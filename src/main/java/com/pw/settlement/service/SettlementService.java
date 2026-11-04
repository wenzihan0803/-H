package com.pw.settlement.service;

import com.pw.booster.service.BoosterEarningService;
import com.pw.common.config.BusinessProperties;
import com.pw.common.exception.BizException;
import com.pw.common.exception.ErrorCode;
import com.pw.common.util.IdGenerator;
import com.pw.common.util.MoneyUtil;
import com.pw.order.entity.BizOrder;
import com.pw.settlement.entity.Settlement;
import com.pw.settlement.mapper.SettlementMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 结算服务。写入 {@code settlement} 并为打手收益入账。
 *
 * <p>口径来源（唯一）：技术规范/01 §7.2、README §0.1 第 4 点、§2.2。</p>
 *
 * <p><b>两种结算</b>（2026-10-29 口径更新：平台费只在确认收货后收取）：</p>
 * <table border="1">
 *   <caption>结算口径</caption>
 *   <tr><th>类型</th><th>结算基数</th><th>打手应得</th><th>平台费</th></tr>
 *   <tr><td>NORMAL（客户确认 / 7 天自动确认）</td><td>实付 payAmount</td>
 *       <td><b>原价</b> × 85%</td><td>实付 − 打手应得</td></tr>
 *   <tr><td>PARTIAL_REFUND（争议部分退款，未确认收货）</td><td>剩余 retained</td>
 *       <td>剩余 × 100%（全归打手）</td><td>0（未确认不收费）</td></tr>
 * </table>
 *
 * <p><b>为什么 NORMAL 的打手收益按原价算</b>：会员 9 折、邀请抵扣都由平台承担，
 * 不能从打手收益里扣（README §4.4「打手仍 85，平台 5」）。
 * 例：原价 100，会员 9 折实付 90 → 打手 85、平台 5。</p>
 *
 * <p><b>全额退款不产生结算单</b>（README §2.2）。</p>
 *
 * <p><b>幂等三重保障</b>（01 §16「结算重复入账」）：调用方订单行锁 +
 * {@code settlement.uk_order} 唯一 + {@code booster_earning_txn.uk_biz} 唯一。</p>
 *
 * <p><b>无 T+7 冻结期</b>：两种结算都直接进 available，可立即提现（README §0.1）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementService {

    private final SettlementMapper settlementMapper;
    private final BoosterEarningService earningService;
    private final BusinessProperties business;
    private final FeePolicy feePolicy;
    private final IdGenerator idGenerator;

    /**
     * 正常完成结算（客户确认或 7 天自动确认）。
     *
     * <p>调用前提：调用方已锁订单（PENDING_CONFIRM）与托管金（HELD）。</p>
     *
     * @param order 订单（需含 originalAmount、payAmount、boosterId）
     * @return 本次创建的结算单；若已结算过则返回既有结算单（幂等）
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public Settlement settleNormal(BizOrder order) {
        Settlement existing = settlementMapper.selectByOrderId(order.getId());
        if (existing != null) {
            log.info("订单已结算，幂等返回既有结算单 orderId={} settlementId={}",
                    order.getId(), existing.getId());
            return existing;
        }

        long payAmount = nz(order.getPayAmount());
        long originalAmount = nz(order.getOriginalAmount());
        // 2026-10-31 口径：平台费 = 原价 × 15%（会员 10%），打手 = 实付 − 平台费
        int platformRate = feePolicy.platformRate(order.getCustomerId());
        boolean member = feePolicy.isMember(order.getCustomerId());
        long platformAmount = feePolicy.platformFee(originalAmount, platformRate);
        long boosterAmount = feePolicy.boosterAmount(payAmount, platformAmount);

        Settlement settlement = insertSettlement(order, "NORMAL", 0L, payAmount,
                boosterAmount, platformAmount, platformRate, member);
        earningService.settle(order.getBoosterId(), boosterAmount, order.getOrderNo(), order.getId(),
                "订单完成结算（原价 " + MoneyUtil.fenToYuan(originalAmount) + " 元 × 平台 "
                        + platformRate + "%）");
        log.info("正常结算完成 orderId={} orderNo={} platformRate={}% boosterAmount={}分 platformAmount={}分",
                order.getId(), order.getOrderNo(), platformRate, boosterAmount, platformAmount);
        return settlement;
    }

    /**
     * 争议部分退款结算（主管审批通过后调用）。
     *
     * <p><b>口径变更（2026-10-29 用户拍板）</b>：平台费只在「确认收货（COMPLETED）」时收取。
     * 争议退款发生在确认之前，因此<b>不扣平台费</b>：
     * {@code settlementBase = retained}，打手应得 = {@code retained} 全额，平台费 = 0。</p>
     *
     * <p>客户侧退款金额 = 下单实付 × 比例（{@code refundAmount}），
     * 剩余 {@code retained = payAmount - refundAmount} 全部转打手收益。</p>
     *
     * @param order        订单
     * @param refundAmount 已退给客户金额（分）
     * @param retained     剩余金额 = 实付 − 退款（分），必须 > 0，全额给打手
     * @return 本次创建的结算单；若已结算过则返回既有结算单（幂等）
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public Settlement settlePartialRefund(BizOrder order, long refundAmount, long retained) {
        MoneyUtil.requirePositive(retained, "部分退款结算基数");
        Settlement existing = settlementMapper.selectByOrderId(order.getId());
        if (existing != null) {
            log.info("订单已结算，幂等返回既有结算单 orderId={} settlementId={}",
                    order.getId(), existing.getId());
            return existing;
        }

        // 未确认收货不收平台费：剩余金额全归打手（2026-10-29 口径）
        long platformFee = 0L;
        long boosterAmount = retained;

        Settlement settlement = insertSettlement(order, "PARTIAL_REFUND", refundAmount, retained,
                boosterAmount, platformFee, 0, false);
        earningService.settle(order.getBoosterId(), boosterAmount, order.getOrderNo(), order.getId(),
                "争议部分退款结算（剩余 " + MoneyUtil.fenToYuan(retained)
                        + " 元全归打手，未确认收货不扣平台费）");
        log.info("部分退款结算完成 orderId={} refundAmount={}分 retained={}分 boosterAmount={}分 platformFee=0",
                order.getId(), refundAmount, retained, boosterAmount);
        return settlement;
    }

    /** 按订单查结算单（不存在返回 null）。 */
    public Settlement findByOrderId(Long orderId) {
        return settlementMapper.selectByOrderId(orderId);
    }

    private Settlement insertSettlement(BizOrder order, String type, long refundAmount,
                                        long settlementBase, long boosterAmount, long platformAmount,
                                        int platformRate, boolean member) {
        LocalDateTime now = LocalDateTime.now();
        Settlement settlement = new Settlement();
        settlement.setId(idGenerator.nextId());
        settlement.setOrderId(order.getId());
        settlement.setBoosterId(order.getBoosterId());
        settlement.setType(type);
        settlement.setOriginalAmount(nz(order.getOriginalAmount()));
        settlement.setPayAmount(nz(order.getPayAmount()));
        settlement.setRefundAmount(refundAmount);
        settlement.setSettlementBase(settlementBase);
        settlement.setBoosterAmount(boosterAmount);
        settlement.setPlatformAmount(platformAmount);
        settlement.setPlatformRate(platformRate);
        settlement.setMemberFlag(member ? 1 : 0);
        settlement.setSettledAt(now);
        settlement.setCreatedAt(now);
        try {
            settlementMapper.insert(settlement);
        } catch (DuplicateKeyException ex) {
            // uk_order 命中：并发下另一线程已结算，回查返回既有单据，保证幂等
            log.info("结算并发冲突，回查既有结算单 orderId={}", order.getId());
            Settlement concurrent = settlementMapper.selectByOrderId(order.getId());
            if (concurrent == null) {
                throw BizException.of(ErrorCode.SYSTEM_ERROR, "结算写入冲突且回查为空，请联系客服");
            }
            return concurrent;
        }
        return settlement;
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }
}
