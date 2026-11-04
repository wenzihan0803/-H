package com.pw.payment.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 充值单。对应 05 §5.1。
 *
 * <p>口径（01 §4、README §4）：<b>微信只用于客户充值金砖，订单不使用微信支付。</b></p>
 *
 * <p>幂等（05 §8）：{@code uk_recharge_no} + {@code uk_transaction} 双唯一索引，
 * 微信回调重复投递不会重复加金砖。</p>
 *
 * <p>单笔 ≥ 2,000 元自动建 {@code member_apply(PENDING)}（01 §4 第 4 步）。</p>
 */
@Data
@TableName("recharge_order")
public class RechargeOrder {

    @TableId
    private Long id;

    /** 充值单号，RC + yyyyMMddHHmmss + 8 位随机 */
    private String rechargeNo;

    private Long userId;

    /** 充值金额（分） */
    private Long amount;

    /** NORMAL / MEMBER */
    private String purpose;

    /** WECHAT */
    private String channel;

    /** PENDING / SUCCESS / FAILED / CLOSED */
    private String status;

    /** 微信预支付 ID */
    private String prepayId;

    /** 微信支付单号，唯一索引，回调幂等键 */
    private String transactionId;

    /** 微信回调原文 JSON，留档对账用 */
    private String rawJson;

    private LocalDateTime paidAt;

    /** 金砖实际到账时间 */
    private LocalDateTime creditedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public static final String PURPOSE_NORMAL = "NORMAL";
    public static final String PURPOSE_MEMBER = "MEMBER";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CLOSED = "CLOSED";

    public boolean isPending() {
        return STATUS_PENDING.equals(status);
    }

    public boolean isSuccess() {
        return STATUS_SUCCESS.equals(status);
    }
}
