package com.pw.payment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pw.common.config.BusinessProperties;
import com.pw.common.exception.BizException;
import com.pw.common.exception.ErrorCode;
import com.pw.common.util.IdGenerator;
import com.pw.common.util.MoneyUtil;
import com.pw.gold.service.GoldAccountService;
import com.pw.member.entity.MemberAccount;
import com.pw.member.entity.MemberApply;
import com.pw.member.mapper.MemberAccountMapper;
import com.pw.member.mapper.MemberApplyMapper;
import com.pw.payment.entity.RechargeOrder;
import com.pw.payment.mapper.RechargeOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 充值到账服务。
 *
 * <p>口径来源：技术规范/01 §4「充值（微信支付）」、§9「会员」；README §4。</p>
 *
 * <p>到账五步（01 §4 第 4 步）：</p>
 * <ol>
 *   <li>按 out_trade_no 查充值单；</li>
 *   <li>校验金额（防篡改）；</li>
 *   <li>已 SUCCESS 直接返回（幂等）；</li>
 *   <li>更新充值单 + {@code customer_gold_account.balance} + {@code customer_gold_txn(RECHARGE)}；</li>
 *   <li>单笔 ≥ 2,000 元自动建 {@code member_apply(PENDING)}。</li>
 * </ol>
 *
 * <p>本服务被两个入口共用：微信回调 {@code /webhooks/wechat/recharge} 与
 * dev 模拟接口 {@code /dev/recharge-sim}（09 §3）。两条路径的资金语义必须完全一致。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RechargeCreditService {

    private final RechargeOrderMapper rechargeOrderMapper;
    private final MemberApplyMapper memberApplyMapper;
    private final MemberAccountMapper memberAccountMapper;
    private final GoldAccountService goldAccountService;
    private final IdGenerator idGenerator;
    private final BusinessProperties businessProperties;

    /**
     * 创建充值单（PENDING）。对应 04 §3.2 {@code POST /recharges}。
     *
     * @param userId  客户 ID
     * @param amount  充值金额（分），≥ 1 元（04 §2.2 CreateRechargeReq）
     * @param purpose NORMAL / MEMBER
     */
    @Transactional(rollbackFor = Exception.class)
    public RechargeOrder create(Long userId, long amount, String purpose) {
        // 04 §2.2：amount ≥ 1 元
        if (amount < 100L) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "充值金额最低 1 元，请调整后重试");
        }
        MoneyUtil.requirePositive(amount, "充值金额");

        String targetPurpose = (purpose == null || purpose.isBlank())
                ? RechargeOrder.PURPOSE_NORMAL : purpose.toUpperCase();
        if (!RechargeOrder.PURPOSE_NORMAL.equals(targetPurpose)
                && !RechargeOrder.PURPOSE_MEMBER.equals(targetPurpose)) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "充值用途只能是普通或会员，请重新选择");
        }

        RechargeOrder order = new RechargeOrder();
        order.setId(idGenerator.nextId());
        order.setRechargeNo(idGenerator.nextRechargeNo());
        order.setUserId(userId);
        order.setAmount(amount);
        order.setPurpose(targetPurpose);
        order.setChannel("WECHAT");
        order.setStatus(RechargeOrder.STATUS_PENDING);
        rechargeOrderMapper.insert(order);

        log.info("充值单已创建 rechargeNo={} userId={} amount={}分", order.getRechargeNo(), userId, amount);
        return order;
    }

    /**
     * 充值到账。幂等：重复调用不会重复加金砖。
     *
     * @param rechargeOrder  充值单（必须已存在）
     * @param transactionId  微信支付单号；dev 模拟时传 {@code SIM} + 时间戳
     * @param rawJson        回调原文，留档对账
     * @return true=本次真实到账；false=幂等命中（此前已到账）
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean credit(RechargeOrder rechargeOrder, String transactionId, String rawJson) {
        // 步骤 3：已 SUCCESS 直接返回（01 §4）
        if (rechargeOrder.isSuccess()) {
            log.info("充值单已到账，幂等返回 rechargeNo={}", rechargeOrder.getRechargeNo());
            return false;
        }
        if (!rechargeOrder.isPending()) {
            throw BizException.of(ErrorCode.STATE_CONFLICT, "充值单状态异常，请联系客服核实");
        }

        LocalDateTime now = LocalDateTime.now();
        // 步骤 4a：条件更新充值单，抢不到说明并发已处理
        int rows = rechargeOrderMapper.markSuccess(rechargeOrder.getId(), transactionId, now, now, rawJson);
        if (rows == 0) {
            log.info("充值单并发到账，幂等返回 rechargeNo={}", rechargeOrder.getRechargeNo());
            return false;
        }

        // 步骤 4b：加金砖 + 写 RECHARGE 流水（同一事务）
        goldAccountService.recharge(rechargeOrder.getUserId(), rechargeOrder.getAmount(),
                rechargeOrder.getRechargeNo(), "微信充值到账");

        // 步骤 5：单笔 ≥ 2,000 元自动建会员申请
        createMemberApplyIfNeeded(rechargeOrder);

        log.info("充值到账成功 rechargeNo={} userId={} amount={}分 transactionId={}",
                rechargeOrder.getRechargeNo(), rechargeOrder.getUserId(),
                rechargeOrder.getAmount(), transactionId);
        return true;
    }

    /**
     * 单笔充值满 2,000 元直接成为会员（2026-10-31 口径）。
     *
     * <p>不再需要管理员审批：到账即写 {@code member_apply(APPROVED)} 留痕，
     * 同时把 {@code member_account.level} 置为 MEMBER、永久有效。</p>
     *
     * <p>{@code uk_recharge} 唯一索引保证一笔充值最多一条申请；重复回调幂等。</p>
     */
    private void createMemberApplyIfNeeded(RechargeOrder rechargeOrder) {
        long threshold = businessProperties.getMemberThresholdAmount();
        if (rechargeOrder.getAmount() < threshold) {
            return;
        }
        Long exists = memberApplyMapper.selectCount(new LambdaQueryWrapper<MemberApply>()
                .eq(MemberApply::getRechargeId, rechargeOrder.getId()));
        if (exists != null && exists > 0) {
            grantMember(rechargeOrder);
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        MemberApply apply = new MemberApply();
        apply.setId(idGenerator.nextId());
        apply.setUserId(rechargeOrder.getUserId());
        apply.setRechargeId(rechargeOrder.getId());
        apply.setSource(MemberApply.SOURCE_SELF);
        apply.setStatus(MemberApply.STATUS_APPROVED);
        apply.setReviewedAt(now);
        try {
            memberApplyMapper.insert(apply);
            log.info("单笔充值满 {} 元，自动成为会员 userId={} applyId={}",
                    MoneyUtil.fenToYuan(threshold), rechargeOrder.getUserId(), apply.getId());
        } catch (DuplicateKeyException ex) {
            log.info("会员申请并发创建，跳过 rechargeId={}", rechargeOrder.getId());
        }
        grantMember(rechargeOrder);
    }

    /** 会员资格落库：没有账户就建，已有账户就置为 MEMBER（永久）。 */
    private void grantMember(RechargeOrder rechargeOrder) {
        LocalDateTime now = LocalDateTime.now();
        MemberAccount account = memberAccountMapper.selectById(rechargeOrder.getUserId());
        if (account == null) {
            account = new MemberAccount();
            account.setUserId(rechargeOrder.getUserId());
            account.setLevel(MemberAccount.LEVEL_MEMBER);
            account.setQualifiedRechargeId(rechargeOrder.getId());
            account.setIsPermanent(1);
            account.setApprovedAt(now);
            memberAccountMapper.insert(account);
            return;
        }
        if (account.isMember() && Integer.valueOf(1).equals(account.getIsPermanent())) {
            return;
        }
        account.setLevel(MemberAccount.LEVEL_MEMBER);
        account.setQualifiedRechargeId(rechargeOrder.getId());
        account.setIsPermanent(1);
        account.setApprovedAt(now);
        memberAccountMapper.updateById(account);
    }

    /** 按 ID 查充值单。 */
    public RechargeOrder findById(Long id) {
        return rechargeOrderMapper.selectById(id);
    }

    /** 按充值单号查。微信回调按 out_trade_no 定位。 */
    public RechargeOrder findByRechargeNo(String rechargeNo) {
        return rechargeOrderMapper.selectOne(new LambdaQueryWrapper<RechargeOrder>()
                .eq(RechargeOrder::getRechargeNo, rechargeNo)
                .last("LIMIT 1"));
    }

    /** 按 ID 查并校验归属（防 IDOR，01 §2）。 */
    public RechargeOrder requireOwned(Long id, Long userId) {
        RechargeOrder order = rechargeOrderMapper.selectById(id);
        if (order == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "充值单不存在，请返回列表刷新");
        }
        if (!order.getUserId().equals(userId)) {
            throw BizException.of(ErrorCode.FORBIDDEN, "无法查看他人的充值记录");
        }
        return order;
    }
}
