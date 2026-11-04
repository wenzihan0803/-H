package com.pw.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 业务规则参数。对应 {@code pw.business.*}。
 *
 * <p>唯一口径来源：技术规范/README.md（金砖与资金口径、订单状态机、退款口径、会员、提现）。</p>
 *
 * <p><b>封板约束（08 §6）</b>：金额/佣金/退款/金砖规则的变更必须走评审。
 * 这些参数做成配置只为便于测试，生产值必须与 README 一致。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "pw.business")
public class BusinessProperties {

    /** 正常完成：打手收益 = 订单原价 × 该比例（README §0.1 第 4 点） */
    private int boosterShareRate = 85;

    /** 平台费 = 订单原价 × 该比例（2026-10-31 口径：确认收货时收取） */
    private int platformCommissionRate = 15;

    /** 会员单平台费率（%）：比非会员少抽 5 个点 */
    private int memberPlatformCommissionRate = 10;

    /** 会员折扣：客户实付 = 原价 × 该比例 / 100（README §4.3，90 即 9 折） */
    private int memberDiscountRate = 90;

    /** 单笔充值满该金额（分）自动创建会员申请（README §4.2，2000 元 = 200000 分） */
    private long memberThresholdAmount = 200000L;

    /** 自动确认天数（README §3，7 天） */
    private int confirmDays = 7;

    /** 最低提现金额（分），1 元 = 100 */
    private long withdrawMinAmount = 100L;

    /** 单笔提现上限（分），5000 元 = 500000 */
    private long withdrawMaxAmount = 500000L;

    /** 每日提现次数上限 */
    private int withdrawDailyLimit = 1;

    /** 提现审核时限（小时） */
    private int withdrawReviewHours = 24;

    /** 交接 SLA（分钟） */
    private int handoverSlaMinutes = 10;

    /** 交接升级管理员（分钟） */
    private int handoverEscalateMinutes = 20;

    /** 交接超时可由打手无责退款（分钟） */
    private int handoverBoosterRefundMinutes = 30;
}
