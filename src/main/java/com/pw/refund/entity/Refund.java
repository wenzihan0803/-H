package com.pw.refund.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 退款单。对应 {@code refund} 表（05 §5.2）。
 *
 * <p>关键设计：{@code active_flag} 是 MySQL GENERATED STORED 列，
 * 与 {@code order_id} 组成唯一索引 {@code uk_order_active}，从数据库层面保证
 * 「同一订单同时最多一条 PENDING_APPROVAL 或 SUCCESS 的退款单」。
 * 该列由数据库计算，实体侧标记为不可写。</p>
 */
@Data
@TableName("refund")
public class Refund implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId
    private Long id;

    private String refundNo;

    private Long orderId;

    private Long customerId;

    /** FULL / PARTIAL */
    private String type;

    /** 1-100 */
    private Integer refundRate;

    /** 订单实付金额（分） */
    private Long payAmount;

    /** 本次退款金额（分） */
    private Long refundAmount;

    /** 留存金额 = payAmount - refundAmount（分） */
    private Long retainedAmount;

    /** 留存金额中的平台佣金（分） */
    private Long commissionAmount;

    /** 留存金额中归属打手的部分（分） */
    private Long boosterAmount;

    /** 争议退款发生时订单的原状态，驳回时用于回滚 */
    private String previousOrderStatus;

    private String reason;

    /** 证据 objectKey 数组 JSON */
    private String evidenceJson;

    /** PENDING_APPROVAL / REJECTED / SUCCESS / FAILED */
    private String status;

    /** 数据库生成列，禁止应用写入 */
    @TableField(value = "active_flag", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Integer activeFlag;

    /** CUSTOMER / CS / ADMIN */
    private String applicantType;

    private Long applicantId;

    private Long approverId;

    private LocalDateTime approvedAt;

    /** 关联的金砖流水 ID，便于对账 */
    private Long goldTxnId;

    /** 关联的结算单 ID（部分退款时） */
    private Long settlementId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    private LocalDateTime completedAt;
}
