package com.pw.admin.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 处罚申诉。对应 {@code punishment_appeal}。本期只读。 */
@Data
@TableName("punishment_appeal")
public class PunishmentAppeal {

    @TableId
    private Long id;

    private Long punishmentId;

    private String reason;

    private String evidenceJson;

    /** PENDING / APPROVED / REJECTED */
    private String result;

    private Long reviewerId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
