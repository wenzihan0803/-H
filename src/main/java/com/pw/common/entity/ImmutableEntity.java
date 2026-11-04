package com.pw.common.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 只有 created_at 的不可变记录基类（流水、日志、进度、证据等）。
 *
 * <p>这类表写入后不允许修改，是资金对账与审计的依据（05 §1、01 §17）。</p>
 */
@Data
public abstract class ImmutableEntity implements Serializable {

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
