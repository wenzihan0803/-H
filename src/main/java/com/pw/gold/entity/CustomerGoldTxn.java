package com.pw.gold.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 客户金砖流水。对应 05 §2.5。
 *
 * <p><b>只增不改</b>：这是资金审计与对账的依据（01 §17「资金操作单独审计日志，保留 ≥3 年」、
 * 05 §12「金砖流水保留 ≥3 年」）。</p>
 *
 * <p>{@code uk_biz (biz_type, biz_no)} 唯一，是防重复扣/退的<b>最终保证</b>（05 §8）。
 * 写入冲突说明同一业务动作已执行过，应回查而不是重试。</p>
 *
 * <p>{@code amount} 有符号：充值/退款为正，下单托管扣减为负（04 §2.2）。</p>
 */
@Data
@TableName("customer_gold_txn")
public class CustomerGoldTxn {

    @TableId
    private Long id;

    private Long userId;

    /** RECHARGE / ORDER_HOLD / ORDER_REFUND / ADJUST */
    private String bizType;

    /** 业务单号，与 bizType 组成唯一键 */
    private String bizNo;

    /** 有符号金额（分），扣减为负 */
    private Long amount;

    /** 本次变动后的余额（分），便于对账时逐笔核算 */
    private Long balanceAfter;

    /** 关联订单 ID，充值类为 null */
    private Long orderId;

    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
