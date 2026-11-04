package com.pw.admin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端响应对象。对应 04 §2.6 {@code AdminOrder} / {@code AdminRefund} 及提现管理视图。
 */
public final class AdminDto {

    private AdminDto() {
    }

    /** 管理端退款申请视图。对应 {@code AdminRefund}。 */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "管理端退款申请")
    public static class AdminRefund {

        private Long id;

        private String refundNo;

        private Long orderId;

        private String orderNo;

        private Long customerId;

        private String customerNickname;

        /** FULL / PARTIAL */
        private String type;

        private Integer refundRate;

        private Long payAmount;

        private Long refundAmount;

        private Long retainedAmount;

        private Long commissionAmount;

        private Long boosterAmount;

        /** 争议发生时订单的原状态，驳回时用于回滚（04 §2.4 Refund.previousOrderStatus） */
        private String previousOrderStatus;

        /** PENDING_APPROVAL / REJECTED / SUCCESS / FAILED */
        private String status;

        private String reason;

        /** CUSTOMER / CS / ADMIN */
        private String applicantType;

        private Long applicantId;

        private Long approverId;

        private LocalDateTime createdAt;

        /** 当前登录者能否审批此单（客服=false，管理员/超管=true，且非本人发起） */
        private boolean canApprove;
    }

    /** 管理端提现视图。对应 04 §3.7 提现管理。 */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "管理端提现单")
    public static class AdminWithdraw {

        private Long id;

        private String withdrawNo;

        private Long boosterId;

        private String boosterName;

        private Long amount;

        /** APPLIED/APPROVED/PAYING/PAID/REJECTED/FAILED */
        private String status;

        private LocalDateTime createdAt;

        private LocalDateTime approvedAt;

        private LocalDateTime paidAt;

        private String failReason;
    }

    /**
     * 管理端派单候选打手。
     *
     * <p>只返回「能不能派」所需的最小字段：是否在线、手里有没有进行中的单。
     * 前端据此置灰不可派的打手，避免选完才被后端拒。</p>
     */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "派单候选打手")
    public static class AdminBooster {

        private Long id;

        private String name;

        /** 打手专用昵称（优先于 name） */
        private String nickname;

        /** 解密后的完整手机号；老种子账号为空 */
        private String phone;

        private Integer age;

        /** MALE / FEMALE */
        private String gender;

        /** ACTIVE / DISABLED / PENDING 等 */
        private String status;

        /** 进行中订单数（ACCEPTED/IN_SERVICE/PENDING_CONFIRM/DISPUTING） */
        private Long activeOrders;

        /** 历史完成单数（COMPLETED / PARTIAL_REFUNDED） */
        private Long completedOrders;

        /** 累计到手收益（分） */
        private Long totalEarnings;

        /** 服务分 */
        private java.math.BigDecimal score;

        private LocalDateTime activatedAt;

        /** 封禁截止时间，未封禁为空 */
        private LocalDateTime banUntil;

        /** 当前是否可被派单：ACTIVE + 未封禁 + 无进行中单（本期无排班） */
        private Boolean assignable;
    }

    /** 订单流水页（对账用）。对应 04 §2.4 {@code OrderLedger}。 */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "订单完整流水")
    public static class OrderLedger {

        /** 订单详情（管理端视图） */
        private com.pw.order.dto.OrderDto.Detail order;

        /** 状态变更轨迹 */
        private List<com.pw.order.dto.OrderDto.Progress> progress;

        /** 退款申请历史 */
        private List<AdminRefund> refunds;

        /** 结算单 */
        private com.pw.order.dto.OrderDto.SettlementInfo settlement;

        /** 客户金砖流水 */
        private List<GoldTxn> goldTxns;

        /** 打手收益流水 */
        private List<EarningTxn> earningTxns;

        /** 交接操作日志 */
        private List<HandoverLog> handoverLogs;
    }

    /** 客户金砖流水条目。对应 04 §2.2 {@code GoldTxn}。 */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class GoldTxn {

        private Long id;

        /** RECHARGE / ORDER_HOLD / ORDER_REFUND / ADJUST */
        private String bizType;

        private String bizNo;

        /** 变动金额（分），带符号 */
        private Long amount;

        private Long balanceAfter;

        private String remark;

        private LocalDateTime createdAt;
    }

    /** 打手收益流水条目（流水页用）。对应 04 §2.5 {@code EarningTxn}。 */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class EarningTxn {

        private Long id;

        private String bizType;

        private String bizNo;

        private Long amount;

        private Long frozenAfter;

        private Long availableAfter;

        private String remark;

        private LocalDateTime createdAt;
    }

    /** 交接操作日志条目。对应 04 §2.4 {@code OrderLedger.handoverLogs}。 */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class HandoverLog {

        /** SENT / ACK / TIMEOUT / ESCALATE */
        private String action;

        private String channel;

        private Long operatorId;

        private LocalDateTime createdAt;
    }
}
