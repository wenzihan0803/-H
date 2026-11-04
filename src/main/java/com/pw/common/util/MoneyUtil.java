package com.pw.common.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 金额工具。全链路整数分，禁止 float/double。
 *
 * <p>口径来源：技术规范/00 §7 ADR#4、/01 §1「金额统一 MoneyUtil，全部 long 分」、
 * /README §0「1 金砖 = 1 元」。</p>
 *
 * <p>关键换算（README §2.2）：</p>
 * <ul>
 *   <li>正常完成：打手收益 = 订单原价 × 85%（向下取整）；平台 = 实付 − 打手收益；</li>
 *   <li>争议部分退款：退款 = 实付 × 比例 / 100（向下取整）；剩余 = 实付 − 退款；
 *       平台佣金 = 剩余 × 15%（向下取整）；打手收益 = 剩余 − 佣金。</li>
 * </ul>
 *
 * <p>所有除法一律 {@link RoundingMode#DOWN}（向下取整），保证平台不少收、客户不多扣，
 * 且打手收益 + 平台佣金严格等于结算基数，不产生 1 分误差。</p>
 */
public final class MoneyUtil {

    private MoneyUtil() {
    }

    /** 元 → 分。用于配置、外部输入。 */
    public static long yuanToFen(BigDecimal yuan) {
        if (yuan == null) {
            return 0L;
        }
        return yuan.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.DOWN).longValueExact();
    }

    /** 分 → 元字符串，保留两位小数。仅用于展示，不参与计算。 */
    public static String fenToYuan(long fen) {
        return BigDecimal.valueOf(fen).divide(BigDecimal.valueOf(100), 2, RoundingMode.DOWN).toPlainString();
    }

    /**
     * 按比例计算金额，向下取整。
     *
     * @param amount     基数（分）
     * @param percentage 百分比整数，如 85 表示 85%
     * @return 结果（分）
     */
    public static long percent(long amount, int percentage) {
        if (amount <= 0 || percentage <= 0) {
            return 0L;
        }
        return BigDecimal.valueOf(amount)
                .multiply(BigDecimal.valueOf(percentage))
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN)
                .longValueExact();
    }

    /**
     * 正常完成结算：打手收益 = 订单原价 × boosterShareRate%（默认 85%）。
     *
     * <p>注意基数是<b>原价</b>而非实付——会员/邀请折扣由平台承担（README §4.4）。</p>
     */
    public static long boosterShareOfOriginal(long originalAmount, int boosterShareRate) {
        return percent(originalAmount, boosterShareRate);
    }

    /**
     * 争议退款：退款金额 = 实付 × refundRate / 100，向下取整。
     *
     * @param payAmount 实付（分）
     * @param rate      退款比例 1–100
     */
    public static long refundAmount(long payAmount, int rate) {
        if (rate >= 100) {
            return payAmount;
        }
        return percent(payAmount, rate);
    }

    /** 争议退款：剩余金额 = 实付 − 退款金额。 */
    public static long retained(long payAmount, long refundAmount) {
        return Math.max(0L, payAmount - refundAmount);
    }

    /**
     * [已停用 2026-10-29] 平台费口径变更：未确认收货不扣平台费，争议部分退款
     * 的剩余金额全归打手（见 SettlementService#settlePartialRefund）。
     * 方法保留仅供历史对账脚本参考，业务代码不得再调用。
     */
    @Deprecated
    public static long commissionOfRetained(long retainedAmount, int commissionRate) {
        return percent(retainedAmount, commissionRate);
    }

    /** 校验金额非负。资金入口统一调用。 */
    public static void requireNonNegative(long amount, String field) {
        if (amount < 0) {
            throw new IllegalArgumentException(field + " 不能为负数: " + amount);
        }
    }

    /** 校验金额为正。 */
    public static void requirePositive(long amount, String field) {
        if (amount <= 0) {
            throw new IllegalArgumentException(field + " 必须大于 0: " + amount);
        }
    }
}
