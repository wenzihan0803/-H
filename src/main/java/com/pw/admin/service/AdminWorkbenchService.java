package com.pw.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pw.admin.dto.WorkbenchDto;
import com.pw.admin.entity.PunishmentQuota;
import com.pw.admin.mapper.PunishmentQuotaMapper;
import com.pw.booster.entity.BoosterApply;
import com.pw.booster.mapper.BoosterApplyMapper;
import com.pw.common.enums.OrderStatus;
import com.pw.order.entity.BizOrder;
import com.pw.order.entity.Complaint;
import com.pw.order.entity.OrderFundHold;
import com.pw.order.mapper.BizOrderMapper;
import com.pw.order.mapper.ComplaintMapper;
import com.pw.order.mapper.OrderFundHoldMapper;
import com.pw.refund.mapper.RefundMapper;
import com.pw.withdraw.mapper.WithdrawMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端工作台聚合。
 *
 * <p>口径：</p>
 * <ul>
 *   <li>今日 GMV = 今日创建订单的实付合计 − 这些订单已退款金额（按客户实付口径）；</li>
 *   <li>完单率 = 今日创建订单中 COMPLETED 占比；</li>
 *   <li>争议数 = 当前 DISPUTING 订单数；</li>
 *   <li>待办 = 各表当前待处理计数；配额读 {@code punishment_quota}。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminWorkbenchService {

    private final BizOrderMapper orderMapper;
    private final OrderFundHoldMapper holdMapper;
    private final RefundMapper refundMapper;
    private final WithdrawMapper withdrawMapper;
    private final BoosterApplyMapper applyMapper;
    private final ComplaintMapper complaintMapper;
    private final PunishmentQuotaMapper quotaMapper;

    public WorkbenchDto.View build() {
        LocalDate today = LocalDate.now();
        LocalDateTime start = today.atStartOfDay();
        LocalDateTime end = today.plusDays(1).atStartOfDay();
        List<BizOrder> orders = orderMapper.selectList(new LambdaQueryWrapper<BizOrder>()
                .ge(BizOrder::getCreatedAt, start)
                .lt(BizOrder::getCreatedAt, end));

        long gmv = 0;
        long completed = 0;
        for (BizOrder order : orders) {
            OrderFundHold hold = holdMapper.selectByOrderId(order.getId());
            long refunded = hold == null ? 0L : nz(hold.getRefundedAmount());
            gmv += nz(order.getPayAmount()) - refunded;
            if (OrderStatus.COMPLETED.name().equals(order.getStatus())) {
                completed++;
            }
        }
        long count = orders.size();
        String completeRate = count == 0 ? "0.00" : String.format("%.2f", completed * 100.0 / count);

        long disputeCount = orderMapper.selectCount(new LambdaQueryWrapper<BizOrder>()
                .eq(BizOrder::getStatus, OrderStatus.DISPUTING.name()));

        WorkbenchDto.Todo todo = WorkbenchDto.Todo.builder()
                .pendingAccept(countOrder(OrderStatus.PENDING_ACCEPT))
                .pendingHandover(countOrder(OrderStatus.ACCEPTED))
                .inService(countOrder(OrderStatus.IN_SERVICE))
                .pendingConfirm(countOrder(OrderStatus.PENDING_CONFIRM))
                .refundApproval(nz(refundMapper.selectCount(new LambdaQueryWrapper<com.pw.refund.entity.Refund>()
                        .eq(com.pw.refund.entity.Refund::getStatus, "PENDING_APPROVAL"))))
                .withdrawApproval(nz(withdrawMapper.selectCount(new LambdaQueryWrapper<com.pw.withdraw.entity.Withdraw>()
                        .eq(com.pw.withdraw.entity.Withdraw::getStatus, "APPLIED"))))
                .boosterApply(nz(applyMapper.selectCount(new LambdaQueryWrapper<BoosterApply>()
                        .eq(BoosterApply::getStatus, BoosterApply.STATUS_PENDING))))
                .complaintOpen(nz(complaintMapper.selectCount(new LambdaQueryWrapper<Complaint>()
                        .in(Complaint::getStatus, Complaint.STATUS_OPEN, Complaint.STATUS_PROCESSING))))
                .build();

        return WorkbenchDto.View.builder()
                .today(WorkbenchDto.Today.builder()
                        .gmv(gmv)
                        .orderCount(count)
                        .completeRate(completeRate)
                        .disputeCount(disputeCount)
                        .build())
                .todo(todo)
                .quota(WorkbenchDto.Quota.builder()
                        .csDailyPunish(quota("CS", true))
                        .adminMonthlyPunish(quota("ADMIN", false))
                        .build())
                .build();
    }

    private long countOrder(OrderStatus status) {
        return nz(orderMapper.selectCount(new LambdaQueryWrapper<BizOrder>()
                .eq(BizOrder::getStatus, status.name())));
    }

    private WorkbenchDto.QuotaItem quota(String role, boolean daily) {
        PunishmentQuota q = quotaMapper.selectOne(new LambdaQueryWrapper<PunishmentQuota>()
                .eq(PunishmentQuota::getRole, role)
                .last("LIMIT 1"));
        if (q == null) {
            return WorkbenchDto.QuotaItem.builder().used(0L).limit(0L).build();
        }
        return WorkbenchDto.QuotaItem.builder()
                .used(daily ? (long) q.getUsedToday() : (long) q.getUsedMonth())
                .limit(daily ? (long) q.getDailyLimit() : (long) q.getMonthlyLimit())
                .build();
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }
}
