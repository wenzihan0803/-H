package com.pw.booster.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 打手收益流水。对应 {@code booster_earning_txn} 表（05 §3.10）。
 *
 * <p>{@code uk_biz (biz_type, biz_no)} 唯一索引是幂等保障：
 * 同一业务动作（如同一订单结算）重复执行只会写入一条流水。</p>
 *
 * <p>{@code frozenAfter} / {@code availableAfter} 记录变动后的余额快照，
 * 用于对账与打手端流水展示。</p>
 */
@Data
@TableName("booster_earning_txn")
public class BoosterEarningTxn implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId
    private Long id;

    private Long boosterId;

    /** SETTLE / WITHDRAW_FREEZE / WITHDRAW_SUCCESS / WITHDRAW_FAIL / PUNISH_FREEZE / PUNISH_UNFREEZE / ADJUST */
    private String bizType;

    /** 业务单号（订单号/提现号等） */
    private String bizNo;

    /** 变动金额（分），带符号 */
    private Long amount;

    /** 变动后冻结金额（分） */
    private Long frozenAfter;

    /** 变动后可提现金额（分） */
    private Long availableAfter;

    private Long orderId;

    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
