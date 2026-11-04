package com.pw.common.enums;

/**
 * 订单托管金状态。对应 {@code order_fund_hold.status}。
 *
 * <p>口径来源：技术规范/04 §1.5、/05 §4.4、§10、README §1 状态表「托管金」列。</p>
 *
 * <p>一笔订单一条托管金（{@code uk_order} 唯一）。退款与结算必须锁本表行，
 * 并以 {@code remaining_amount > 0} 作为条件（05 §1.2 硬约束 3）。</p>
 */
public enum HoldStatus {

    /** 托管中，remaining_amount = 实付 */
    HELD,
    /** 全额退款完成，remaining_amount = 0 */
    REFUNDED,
    /** 部分退款完成，remaining_amount = 0，refunded_amount < amount */
    PARTIAL_REFUNDED,
    ;

    public static HoldStatus ofRefundRate(int refundRate) {
        return refundRate >= 100 ? REFUNDED : PARTIAL_REFUNDED;
    }
}
