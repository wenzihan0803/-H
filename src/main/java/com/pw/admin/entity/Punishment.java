package com.pw.admin.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 处置记录。对应 {@code punishment}。 */
@Data
@TableName("punishment")
public class Punishment {

    @TableId
    private Long id;

    /** BOOSTER / USER */
    private String targetType;

    private Long targetId;

    /** WARNING / TEMP_BAN / BAN */
    private String type;

    private String reason;

    /** JSON 字符串，存证据 objectKey 数组 */
    private String evidenceJson;

    private Long operatorId;

    private LocalDateTime startAt;

    private LocalDateTime endAt;

    /** ACTIVE / LIFTED */
    private String status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    public static final String TARGET_BOOSTER = "BOOSTER";
    public static final String TARGET_USER = "USER";

    public static final String TYPE_WARNING = "WARNING";
    public static final String TYPE_TEMP_BAN = "TEMP_BAN";
    public static final String TYPE_BAN = "BAN";

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_LIFTED = "LIFTED";
}
