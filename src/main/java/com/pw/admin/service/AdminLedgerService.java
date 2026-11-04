package com.pw.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pw.admin.dto.AdminDto;
import com.pw.booster.entity.BoosterEarningTxn;
import com.pw.booster.mapper.BoosterEarningTxnMapper;
import com.pw.gold.entity.CustomerGoldTxn;
import com.pw.gold.mapper.CustomerGoldTxnMapper;
import com.pw.order.dto.OrderDto;
import com.pw.order.entity.BizOrder;
import com.pw.order.entity.HandoverLog;
import com.pw.order.entity.OrderProgress;
import com.pw.order.mapper.HandoverLogMapper;
import com.pw.order.service.OrderProgressRecorder;
import com.pw.order.service.OrderQueryService;
import com.pw.refund.entity.Refund;
import com.pw.refund.service.RefundService;
import com.pw.settlement.entity.Settlement;
import com.pw.settlement.service.SettlementService;
import com.pw.common.enums.Role;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 管理端流水装配服务（对账用）。对应 04 §2.4 {@code OrderLedger}、§3.7 流水页。
 *
 * <p><b>权限</b>（README §2.3）：仅管理员/超管可看流水，客服 403——
 * 门禁在控制器层用 {@code Role.canViewLedger()} 把关，本服务只负责装配。</p>
 *
 * <p>把一笔订单的全部资金足迹汇总到一处：状态轨迹、退款历史、结算单、
 * 客户金砖流水、打手收益流水、交接日志。用于对账与争议核查（01 §13）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminLedgerService {

    private final OrderQueryService queryService;
    private final OrderProgressRecorder progressRecorder;
    private final RefundService refundService;
    private final SettlementService settlementService;
    private final CustomerGoldTxnMapper goldTxnMapper;
    private final BoosterEarningTxnMapper earningTxnMapper;
    private final HandoverLogMapper handoverLogMapper;

    /**
     * 装配单笔订单的完整流水。
     *
     * @param orderId 订单 ID
     * @param viewer  查看者角色（用于详情装配的字段可见性；流水页恒为管理端视角）
     */
    public AdminDto.OrderLedger buildLedger(Long orderId, Role viewer) {
        BizOrder order = queryService.requireOrder(orderId);
        OrderDto.Detail detail = queryService.toDetail(order, viewer, null);

        // 状态轨迹
        List<OrderDto.Progress> progress = new ArrayList<>();
        for (OrderProgress p : progressRecorder.listByOrderId(orderId)) {
            progress.add(OrderDto.Progress.builder()
                    .fromStatus(p.getFromStatus())
                    .toStatus(p.getToStatus())
                    .action(p.getAction())
                    .operatorType(p.getOperatorType())
                    .remark(p.getRemark())
                    .createdAt(p.getCreatedAt())
                    .build());
        }

        // 退款历史（管理端视角，含金额拆分）
        List<AdminDto.AdminRefund> refunds = new ArrayList<>();
        for (Refund r : refundService.listByOrderId(orderId)) {
            refunds.add(toAdminRefund(r, order));
        }

        // 结算单
        Settlement settlement = settlementService.findByOrderId(orderId);
        OrderDto.SettlementInfo settlementInfo = settlement == null ? null
                : OrderDto.SettlementInfo.builder()
                .orderId(settlement.getOrderId())
                .type(settlement.getType())
                .originalAmount(settlement.getOriginalAmount())
                .payAmount(settlement.getPayAmount())
                .refundAmount(settlement.getRefundAmount())
                .settlementBase(settlement.getSettlementBase())
                .boosterAmount(settlement.getBoosterAmount())
                .platformAmount(settlement.getPlatformAmount())
                .settledAt(settlement.getSettledAt())
                .build();

        // 客户金砖流水（按订单）
        List<AdminDto.GoldTxn> goldTxns = new ArrayList<>();
        for (CustomerGoldTxn t : goldTxnMapper.selectList(new LambdaQueryWrapper<CustomerGoldTxn>()
                .eq(CustomerGoldTxn::getOrderId, orderId)
                .orderByAsc(CustomerGoldTxn::getCreatedAt))) {
            goldTxns.add(AdminDto.GoldTxn.builder()
                    .id(t.getId())
                    .bizType(t.getBizType())
                    .bizNo(t.getBizNo())
                    .amount(t.getAmount())
                    .balanceAfter(t.getBalanceAfter())
                    .remark(t.getRemark())
                    .createdAt(t.getCreatedAt())
                    .build());
        }

        // 打手收益流水（按订单）
        List<AdminDto.EarningTxn> earningTxns = new ArrayList<>();
        for (BoosterEarningTxn t : earningTxnMapper.selectList(new LambdaQueryWrapper<BoosterEarningTxn>()
                .eq(BoosterEarningTxn::getOrderId, orderId)
                .orderByAsc(BoosterEarningTxn::getCreatedAt))) {
            earningTxns.add(AdminDto.EarningTxn.builder()
                    .id(t.getId())
                    .bizType(t.getBizType())
                    .bizNo(t.getBizNo())
                    .amount(t.getAmount())
                    .frozenAfter(t.getFrozenAfter())
                    .availableAfter(t.getAvailableAfter())
                    .remark(t.getRemark())
                    .createdAt(t.getCreatedAt())
                    .build());
        }

        // 交接日志
        List<AdminDto.HandoverLog> handoverLogs = new ArrayList<>();
        for (HandoverLog hl : handoverLogMapper.listByOrderId(orderId)) {
            handoverLogs.add(AdminDto.HandoverLog.builder()
                    .action(hl.getAction())
                    .channel(hl.getChannel())
                    .operatorId(hl.getOperatorId())
                    .createdAt(hl.getCreatedAt())
                    .build());
        }

        return AdminDto.OrderLedger.builder()
                .order(detail)
                .progress(progress)
                .refunds(refunds)
                .settlement(settlementInfo)
                .goldTxns(goldTxns)
                .earningTxns(earningTxns)
                .handoverLogs(handoverLogs)
                .build();
    }

    private AdminDto.AdminRefund toAdminRefund(Refund r, BizOrder order) {
        return AdminDto.AdminRefund.builder()
                .id(r.getId())
                .refundNo(r.getRefundNo())
                .orderId(r.getOrderId())
                .orderNo(order.getOrderNo())
                .customerId(order.getCustomerId())
                .type(r.getType())
                .refundRate(r.getRefundRate())
                .payAmount(r.getPayAmount())
                .refundAmount(r.getRefundAmount())
                .retainedAmount(r.getRetainedAmount())
                .commissionAmount(r.getCommissionAmount())
                .boosterAmount(r.getBoosterAmount())
                .previousOrderStatus(r.getPreviousOrderStatus())
                .status(r.getStatus())
                .reason(r.getReason())
                .applicantType(r.getApplicantType())
                .applicantId(r.getApplicantId())
                .approverId(r.getApproverId())
                .createdAt(r.getCreatedAt())
                .canApprove(false)
                .build();
    }
}
