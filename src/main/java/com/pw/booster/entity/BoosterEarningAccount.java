package com.pw.booster.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 打手收益账户。对应 05 §3.9。
 *
 * <p>口径（README §0、§0.2）：</p>
 * <ul>
 *   <li>{@code available}：可提现余额。<b>确认即到账</b>，v1 起无 T+7 冻结期；</li>
 *   <li>{@code frozen}：仅两种来源——提现审核中（WITHDRAW_FREEZE）、封号（PUNISH_FREEZE）；</li>
 *   <li>{@code total}：累计收益，只增不减；</li>
 *   <li>DB CHECK：{@code frozen >= 0}、{@code available >= 0}。</li>
 * </ul>
 *
 * <p>变更必须行锁 + 流水双写（05 §1.2、01 §14）。</p>
 */
@Data
@TableName("booster_earning_account")
public class BoosterEarningAccount {

    /** 主键即 booster_id（非独立 id） */
    @TableId
    private Long boosterId;

    /** 冻结中（分）：提现审核中 + 封号冻结 */
    private Long frozen;

    /** 可提现（分） */
    private Long available;

    /** 累计收益（分） */
    private Long total;

    private Integer version;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public long getFrozenOrZero() {
        return frozen == null ? 0L : frozen;
    }

    public long getAvailableOrZero() {
        return available == null ? 0L : available;
    }

    public long getTotalOrZero() {
        return total == null ? 0L : total;
    }
}
