package com.pw.common.enums;

/**
 * 客户金砖流水类型。对应 {@code customer_gold_txn.biz_type}。
 *
 * <p>口径来源：技术规范/05 §2.5、/04 §2.2 {@code GoldTxn.bizType}。</p>
 *
 * <p>{@code (biz_type, biz_no)} 唯一索引是防重复扣/退的最终保证（05 §8）。</p>
 */
public enum GoldTxnType {

    /** 充值到账（微信回调或 dev 模拟），amount 为正 */
    RECHARGE,
    /** 下单托管扣减，amount 为负 */
    ORDER_HOLD,
    /** 退款回金砖，amount 为正 */
    ORDER_REFUND,
    /** 人工调账（管理员线下特批），可正可负 */
    ADJUST,
    ;
}
