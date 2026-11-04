package com.pw.order.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 订单托管金。对应 {@code order_fund_hold} 表（05 §4.4）。
 *
 * <p>一笔订单一条托管金（{@code uk_order} 唯一）。<b>下单即扣金砖，不是冻结</b>（01 §5），
 * 所以这里的「托管」表示钱已在平台侧、等待结算或退款。</p>
 *
 * <p>不变式：{@code amount = remainingAmount + refundedAmount}，
 * 由 {@code ck_hold_amount / ck_hold_remaining / ck_hold_refunded} 三个 CHECK 约束兜底。</p>
 *
 * <p>{@code status}：{@code HELD → REFUNDED}（全额退）或 {@code HELD → PARTIAL_REFUNDED}（部分退）。</p>
 */
@Data
@TableName("order_fund_hold")
public class OrderFundHold implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId
    private Long id;

    private Long orderId;

    private Long customerId;

    /** 托管总额（分）= 订单实付 */
    private Long amount;

    /** 剩余可退/可结算金额（分） */
    private Long remainingAmount;

    /** 已退金额（分） */
    private Long refundedAmount;

    /** HELD / REFUNDED / PARTIAL_REFUNDED */
    private String status;

    /** 托管发生时间 */
    private LocalDateTime heldAt;

    private LocalDateTime refundedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
