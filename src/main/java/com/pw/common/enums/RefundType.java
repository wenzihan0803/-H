package com.pw.common.enums;

/**
 * 退款类型。对应 {@code refund.type}。
 *
 * <p>口径来源：技术规范/README §2、/05 §5.2。</p>
 *
 * <ul>
 *   <li>{@link #FULL}：退款比例 100%，订单终态 REFUNDED，<b>不产生结算</b>；</li>
 *   <li>{@link #PARTIAL}：退款比例 1%-99%，订单终态 PARTIAL_REFUNDED，
 *       剩余金额按 85/15 结算，打手收益直接到账。</li>
 * </ul>
 */
public enum RefundType {

    FULL,
    PARTIAL,
    ;

    /** 比例 100 为全额，1-99 为部分。 */
    public static RefundType ofRate(int refundRate) {
        return refundRate >= 100 ? FULL : PARTIAL;
    }

    /** 退款后订单应进入的终态。 */
    public static OrderStatus toOrderStatus(int refundRate) {
        return refundRate >= 100 ? OrderStatus.REFUNDED : OrderStatus.PARTIAL_REFUNDED;
    }
}
