package com.pw.common.enums;

/**
 * 订单动作枚举，与 {@link OrderStatus} 配合构成状态机。
 *
 * <p>口径来源：技术规范/README.md §1.1 状态转移表、/01 §3。</p>
 */
public enum OrderAction {

    /** 创建订单：→ PENDING_ACCEPT，扣金砖、建托管金 */
    CREATE,
    /** 打手接单：PENDING_ACCEPT → ACCEPTED，建 handover_task（10 分钟 SLA） */
    ACCEPT,
    /** 开始服务：ACCEPTED → IN_SERVICE，守卫为开局照 + 账号 ID + 交接 ACK */
    START_SERVICE,
    /** 申请完单：IN_SERVICE → PENDING_CONFIRM，守卫为两照 + 账号 ID */
    REQUEST_COMPLETE,
    /** 客户确认：PENDING_CONFIRM → COMPLETED，立即结算 85/15 */
    CONFIRM,
    /** 7 天自动确认：PENDING_CONFIRM → COMPLETED，DISPUTING 跳过 */
    AUTO_CONFIRM,
    /** 客户自助全额退：PENDING_ACCEPT/ACCEPTED → REFUNDED */
    CUSTOMER_REFUND,
    /** 客服发起争议退款：IN_SERVICE/PENDING_CONFIRM → DISPUTING */
    DISPUTE_SUBMIT,
    /** 主管通过争议退款：DISPUTING → REFUNDED（100%）或 PARTIAL_REFUNDED（1%-99%） */
    DISPUTE_APPROVE,
    /** 主管驳回争议退款：DISPUTING → 回到 previous_order_status */
    DISPUTE_REJECT,
    /** 管理员改派打手（不改主状态） */
    REASSIGN,
    /** 客户催单（不改主状态） */
    URGE,
    ;

    /** 中文名（轨迹与错误提示用） */
    public String label() {
        return switch (this) {
            case CREATE -> "创建订单";
            case ACCEPT -> "接单";
            case START_SERVICE -> "开始服务";
            case REQUEST_COMPLETE -> "申请完单";
            case CONFIRM -> "确认收货";
            case AUTO_CONFIRM -> "自动确认";
            case CUSTOMER_REFUND -> "客户退款";
            case DISPUTE_SUBMIT -> "发起仲裁";
            case DISPUTE_APPROVE -> "仲裁通过";
            case DISPUTE_REJECT -> "仲裁驳回";
            case REASSIGN -> "改派";
            case URGE -> "催单";
        };
    }
}
