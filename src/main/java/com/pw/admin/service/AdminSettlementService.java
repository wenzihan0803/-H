package com.pw.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pw.admin.dto.SettlementSummaryDto;
import com.pw.booster.entity.Booster;
import com.pw.booster.mapper.BoosterMapper;
import com.pw.common.util.MoneyUtil;
import com.pw.order.entity.BizOrder;
import com.pw.order.mapper.BizOrderMapper;
import com.pw.settlement.entity.Settlement;
import com.pw.settlement.mapper.SettlementMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 管理端「合计」：按打手 + 时间查已结算订单，算平台与打手应得，支持导出。
 *
 * <p>数据源是 {@code settlement}（结算单），不重新算钱——保证页面与资金流水一致。</p>
 */
@Service
@RequiredArgsConstructor
public class AdminSettlementService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final List<String> ALL_COLUMNS = List.of(
            "orderNo", "settledAt", "booster", "original", "pay", "refund", "rate", "platform", "boosterAmount");

    private final SettlementMapper settlementMapper;
    private final BizOrderMapper orderMapper;
    private final BoosterMapper boosterMapper;

    public SettlementSummaryDto.Result summary(Long boosterId, LocalDate from, LocalDate to) {
        LambdaQueryWrapper<Settlement> wrapper = new LambdaQueryWrapper<Settlement>()
                .orderByDesc(Settlement::getSettledAt)
                .orderByDesc(Settlement::getId);
        if (boosterId != null) {
            wrapper.eq(Settlement::getBoosterId, boosterId);
        }
        if (from != null) {
            wrapper.ge(Settlement::getSettledAt, from.atStartOfDay());
        }
        if (to != null) {
            wrapper.lt(Settlement::getSettledAt, to.plusDays(1).atStartOfDay());
        }

        List<SettlementSummaryDto.Row> rows = new ArrayList<>();
        long original = 0;
        long pay = 0;
        long refund = 0;
        long platform = 0;
        long boosterAmount = 0;
        for (Settlement s : settlementMapper.selectList(wrapper)) {
            BizOrder order = orderMapper.selectById(s.getOrderId());
            Booster booster = s.getBoosterId() == null ? null : boosterMapper.selectById(s.getBoosterId());
            rows.add(SettlementSummaryDto.Row.builder()
                    .orderId(s.getOrderId())
                    .orderNo(order == null ? null : order.getOrderNo())
                    .boosterId(s.getBoosterId())
                    .boosterName(booster == null ? null : booster.getNickname())
                    .settledAt(s.getSettledAt())
                    .type(s.getType())
                    .originalAmount(s.getOriginalAmount())
                    .payAmount(s.getPayAmount())
                    .refundAmount(s.getRefundAmount())
                    .platformRate(s.getPlatformRate())
                    .memberFlag(s.getMemberFlag())
                    .platformAmount(s.getPlatformAmount())
                    .boosterAmount(s.getBoosterAmount())
                    .build());
            original += nz(s.getOriginalAmount());
            pay += nz(s.getPayAmount());
            refund += nz(s.getRefundAmount());
            platform += nz(s.getPlatformAmount());
            boosterAmount += nz(s.getBoosterAmount());
        }
        return SettlementSummaryDto.Result.builder()
                .list(rows)
                .totals(SettlementSummaryDto.Totals.builder()
                        .orderCount(rows.size())
                        .originalAmount(original)
                        .payAmount(pay)
                        .refundAmount(refund)
                        .platformAmount(platform)
                        .boosterAmount(boosterAmount)
                        .build())
                .build();
    }

    /** 导出 CSV（UTF-8 BOM，Excel 直接打开）。columns 为空表示全部列。 */
    public byte[] exportCsv(Long boosterId, LocalDate from, LocalDate to, List<String> columns) {
        List<String> cols = (columns == null || columns.isEmpty()) ? ALL_COLUMNS : columns;
        SettlementSummaryDto.Result result = summary(boosterId, from, to);
        StringBuilder sb = new StringBuilder("\uFEFF");
        sb.append(String.join(",", cols.stream().map(AdminSettlementService::header).toList())).append('\n');
        for (SettlementSummaryDto.Row row : result.getList()) {
            List<String> cells = new ArrayList<>(cols.size());
            for (String col : cols) {
                cells.add(cell(row, col));
            }
            sb.append(String.join(",", cells.stream().map(AdminSettlementService::escape).toList())).append('\n');
        }
        SettlementSummaryDto.Totals t = result.getTotals();
        List<String> totalRow = new ArrayList<>();
        for (String col : cols) {
            switch (col) {
                case "orderNo" -> totalRow.add("合计 " + t.getOrderCount() + " 单");
                case "original" -> totalRow.add(MoneyUtil.fenToYuan(t.getOriginalAmount()));
                case "pay" -> totalRow.add(MoneyUtil.fenToYuan(t.getPayAmount()));
                case "refund" -> totalRow.add(MoneyUtil.fenToYuan(t.getRefundAmount()));
                case "platform" -> totalRow.add(MoneyUtil.fenToYuan(t.getPlatformAmount()));
                case "boosterAmount" -> totalRow.add(MoneyUtil.fenToYuan(t.getBoosterAmount()));
                default -> totalRow.add("");
            }
        }
        sb.append(String.join(",", totalRow.stream().map(AdminSettlementService::escape).toList())).append('\n');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String cell(SettlementSummaryDto.Row row, String col) {
        return switch (col) {
            case "orderNo" -> row.getOrderNo();
            case "settledAt" -> row.getSettledAt() == null ? "" : row.getSettledAt().format(TIME);
            case "booster" -> row.getBoosterName();
            case "original" -> MoneyUtil.fenToYuan(nz(row.getOriginalAmount()));
            case "pay" -> MoneyUtil.fenToYuan(nz(row.getPayAmount()));
            case "refund" -> MoneyUtil.fenToYuan(nz(row.getRefundAmount()));
            case "rate" -> row.getPlatformRate() == null ? "" : row.getPlatformRate() + "%";
            case "platform" -> MoneyUtil.fenToYuan(nz(row.getPlatformAmount()));
            case "boosterAmount" -> MoneyUtil.fenToYuan(nz(row.getBoosterAmount()));
            default -> "";
        };
    }

    private static String header(String col) {
        return switch (col) {
            case "orderNo" -> "订单号";
            case "settledAt" -> "结算时间";
            case "booster" -> "打手";
            case "original" -> "原价(元)";
            case "pay" -> "实付(元)";
            case "refund" -> "退款(元)";
            case "rate" -> "平台费率";
            case "platform" -> "平台应得(元)";
            case "boosterAmount" -> "打手应得(元)";
            default -> col;
        };
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        String v = value.replace("\"", "\"\"");
        return v.contains(",") || v.contains("\"") || v.contains("\n") ? "\"" + v + "\"" : v;
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }
}
