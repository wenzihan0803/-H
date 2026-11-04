package com.pw.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 仲裁工单视图（客户与管理端共用）。 */
public final class ComplaintDto {

    private ComplaintDto() {
    }

    @Data
    @Builder
    @Schema(description = "仲裁工单")
    public static class View {
        private Long id;
        private Long orderId;
        private String orderNo;
        private Long payAmount;
        private String status;
        private String previousStatus;
        private String description;
        private List<String> evidence;
        private String handleNote;
        private Integer refundRate;
        private Long refundId;
        private LocalDateTime resolvedAt;
        private LocalDateTime createdAt;
    }
}
