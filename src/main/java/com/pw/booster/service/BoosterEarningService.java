package com.pw.booster.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pw.booster.entity.BoosterEarningAccount;
import com.pw.booster.entity.BoosterEarningTxn;
import com.pw.booster.mapper.BoosterEarningAccountMapper;
import com.pw.booster.mapper.BoosterEarningTxnMapper;
import com.pw.common.enums.EarningTxnType;
import com.pw.common.exception.BizException;
import com.pw.common.exception.ErrorCode;
import com.pw.common.util.IdGenerator;
import com.pw.common.util.MoneyUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 打手收益账户服务：{@code booster_earning_account} 与 {@code booster_earning_txn} 的唯一写入入口。
 *
 * <p>口径来源：技术规范/README.md §0.1（确认即到账、无 T+7）、01 §7.2 结算、§7.3 收益冻结、
 * /05 §1.2 硬约束、§3.9/§3.10。</p>
 *
 * <p><b>三条铁律</b>（与 {@code GoldAccountService} 对称）：</p>
 * <ol>
 *   <li>任何余额变更必须在事务内、先 {@code FOR UPDATE} 锁行；</li>
 *   <li>余额变更与流水写入必须原子；</li>
 *   <li>流水 {@code (biz_type,biz_no)} 唯一（{@code uk_biz}），冲突即为重复执行，按幂等处理。</li>
 * </ol>
 *
 * <p>传播级别 {@code MANDATORY}：强制调用方已有事务，避免「收益加了但订单没完结」的不一致。</p>
 *
 * <p><b>账户语义</b>：{@code available} 可提现；{@code frozen} 在途冻结（提现中/封号）；
 * {@code total} 历史累计到手。结算只加 available 与 total；提现申请是 available → frozen
 * （total 不变，因为钱还没离开平台）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BoosterEarningService {

    private final BoosterEarningAccountMapper accountMapper;
    private final BoosterEarningTxnMapper txnMapper;
    private final IdGenerator idGenerator;

    /**
     * 结算入账：available += amount, total += amount（README §0.1：确认即到账，可立即提现）。
     *
     * <p>幂等：bizNo 传订单号，{@code uk_biz} 保证同一订单只结算一次。</p>
     *
     * @param boosterId 打手 ID
     * @param amount    收益金额（分），必须 > 0
     * @param bizNo     业务单号（订单号），幂等键
     * @param orderId   订单 ID
     * @param remark    流水备注
     * @return true=本次真实入账；false=已入账过（幂等命中）
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public boolean settle(Long boosterId, long amount, String bizNo, Long orderId, String remark) {
        MoneyUtil.requirePositive(amount, "结算金额");
        if (txnExists(EarningTxnType.SETTLE, bizNo)) {
            log.info("结算流水已存在，幂等跳过 boosterId={} bizNo={}", boosterId, bizNo);
            return false;
        }
        BoosterEarningAccount account = lockAccount(boosterId);
        int rows = accountMapper.settle(boosterId, amount);
        if (rows == 0) {
            throw BizException.of(ErrorCode.SYSTEM_ERROR, "收益入账失败，请稍后重试或联系客服");
        }
        writeTxn(boosterId, EarningTxnType.SETTLE, bizNo, amount,
                nz(account.getFrozen()), nz(account.getAvailable()) + amount, orderId, remark);
        log.info("结算入账成功 boosterId={} amount={}分 orderNo={}", boosterId, amount, bizNo);
        return true;
    }

    /**
     * 提现冻结：available → frozen（01 §7.3-A）。
     *
     * <p>目的：防止审核期间打手继续接单到账新收益，与在途提现金额混淆。</p>
     *
     * @return true=本次真实冻结；false=已冻结过（幂等命中）
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public boolean freezeForWithdraw(Long boosterId, long amount, String bizNo) {
        MoneyUtil.requirePositive(amount, "提现金额");
        if (txnExists(EarningTxnType.WITHDRAW_FREEZE, bizNo)) {
            log.info("提现冻结流水已存在，幂等跳过 boosterId={} bizNo={}", boosterId, bizNo);
            return false;
        }
        BoosterEarningAccount account = lockAccount(boosterId);
        if (nz(account.getAvailable()) < amount) {
            throw BizException.of(ErrorCode.STATE_CONFLICT,
                    "可提现余额不足，当前 " + MoneyUtil.fenToYuan(nz(account.getAvailable())) + " 元");
        }
        int rows = accountMapper.freezeForWithdraw(boosterId, amount);
        if (rows == 0) {
            throw BizException.of(ErrorCode.STATE_CONFLICT, "提现处理中，请勿重复提交");
        }
        writeTxn(boosterId, EarningTxnType.WITHDRAW_FREEZE, bizNo, -amount,
                nz(account.getFrozen()) + amount, nz(account.getAvailable()) - amount, null, "提现申请冻结");
        return true;
    }

    /**
     * 提现成功：frozen -= amount（钱离开平台，total 不变）。
     *
     * @return true=本次真实扣减；false=已处理过（幂等命中）
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public boolean withdrawSuccess(Long boosterId, long amount, String bizNo) {
        MoneyUtil.requirePositive(amount, "提现金额");
        if (txnExists(EarningTxnType.WITHDRAW_SUCCESS, bizNo)) {
            log.info("提现成功流水已存在，幂等跳过 boosterId={} bizNo={}", boosterId, bizNo);
            return false;
        }
        BoosterEarningAccount account = lockAccount(boosterId);
        int rows = accountMapper.deductFrozen(boosterId, amount);
        if (rows == 0) {
            // frozen 不足属数据异常，需告警对账（01 §13 对账）
            throw BizException.of(ErrorCode.SYSTEM_ERROR,
                    "提现扣减异常（冻结金额不足），请联系客服并提供提现单号 " + bizNo);
        }
        writeTxn(boosterId, EarningTxnType.WITHDRAW_SUCCESS, bizNo, -amount,
                nz(account.getFrozen()) - amount, nz(account.getAvailable()), null, "提现打款成功");
        return true;
    }

    /**
     * 提现失败/拒绝：frozen 退回 available（01 §7.3-A、§8 第 5 步）。
     *
     * @return true=本次真实退回；false=已处理过（幂等命中）
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public boolean withdrawFail(Long boosterId, long amount, String bizNo, String reason) {
        MoneyUtil.requirePositive(amount, "提现金额");
        if (txnExists(EarningTxnType.WITHDRAW_FAIL, bizNo)) {
            log.info("提现失败流水已存在，幂等跳过 boosterId={} bizNo={}", boosterId, bizNo);
            return false;
        }
        BoosterEarningAccount account = lockAccount(boosterId);
        int rows = accountMapper.unfreezeToAvailable(boosterId, amount);
        if (rows == 0) {
            throw BizException.of(ErrorCode.SYSTEM_ERROR, "提现退回异常（冻结金额不足），请联系客服");
        }
        writeTxn(boosterId, EarningTxnType.WITHDRAW_FAIL, bizNo, amount,
                nz(account.getFrozen()) - amount, nz(account.getAvailable()) + amount, null, reason);
        return true;
    }

    /**
     * 封号冻结：全部 available 转入 frozen（01 §7.3-B、README §6.2）。
     *
     * <p>封号优先级高于提现：调用方须在同一事务内自动拒绝该打手所有进行中提现。</p>
     *
     * @return 本次冻结的金额（分）；0 表示账户无可提现余额（仍写流水留痕）
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public long freezeAllForPunish(Long boosterId, String bizNo, String reason) {
        BoosterEarningAccount account = lockAccount(boosterId);
        long frozenNow = nz(account.getAvailable());
        if (txnExists(EarningTxnType.PUNISH_FREEZE, bizNo)) {
            log.info("封号冻结流水已存在，幂等跳过 boosterId={} bizNo={}", boosterId, bizNo);
            return 0L;
        }
        int rows = accountMapper.freezeAllForPunish(boosterId);
        if (rows == 0) {
            throw BizException.of(ErrorCode.SYSTEM_ERROR, "封号冻结失败，请稍后重试");
        }
        writeTxn(boosterId, EarningTxnType.PUNISH_FREEZE, bizNo, -frozenNow,
                nz(account.getFrozen()) + frozenNow, 0L, null, reason);
        log.warn("打手封号冻结收益 boosterId={} frozenAmount={}分 reason={}", boosterId, frozenNow, reason);
        return frozenNow;
    }

    /**
     * 解封：封号冻结部分退回 available（01 §7.3-B）。
     *
     * <p><b>必须按封号时 PUNISH_FREEZE 流水的金额解冻</b>，不能把 frozen 清零——
     * frozen 中可能还有别的提现在途。被封时自动拒绝的提现不自动恢复，打手需重新申请。</p>
     *
     * @param unfreezeAmount 应解冻金额（分），来自封号时记录的 PUNISH_FREEZE 金额
     * @return true=本次真实解冻；false=已解冻过或金额为 0
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public boolean unfreezeFromPunish(Long boosterId, long unfreezeAmount, String bizNo) {
        if (unfreezeAmount <= 0) {
            return false;
        }
        if (txnExists(EarningTxnType.PUNISH_UNFREEZE, bizNo)) {
            log.info("解封流水已存在，幂等跳过 boosterId={} bizNo={}", boosterId, bizNo);
            return false;
        }
        BoosterEarningAccount account = lockAccount(boosterId);
        int rows = accountMapper.unfreezeFromPunish(boosterId, unfreezeAmount);
        if (rows == 0) {
            throw BizException.of(ErrorCode.SYSTEM_ERROR,
                    "解封失败：冻结金额不足 " + unfreezeAmount + " 分，请先对账");
        }
        writeTxn(boosterId, EarningTxnType.PUNISH_UNFREEZE, bizNo, unfreezeAmount,
                nz(account.getFrozen()) - unfreezeAmount, nz(account.getAvailable()) + unfreezeAmount,
                null, "封号解除，冻结收益退回");
        log.warn("打手解封解冻收益 boosterId={} unfrozenAmount={}分", boosterId, unfreezeAmount);
        return true;
    }

    /** 查询账户（不存在返回 null）。打手端「冻结/可提现」两栏读这里。 */
    public BoosterEarningAccount getAccount(Long boosterId) {
        return accountMapper.selectById(boosterId);
    }

    /** 分页查询打手收益流水（按时间倒序）。打手端流水页用。 */
    public IPage<BoosterEarningTxn> listTxns(Long boosterId, int page, int pageSize) {
        return txnMapper.selectPage(Page.of(Math.max(1, page), Math.max(1, pageSize)),
                new LambdaQueryWrapper<BoosterEarningTxn>()
                        .eq(BoosterEarningTxn::getBoosterId, boosterId)
                        .orderByDesc(BoosterEarningTxn::getCreatedAt)
                        .orderByDesc(BoosterEarningTxn::getId));
    }

    /** 行锁读取账户，不存在则抛错。必须在事务内调用。 */
    private BoosterEarningAccount lockAccount(Long boosterId) {
        BoosterEarningAccount account = accountMapper.selectForUpdate(boosterId);
        if (account == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "收益账户不存在，请联系客服");
        }
        return account;
    }

    private boolean txnExists(EarningTxnType bizType, String bizNo) {
        Long count = txnMapper.selectCount(new LambdaQueryWrapper<BoosterEarningTxn>()
                .eq(BoosterEarningTxn::getBizType, bizType.name())
                .eq(BoosterEarningTxn::getBizNo, bizNo));
        return count != null && count > 0;
    }

    private void writeTxn(Long boosterId, EarningTxnType bizType, String bizNo, long amount,
                          long frozenAfter, long availableAfter, Long orderId, String remark) {
        BoosterEarningTxn txn = new BoosterEarningTxn();
        txn.setId(idGenerator.nextId());
        txn.setBoosterId(boosterId);
        txn.setBizType(bizType.name());
        txn.setBizNo(bizNo);
        txn.setAmount(amount);
        txn.setFrozenAfter(frozenAfter);
        txn.setAvailableAfter(availableAfter);
        txn.setOrderId(orderId);
        txn.setRemark(remark);
        txn.setCreatedAt(LocalDateTime.now());
        try {
            txnMapper.insert(txn);
        } catch (DuplicateKeyException ex) {
            // 并发下另一线程已写入同一 (biz_type,biz_no)：回滚本次余额变更，由调用方按幂等处理
            log.info("收益流水并发冲突，回滚本次变更 bizType={} bizNo={}", bizType, bizNo);
            throw ex;
        }
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }
}
