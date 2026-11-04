package com.pw.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

/** 管理端工作台：今日指标 + 待办计数 + 处置配额。 */
public final class WorkbenchDto {

    private WorkbenchDto() {
    }

    @Data
    @Builder
    @Schema(description = "今日指标")
    public static class Today {
        /** 今日创建订单的实付净额（已扣该单已退款） */
        private Long gmv;
        private Long orderCount;
        /** 完单率，两位小数字符串，如 100.00 */
        private String completeRate;
        /** 当前仲裁中订单数 */
        private Long disputeCount;
    }

    @Data
    @Builder
    @Schema(description = "待办计数")
    public static class Todo {
        private Long pendingAccept;
        private Long pendingHandover;
        private Long inService;
        private Long pendingConfirm;
        private Long refundApproval;
        private Long withdrawApproval;
        private Long boosterApply;
        private Long complaintOpen;
    }

    @Data
    @Builder
    @Schema(description = "配额项")
    public static class QuotaItem {
        private Long used;
        private Long limit;
    }

    @Data
    @Builder
    @Schema(description = "处置配额")
    public static class Quota {
        private QuotaItem csDailyPunish;
        private QuotaItem adminMonthlyPunish;
    }

    @Data
    @Builder
    @Schema(description = "工作台")
    public static class View {
        private Today today;
        private Todo todo;
        private Quota quota;
        /** 为空表示无额外提示 */
        private String notice;
    }
}
