package com.pw.settlement.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 结算单。对应 {@code settlement} 表（05 §5.3）。
 *
 * <p>{@code uk_order} 唯一索引是「结算不重复入账」的最后一道防线：
 * 同一订单无论客户确认、7 天自动确认还是并发请求，只能写入一条结算。</p>
 *
 * <p>口径（01 §7.2）：
 * <ul>
 *   <li>NORMAL：{@code settlementBase = payAmount}，打手收益 = <b>原价</b> × 85%，平台 = 实付 − 打手收益；
 *       会员/邀请折扣由平台承担。</li>
 *   <li>PARTIAL_REFUND：{@code settlementBase = retained}，打手收益 = 剩余 × 85%，平台 = 剩余 × 15%。</li>
 * </ul>
 * 全额退款（REFUNDED）<b>不产生结算单</b>。</p>
 */
@Data
@TableName("settlement")
public class Settlement implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId
    private Long id;

    private Long orderId;

    private Long boosterId;

    /** NORMAL / PARTIAL_REFUND */
    private String type;

    /** 订单原价（分） */
    private Long originalAmount;

    /** 客户实付（分） */
    private Long payAmount;

    /** 已退款金额（分）；NORMAL 为 0 */
    private Long refundAmount;

    /** 结算基数（分）：NORMAL=实付；PARTIAL_REFUND=剩余 */
    private Long settlementBase;

    /** 打手到手收益（分） */
    private Long boosterAmount;

    /** 平台收入（分） */
    private Long platformAmount;

    /** 平台费率（%）：15 非会员 / 10 会员 / 0 未确认退款 */
    private Integer platformRate;

    /** 结算时是否会员（0/1），用于事后对账 */
    private Integer memberFlag;

    private LocalDateTime settledAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
