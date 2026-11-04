package com.pw.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 订单相关请求体集合。对应 04 §2.4/§3.4/§3.6 的请求结构。
 */
public final class OrderReq {

    private OrderReq() {
    }

    /** 退款申请（客户自助全额退）。对应 {@code RefundReq}。 */
    @Data
    @Schema(description = "退款申请")
    public static class RefundReq {

        @NotBlank(message = "请填写退款原因")
        @Size(max = 200, message = "退款原因不能超过 200 字")
        @Schema(description = "退款原因，必填", example = "临时有事，不打了")
        private String reason;
    }

    /** 争议退款申请（客服发起）。对应 {@code DisputeRefundReq}。 */
    @Data
    @Schema(description = "争议退款申请")
    public static class DisputeRefundReq {

        @NotNull(message = "请指定退款比例")
        @jakarta.validation.constraints.Min(value = 1, message = "退款比例最低 1%")
        @jakarta.validation.constraints.Max(value = 100, message = "退款比例最高 100%")
        @Schema(description = "退款比例 1-100；100=全额", example = "50")
        private Integer refundRate;

        @NotBlank(message = "请填写退款原因")
        @Size(max = 200, message = "退款原因不能超过 200 字")
        @Schema(description = "退款原因，必填", example = "打手只完成一半，协商退一半")
        private String reason;

        @Size(max = 6, message = "证据最多 6 张")
        @Schema(description = "证据 objectKey，最多 6 张")
        private java.util.List<String> evidenceKeys;
    }

    /** 驳回退款。 */
    @Data
    @Schema(description = "驳回退款请求")
    public static class RejectRefundReq {

        @NotBlank(message = "请填写驳回理由")
        @Size(max = 200, message = "驳回理由不能超过 200 字")
        @Schema(description = "驳回理由，必填", example = "证据不足，请补充")
        private String reason;
    }

    /** 打手接单后拒单。 */
    @Data
    @Schema(description = "打手拒单请求")
    public static class RejectOrderReq {

        @NotBlank(message = "请填写拒单理由")
        @Size(max = 200, message = "拒单理由不能超过 200 字")
        @Schema(description = "拒单理由，必填", example = "临时有事无法按时开始")
        private String reason;
    }

    /** 打手开始服务。对应 04 §3.6 {@code POST /booster/orders/{id}/start}。 */
    @Data
    @Schema(description = "开始服务请求")
    public static class StartServiceReq {

        @NotNull(message = "请选择游戏账号")
        @Schema(description = "打手游戏账号 ID", example = "7001")
        private Long gameAccountId;

        @NotNull(message = "请上传开局照")
        @Schema(description = "开局照凭证 ID", example = "8001")
        private Long startEvidenceId;
    }

    /** 打手申请完单。对应 04 §3.6 {@code POST /booster/orders/{id}/complete}。 */
    @Data
    @Schema(description = "申请完单请求")
    public static class CompleteOrderReq {

        @NotNull(message = "请上传结束照")
        @Schema(description = "结束照凭证 ID", example = "8002")
        private Long endEvidenceId;
    }

    /** 打手上传履约凭证。对应 04 §3.6 {@code POST /booster/orders/{id}/evidence}。 */
    @Data
    @Schema(description = "上传履约凭证请求")
    public static class EvidenceReq {

        @NotBlank(message = "请指定凭证类型")
        @Schema(description = "START=开局照，END=结束照，SUPPLEMENT=补充", example = "START")
        private String type;

        @NotBlank(message = "请提供文件标识")
        @Size(max = 255, message = "文件标识过长")
        @Schema(description = "文件 objectKey", example = "evidence/2026/10/29/xxx.jpg")
        private String objectKey;

        @Schema(description = "拍摄时间，缺省用当前时间")
        private java.time.LocalDateTime takenAt;
    }

    /** 客服下发交接信息。对应 04 §3.7 {@code POST /admin/orders/{id}/handover}。 */
    @Data
    @Schema(description = "客服交接请求")
    public static class HandoverReq {

        @NotBlank(message = "请选择交接渠道")
        @Schema(description = "IM / WECHAT / PHONE", example = "IM")
        private String channel;

        @NotBlank(message = "请填写老板游戏 ID")
        @Size(max = 64, message = "老板游戏 ID 过长")
        @Schema(description = "老板游戏 ID，加密后写入交接日志", example = "12345678")
        private String bossGameId;

        @NotBlank(message = "请填写上号指引")
        @Size(max = 512, message = "上号指引不能超过 512 字")
        @Schema(description = "上号指引，打手端可见", example = "搜索 ID 添加好友，邀请进房间")
        private String guide;

        @Size(max = 200, message = "备注不能超过 200 字")
        @Schema(description = "内部备注，打手端不可见", example = "老板在线")
        private String remark;
    }

    /** 管理端指派/改派打手。对应 04 §3.7。 */
    @Data
    @Schema(description = "指派打手请求")
    public static class AssignReq {

        @NotNull(message = "请指定打手")
        @Schema(description = "打手 ID", example = "5002")
        private Long boosterId;

        @Size(max = 200, message = "改派理由不能超过 200 字")
        @Schema(description = "改派理由（改派时必填）", example = "原打手临时有事")
        private String reason;
    }

    /** 提现申请。对应 04 §3.6 {@code POST /booster/withdrawals}。 */
    @Data
    @Schema(description = "提现申请")
    public static class WithdrawReq {

        @NotNull(message = "请填写提现金额")
        @jakarta.validation.constraints.Min(value = 100, message = "最低提现 1 元")
        @Schema(description = "提现金额（分），≥100", example = "10000")
        private Long amount;
    }

    /** 提现拒绝。 */
    @Data
    @Schema(description = "拒绝提现请求")
    public static class RejectWithdrawReq {

        @NotBlank(message = "请填写拒绝理由")
        @Size(max = 200, message = "拒绝理由不能超过 200 字")
        @Schema(description = "拒绝理由，必填", example = "实名信息不一致")
        private String reason;
    }

    /** 客户申请仲裁。 */
    @Data
    @Schema(description = "申请仲裁")
    public static class ArbitrationReq {

        @NotBlank(message = "请填写仲裁原因")
        @Size(max = 500, message = "原因不能超过 500 字")
        @Schema(description = "仲裁原因", example = "打手未按约定完成服务")
        private String reason;

        @Size(max = 9, message = "最多上传 9 张凭证")
        @Schema(description = "凭证 objectKey 列表")
        private java.util.List<String> evidenceKeys;
    }

    /** 管理员处理仲裁。 */
    @Data
    @Schema(description = "处理仲裁")
    public static class ResolveArbitrationReq {

        @NotNull(message = "请填写扣款比例")
        @jakarta.validation.constraints.Min(value = 0, message = "比例不能小于 0")
        @jakarta.validation.constraints.Max(value = 100, message = "比例不能大于 100")
        @Schema(description = "扣款比例 0–100；0 表示驳回", example = "30")
        private Integer refundRate;

        @Size(max = 200, message = "处理说明不能超过 200 字")
        @Schema(description = "处理说明")
        private String note;
    }

    /** 审批前修改待审退款的比例（仅管理员/超管）。 */
    @Data
    @Schema(description = "修改退款比例")
    public static class UpdateRefundRateReq {

        @NotNull(message = "请填写退款比例")
        @jakarta.validation.constraints.Min(value = 1, message = "比例不能小于 1")
        @jakarta.validation.constraints.Max(value = 100, message = "比例不能大于 100")
        @Schema(description = "退款比例 1–100", example = "30")
        private Integer refundRate;
    }
}
