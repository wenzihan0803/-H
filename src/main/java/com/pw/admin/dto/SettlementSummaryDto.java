package com.pw.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 管理端「合计」：已完成订单的抽成明细与汇总。 */
public final class SettlementSummaryDto {

    private SettlementSummaryDto() {
    }

    @Data
    @Builder
    @Schema(description = "合计明细行")
    public static class Row {
        private Long orderId;
        private String orderNo;
        private Long boosterId;
        private String boosterName;
        private LocalDateTime settledAt;
        private String type;
        private Long originalAmount;
        private Long payAmount;
        private Long refundAmount;
        private Integer platformRate;
        private Integer memberFlag;
        private Long platformAmount;
        private Long boosterAmount;
    }

    @Data
    @Builder
    @Schema(description = "合计汇总")
    public static class Totals {
        private long orderCount;
        private long originalAmount;
        private long payAmount;
        private long refundAmount;
        private long platformAmount;
        private long boosterAmount;
    }

    @Data
    @Builder
    @Schema(description = "合计结果")
    public static class Result {
        private List<Row> list;
        private Totals totals;
    }
}
