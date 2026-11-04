package com.pw.common.enums;

/**
 * 打手收益流水类型。对应 {@code booster_earning_txn.biz_type}。
 *
 * <p>口径来源：技术规范/05 §3.10、/04 §2.5 {@code EarningTxn.bizType}、/01 §7.3。</p>
 *
 * <p>v1 起<b>无 T+7 解冻</b>：确认即到账 available，冻结只发生在提现审核与封号两种场景。</p>
 *
 * <p>{@code (biz_type, biz_no)} 唯一索引防重复结算/提现（05 §8）。</p>
 */
public enum EarningTxnType {

    /** 结算入账：available += amount, total += amount */
    SETTLE,
    /** 提现冻结：available -= amount, frozen += amount */
    WITHDRAW_FREEZE,
    /** 提现成功：frozen -= amount，钱离开平台 */
    WITHDRAW_SUCCESS,
    /** 提现失败/拒绝：frozen -= amount, available += amount（退回） */
    WITHDRAW_FAIL,
    /** 封号冻结：available 全部转 frozen */
    PUNISH_FREEZE,
    /** 解封：封号冻结部分从 frozen 退回 available */
    PUNISH_UNFREEZE,
    /** 人工调账 */
    ADJUST,
    ;
}
