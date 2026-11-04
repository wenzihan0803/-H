package com.pw.withdraw.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 提现单。对应 {@code withdraw} 表（05 §5.4）。
 *
 * <p>状态流（01 §8）：
 * <pre>
 * APPLIED --管理员确认--> APPROVED --商家转账中--> PAYING --成功--> PAID
 *    |                       |                     |
 *    +--- 拒绝 ------------> REJECTED               +-- 失败 --> FAILED
 * </pre>
 * 申请时 {@code available → frozen}；成功扣 frozen；失败/拒绝 frozen 退回 available。</p>
 */
@Data
@TableName("withdraw")
public class Withdraw implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId
    private Long id;

    private String withdrawNo;

    private Long boosterId;

    /** 提现金额（分） */
    private Long amount;

    /** APPLIED/APPROVED/PAYING/PAID/REJECTED/FAILED */
    private String status;

    private Long approverId;

    private LocalDateTime approvedAt;

    private LocalDateTime paidAt;

    private String failReason;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
