package com.pw.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pw.admin.dto.ReportDto;
import com.pw.booster.entity.Booster;
import com.pw.booster.mapper.BoosterMapper;
import com.pw.common.enums.OrderStatus;
import com.pw.order.entity.BizOrder;
import com.pw.order.entity.OrderFundHold;
import com.pw.order.mapper.BizOrderMapper;
import com.pw.order.mapper.OrderFundHoldMapper;
import com.pw.refund.entity.Refund;
import com.pw.refund.mapper.RefundMapper;
import com.pw.settlement.entity.Settlement;
import com.pw.settlement.mapper.SettlementMapper;
import com.pw.user.entity.User;
import com.pw.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 经营报表：订单、GMV、平台/打手结算、退款、新增用户与打手，按日拆分。 */
@Service
@RequiredArgsConstructor
public class AdminReportService {

    private final BizOrderMapper orderMapper;
    private final OrderFundHoldMapper holdMapper;
    private final SettlementMapper settlementMapper;
    private final RefundMapper refundMapper;
    private final UserMapper userMapper;
    private final BoosterMapper boosterMapper;

    public ReportDto.View build(LocalDate from, LocalDate to) {
        LocalDate startDate = from == null ? LocalDate.now().withDayOfMonth(1) : from;
        LocalDate endDate = to == null ? LocalDate.now() : to;
        if (endDate.isBefore(startDate)) {
            LocalDate tmp = startDate;
            startDate = endDate;
            endDate = tmp;
        }
        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = endDate.plusDays(1).atStartOfDay();

        Map<LocalDate, long[]> dailyMap = new LinkedHashMap<>();
        for (LocalDate d = startDate; !d.isAfter(endDate); d = d.plusDays(1)) {
            dailyMap.put(d, new long[3]);
        }

        List<BizOrder> orders = orderMapper.selectList(new LambdaQueryWrapper<BizOrder>()
                .ge(BizOrder::getCreatedAt, start)
                .lt(BizOrder::getCreatedAt, end));
        long gmv = 0;
        long completed = 0;
        for (BizOrder order : orders) {
            OrderFundHold hold = holdMapper.selectByOrderId(order.getId());
            long refunded = hold == null || hold.getRefundedAmount() == null ? 0L : hold.getRefundedAmount();
            long net = nz(order.getPayAmount()) - refunded;
            gmv += net;
            if (OrderStatus.COMPLETED.name().equals(order.getStatus())) {
                completed++;
            }
            long[] cell = dailyMap.get(order.getCreatedAt().toLocalDate());
            if (cell != null) {
                cell[0]++;
                cell[1] += net;
            }
        }

        long platform = 0;
        long booster = 0;
        List<Settlement> settlements = settlementMapper.selectList(new LambdaQueryWrapper<Settlement>()
                .ge(Settlement::getSettledAt, start)
                .lt(Settlement::getSettledAt, end));
        for (Settlement settlement : settlements) {
            platform += nz(settlement.getPlatformAmount());
            booster += nz(settlement.getBoosterAmount());
            long[] cell = dailyMap.get(settlement.getSettledAt().toLocalDate());
            if (cell != null) {
                cell[2] += nz(settlement.getPlatformAmount());
            }
        }

        long refundAmount = 0;
        for (Refund refund : refundMapper.selectList(new LambdaQueryWrapper<Refund>()
                .ge(Refund::getCreatedAt, start)
                .lt(Refund::getCreatedAt, end))) {
            if (!"REJECTED".equals(refund.getStatus()) && !"FAILED".equals(refund.getStatus())) {
                refundAmount += nz(refund.getRefundAmount());
            }
        }

        long newUsers = nz(userMapper.selectCount(new LambdaQueryWrapper<User>()
                .ge(User::getCreatedAt, start).lt(User::getCreatedAt, end)));
        long newBoosters = nz(boosterMapper.selectCount(new LambdaQueryWrapper<Booster>()
                .ge(Booster::getCreatedAt, start).lt(Booster::getCreatedAt, end)));

        long orderCount = orders.size();
        String rate = orderCount == 0 ? "0.00" : String.format("%.2f", completed * 100.0 / orderCount);

        List<ReportDto.Daily> daily = new ArrayList<>();
        for (Map.Entry<LocalDate, long[]> e : dailyMap.entrySet()) {
            daily.add(ReportDto.Daily.builder()
                    .date(e.getKey())
                    .orderCount(e.getValue()[0])
                    .gmv(e.getValue()[1])
                    .platformAmount(e.getValue()[2])
                    .build());
        }

        return ReportDto.View.builder()
                .from(startDate)
                .to(endDate)
                .summary(ReportDto.Summary.builder()
                        .orderCount(orderCount)
                        .completedCount(completed)
                        .completeRate(rate)
                        .gmv(gmv)
                        .platformAmount(platform)
                        .boosterAmount(booster)
                        .refundAmount(refundAmount)
                        .newUsers(newUsers)
                        .newBoosters(newBoosters)
                        .build())
                .daily(daily)
                .build();
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }
}
