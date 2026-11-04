package com.pw.common.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 实体审计基类。对应 05 §1「每表 created_at；可变表加 updated_at」。
 *
 * <p>数据库层已有 {@code DEFAULT CURRENT_TIMESTAMP(3)} 兜底，
 * 应用层填充是为了 insert 后实体立即带时间，便于同事务内装配响应。</p>
 *
 * <p><b>禁止物理删除</b>：订单/资金表不允许 delete；仅 {@code user} 表有 {@code deleted_at} 软删除，
 * 在其自身实体上单独声明 {@code @TableLogic}。</p>
 */
@Data
public abstract class BaseEntity implements Serializable {

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
