package com.pw.gold.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 客户金砖账户。对应 05 §2.4。
 *
 * <p>口径（README §0）：<b>金砖只指客户预付余额，1 金砖 = 1 元</b>；数据库存分。
 * 不是冻结额度——下单时直接从 balance 扣除，转入订单托管金。</p>
 *
 * <p>硬约束（05 §1.2、§8）：</p>
 * <ul>
 *   <li>{@code balance >= 0}，金砖不允许透支（DB CHECK 兜底）；</li>
 *   <li>必须「账户 + 流水」双写，不能只存余额（00 §7 ADR#5）；</li>
 *   <li>扣减/退款必须 {@code SELECT ... FOR UPDATE} 行锁后再更新。</li>
 * </ul>
 */
@Data
@TableName("customer_gold_account")
public class CustomerGoldAccount {

    /** 主键即 user_id（05 §2.4 主键为 user_id，非独立 id） */
    @TableId
    private Long userId;

    /** 金砖余额，单位分 */
    private Long balance;

    /** 累计充值，单位分 */
    private Long totalRecharged;

    /** 乐观锁版本号 */
    private Integer version;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public long getBalanceOrZero() {
        return balance == null ? 0L : balance;
    }
}
