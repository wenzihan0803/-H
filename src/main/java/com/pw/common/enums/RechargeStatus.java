package com.pw.common.enums;

/**
 * 充值单状态。对应 {@code recharge_order.status}。
 *
 * <p>口径来源：技术规范/04 §1.5、/05 §5.1、/01 §4。</p>
 *
 * <p>幂等键：{@code recharge_no} + {@code transaction_id} 唯一（05 §8）。
 * 已 SUCCESS 的回调直接返回成功，不重复加金砖。</p>
 */
public enum RechargeStatus {

    /** 已创建，等待支付 */
    PENDING,
    /** 支付成功且金砖已到账 */
    SUCCESS,
    /** 支付失败 */
    FAILED,
    /** 已关闭（超时未支付） */
    CLOSED,
    ;

    public boolean isTerminal() {
        return this != PENDING;
    }
}
