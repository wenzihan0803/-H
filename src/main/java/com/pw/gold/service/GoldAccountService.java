package com.pw.gold.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pw.common.enums.GoldTxnType;
import com.pw.common.exception.BizException;
import com.pw.common.exception.ErrorCode;
import com.pw.common.util.IdGenerator;
import com.pw.common.util.MoneyUtil;
import com.pw.gold.entity.CustomerGoldAccount;
import com.pw.gold.entity.CustomerGoldTxn;
import com.pw.gold.mapper.CustomerGoldAccountMapper;
import com.pw.gold.mapper.CustomerGoldTxnMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 客户金砖账户服务：账户 + 流水的唯一写入入口。
 *
 * <p>口径来源：技术规范/00 §7 ADR#5「金砖用账户 + 流水，可审计可对账，不能只存余额」、
 * /05 §1.2 硬约束、/01 §14 幂等与并发。</p>
 *
 * <p><b>三条铁律</b>：</p>
 * <ol>
 *   <li>任何余额变更必须在事务内、先 {@code FOR UPDATE} 锁行；</li>
 *   <li>余额变更与流水写入必须原子——本类所有 public 方法都带事务；</li>
 *   <li>流水 {@code (biz_type,biz_no)} 唯一，写入冲突即为重复，返回已存在结果而非报错重试。</li>
 * </ol>
 *
 * <p>传播级别用 {@code MANDATORY}：强制调用方已有事务，避免资金操作脱离订单/充值主事务
 * 导致「金砖扣了但订单没建」的不一致。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoldAccountService {

    private final CustomerGoldAccountMapper accountMapper;
    private final CustomerGoldTxnMapper txnMapper;
    private final IdGenerator idGenerator;

    /**
     * 充值到账：balance += amount，写 RECHARGE 流水。
     *
     * <p>幂等：bizNo 传充值单号 {@code recharge_no}，唯一索引保证重复回调只入账一次。</p>
     *
     * @param userId  客户 ID
     * @param amount  充值金额（分），必须 > 0
     * @param bizNo   业务单号（充值单号），幂等键
     * @param remark  流水备注
     * @return true=本次真实入账；false=已入账过（幂等命中）
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public boolean recharge(Long userId, long amount, String bizNo, String remark) {
        MoneyUtil.requirePositive(amount, "充值金额");
        return credit(userId, amount, GoldTxnType.RECHARGE, bizNo, null, remark, true);
    }

    /**
     * 下单托管扣减：balance -= amount，写 ORDER_HOLD 流水（负值）。
     *
     * <p>余额不足抛 {@link ErrorCode#GOLD_NOT_ENOUGH}（60008）。</p>
     *
     * @param userId  客户 ID
     * @param amount  实付金额（分），必须 > 0
     * @param bizNo   订单号，幂等键
     * @param orderId 订单 ID
     * @return 扣减后的余额（分）
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public long holdForOrder(Long userId, long amount, String bizNo, Long orderId) {
        MoneyUtil.requirePositive(amount, "订单金额");
        CustomerGoldAccount account = accountMapper.selectForUpdate(userId);
        if (account == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "金砖账户不存在，请重新登录后重试");
        }
        int rows = accountMapper.deductIfEnough(userId, amount);
        if (rows == 0) {
            // 区分「余额不足」与「并发冲突」：重新读锁后余额仍不足即为余额不足
            long balance = account.getBalanceOrZero();
            if (balance < amount) {
                throw BizException.of(ErrorCode.GOLD_NOT_ENOUGH,
                        "金砖余额不足，当前 " + MoneyUtil.fenToYuan(balance) + " 元，请先充值");
            }
            throw BizException.of(ErrorCode.STATE_CONFLICT, "下单处理中，请勿重复提交");
        }
        long balanceAfter = account.getBalanceOrZero() - amount;
        insertTxn(userId, GoldTxnType.ORDER_HOLD, bizNo, -amount, balanceAfter, orderId, "下单托管");
        return balanceAfter;
    }

    /**
     * 退款回金砖：balance += amount，写 ORDER_REFUND 流水。
     *
     * <p>幂等：bizNo 传退款单号 {@code refund_no}，唯一索引保证同一退款只回一次金砖。</p>
     *
     * @param userId  客户 ID
     * @param amount  退款金额（分），必须 > 0
     * @param bizNo   退款单号，幂等键
     * @param orderId 订单 ID
     * @return true=本次真实退款；false=已退过（幂等命中）
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public boolean refundToGold(Long userId, long amount, String bizNo, Long orderId) {
        MoneyUtil.requirePositive(amount, "退款金额");
        return credit(userId, amount, GoldTxnType.ORDER_REFUND, bizNo, orderId, "订单退款", false);
    }

    /**
     * 人工调账（管理员线下特批）：balance += delta，写 ADJUST 流水。
     *
     * <p>delta 可正可负，但扣减后不得为负。</p>
     *
     * @param userId 客户 ID
     * @param delta  调账金额（分），有符号
     * @param bizNo  调账单号，幂等键
     * @param remark 调账原因
     * @return 调账后余额
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public long adjust(Long userId, long delta, String bizNo, String remark) {
        if (delta == 0) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "调账金额不能为 0");
        }
        CustomerGoldAccount account = accountMapper.selectForUpdate(userId);
        if (account == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "金砖账户不存在，请重新登录后重试");
        }
        int rows = accountMapper.adjust(userId, delta);
        if (rows == 0) {
            throw BizException.of(ErrorCode.GOLD_NOT_ENOUGH, "调账后余额将为负，操作被拒绝");
        }
        long balanceAfter = account.getBalanceOrZero() + delta;
        insertTxn(userId, GoldTxnType.ADJUST, bizNo, delta, balanceAfter, null, remark);
        return balanceAfter;
    }

    /** 查询余额（分）。账户不存在时返回 0，不抛异常。 */
    public long getBalance(Long userId) {
        CustomerGoldAccount account = accountMapper.selectById(userId);
        return account == null ? 0L : account.getBalanceOrZero();
    }

    /**
     * 通用入账：加余额 + 写正向流水，带幂等。
     *
     * @param rechargeDelta 是否计入 total_recharged（仅 RECHARGE 为 true）
     */
    private boolean credit(Long userId, long amount, GoldTxnType bizType, String bizNo, Long orderId,
                           String remark, boolean rechargeDelta) {
        // 幂等前置检查：流水已存在说明已入账
        if (txnExists(bizType, bizNo)) {
            log.info("金砖流水已存在，幂等跳过 bizType={} bizNo={}", bizType, bizNo);
            return false;
        }
        CustomerGoldAccount account = accountMapper.selectForUpdate(userId);
        if (account == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "金砖账户不存在，请重新登录后重试");
        }
        int rows = accountMapper.increase(userId, amount, rechargeDelta ? amount : 0L);
        if (rows == 0) {
            throw BizException.of(ErrorCode.SYSTEM_ERROR, "金砖入账失败，请稍后重试");
        }
        long balanceAfter = account.getBalanceOrZero() + amount;
        try {
            insertTxn(userId, bizType, bizNo, amount, balanceAfter, orderId, remark);
        } catch (DuplicateKeyException ex) {
            // 并发下另一线程已写入同一流水：抛出走整体回滚（含上面的加余额，本次变更不生效）；
            // 调用方（如充值回调）收到失败后重试，重试时命中 txnExists 幂等检查，不会重复入账
            log.info("金砖入账并发冲突，整体回滚本次变更，待调用方重试后命中幂等 bizType={} bizNo={}",
                    bizType, bizNo);
            throw ex;
        }
        return true;
    }

    private boolean txnExists(GoldTxnType bizType, String bizNo) {
        Long count = txnMapper.selectCount(new LambdaQueryWrapper<CustomerGoldTxn>()
                .eq(CustomerGoldTxn::getBizType, bizType.name())
                .eq(CustomerGoldTxn::getBizNo, bizNo));
        return count != null && count > 0;
    }

    private void insertTxn(Long userId, GoldTxnType bizType, String bizNo, long amount,
                           long balanceAfter, Long orderId, String remark) {
        CustomerGoldTxn txn = new CustomerGoldTxn();
        txn.setId(idGenerator.nextId());
        txn.setUserId(userId);
        txn.setBizType(bizType.name());
        txn.setBizNo(bizNo);
        txn.setAmount(amount);
        txn.setBalanceAfter(balanceAfter);
        txn.setOrderId(orderId);
        txn.setRemark(remark);
        txn.setCreatedAt(LocalDateTime.now());
        txnMapper.insert(txn);
    }
}
