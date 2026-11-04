package com.pw.common.enums;

/**
 * 退款单状态。对应 {@code refund.status}。
 *
 * <p>口径来源：技术规范/04 §1.5、/05 §5.2、§10。</p>
 *
 * <p>{@code PENDING_APPROVAL} 与 {@code SUCCESS} 在表里由 {@code active_flag} 生成列 +
 * 唯一索引 {@code uk_order_active (order_id, active_flag)} 保证：
 * 同一订单同时只能有一条待审或成功的退款单；驳回后可再申请（05 §8）。</p>
 */
public enum RefundStatus {

    /** 客服已提交，等待主管审批；订单处于 DISPUTING */
    PENDING_APPROVAL,
    /** 主管驳回；订单回到 previous_order_status */
    REJECTED,
    /** 退款已执行，金砖已退回；订单进入 REFUNDED 或 PARTIAL_REFUNDED */
    SUCCESS,
    /** 执行失败 */
    FAILED,
    ;

    /** 是否占用「活跃退款」唯一槽位。 */
    public boolean isActive() {
        return this == PENDING_APPROVAL || this == SUCCESS;
    }
}
