package com.pw.order.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单响应对象。对应 04 §2.4 {@code OrderSummary} / {@code OrderDetail}。
 *
 * <p>{@code JsonInclude.NON_NULL} 让可选字段（如未结算时的 settlement）不出现在响应里，
 * 与 04 文档中标 {@code ?} 的字段语义一致。</p>
 *
 * <p><b>金额一律整数分</b>（README §0.1），前端负责展示为元。
 * ID 与金额在序列化时转为字符串（见 JacksonConfig），避免 JS 精度丢失。</p>
 */
public final class OrderDto {

    private OrderDto() {
    }

    /** 订单摘要，用于列表页。对应 {@code OrderSummary}。 */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "订单摘要（列表页）")
    public static class Summary {

        private Long id;

        private String orderNo;

        private String status;

        private String categoryName;

        private String skuName;

        /** 计量单位：ROUND/HOUR/LESSON/TIME */
        private String unit;

        private Integer quantity;

        /** 打手信息；大厅单未接单时为 null */
        private BoosterBrief booster;

        /** 实付金额（分） */
        private Long payAmount;

        /** 当前托管余额（分） */
        private Long holdAmount;

        /** 服务开始前，客户可自助全额退 */
        private boolean canRefund;

        /** 服务开始后，可联系客服发起争议退款 */
        private boolean canRequestDispute;

        /** 可确认收货 */
        private boolean canConfirm;

        private LocalDateTime createdAt;

        private LocalDateTime appointAt;
    }

    /** 订单详情，用于详情页。对应 {@code OrderDetail}。 */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "订单详情")
    public static class Detail {

        private Long id;

        private String orderNo;

        private String status;

        private String categoryName;

        private String skuName;

        private String unit;

        /** DESIGNATED / HALL */
        private String mode;

        private Integer quantity;

        private BoosterBrief booster;

        private Amounts amounts;

        private HoldInfo hold;

        private Timestamps timestamps;

        private HandoverInfo handover;

        private List<Evidence> evidence;

        private List<Progress> progress;

        private SettlementInfo settlement;

        private List<RefundInfo> refunds;

        /** 客户备注/需求（≤200 字）。<b>打手端不返回此字段</b>（04 §3.6） */
        private String requirement;

        private boolean canRefund;

        private boolean canRequestDispute;

        private boolean canConfirm;

        private boolean canReview;

        /** 可开始服务（打手端用） */
        private boolean canStart;

        /** 可申请完单（打手端用） */
        private boolean canComplete;
    }

    /** 金额明细。对应 {@code OrderAmounts}。 */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Amounts {

        /** 下单总金额（分）= SKU 单价 × 数量 */
        private Long originalAmount;

        /** 会员折扣（分） */
        private Long memberDiscount;

        /** 实付（分）= 托管金 */
        private Long payAmount;

        /** 打手应得金额（分），结算后才有 */
        private Long boosterAmount;

        /** 平台费（分），仅在确认收货结算后才有；退款单为 0 */
        private Long platformAmount;
    }

    /** 托管金信息。对应 {@code OrderFundHold}。 */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class HoldInfo {

        private Long amount;

        private Long remainingAmount;

        private Long refundedAmount;

        /** HELD / REFUNDED / PARTIAL_REFUNDED */
        private String status;

        private LocalDateTime heldAt;

        private LocalDateTime refundedAt;
    }

    /** 订单时间戳。对应 {@code OrderTimestamps}。 */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Timestamps {

        private LocalDateTime createdAt;

        private LocalDateTime appointAt;

        private LocalDateTime serviceCompletedAt;

        /** = serviceCompletedAt + 7d */
        private LocalDateTime confirmDeadline;

        private LocalDateTime confirmedAt;

        private LocalDateTime refundedAt;
    }

    /** 交接信息。对应 {@code HandoverInfo}。 */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class HandoverInfo {

        /** PENDING / SENT / ACK / TIMEOUT */
        private String status;

        /**
         * 老板游戏 ID。
         *
         * <p>仅在 PENDING/SENT/ACK 后按权限返回（04 §2.4）：
         * 打手端在客服 SENT 之后才可见；客户端始终可见自己的 ID。</p>
         */
        private String bossGameId;

        /** 上号指引 */
        private String guide;

        private LocalDateTime sentAt;

        private LocalDateTime ackAt;

        /** 交接截止时间（客服 SLA） */
        private LocalDateTime dueAt;
    }

    /** 履约凭证。对应 {@code OrderEvidence}。 */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Evidence {

        private Long id;

        /** START / END / SUPPLEMENT */
        private String type;

        /** 访问地址（生产为 10 分钟签名 URL，dev 为本地路径） */
        private String url;

        private LocalDateTime takenAt;

        private boolean supplement;
    }

    /** 订单进度轨迹。对应 {@code OrderProgress}。 */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Progress {

        private String fromStatus;

        private String toStatus;

        private String action;

        /** CUSTOMER / BOOSTER / CS / ADMIN / SYSTEM */
        private String operatorType;

        private String remark;

        private LocalDateTime createdAt;
    }

    /** 结算信息。对应 {@code Settlement}。 */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SettlementInfo {

        private Long orderId;

        /** NORMAL / PARTIAL_REFUND */
        private String type;

        private Long originalAmount;

        private Long payAmount;

        private Long refundAmount;

        /** 正常=实付；部分退款=剩余 */
        private Long settlementBase;

        private Long boosterAmount;

        private Long platformAmount;

        /** 平台费率（%）：15 / 10 / 0 */
        private Integer platformRate;

        /** 结算时是否会员（0/1） */
        private Integer memberFlag;

        private LocalDateTime settledAt;
    }

    /** 退款信息。对应 {@code Refund}。 */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RefundInfo {

        private Long id;

        private String refundNo;

        private Long orderId;

        /** FULL / PARTIAL */
        private String type;

        /** 1-100 */
        private Integer refundRate;

        private Long payAmount;

        private Long refundAmount;

        private Long retainedAmount;

        private Long commissionAmount;

        private Long boosterAmount;

        private String previousOrderStatus;

        /** PENDING_APPROVAL / REJECTED / SUCCESS / FAILED */
        private String status;

        private String reason;

        /** CUSTOMER / CS / ADMIN */
        private String applicantType;

        private Long applicantId;

        private Long approverId;

        private LocalDateTime approvedAt;

        private LocalDateTime createdAt;

        private LocalDateTime completedAt;
    }

    /** 打手简要信息。对应 {@code OrderSummary.booster}。 */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BoosterBrief {

        private Long id;

        private String name;

        private String avatar;
    }

    /** 大厅订单（打手端抢单列表）。对应 {@code HallOrder}。 */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class HallOrder {

        private Long id;

        private String orderNo;

        private String categoryName;

        private String skuName;

        private String unit;

        private Integer quantity;

        /** 打手到手收益预估（分）= 原价 × 85% */
        private Long estimatedEarning;

        private LocalDateTime appointAt;

        private LocalDateTime createdAt;

        /** AVAILABLE（可抢）/ ASSIGNED（已指派给我）/ BOOKED（已被抢） */
        private String tab;
    }
}
