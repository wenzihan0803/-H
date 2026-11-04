package com.pw.admin.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 首页 Banner。对应 {@code banner}。 */
@Data
@TableName("banner")
public class Banner {

    @TableId
    private Long id;

    private String title;

    private String imageObjectKey;

    /** NONE / ORDER / URL / BOOSTER */
    private String linkType;

    private String linkValue;

    private Integer sort;

    /** ON / OFF */
    private String status;

    private LocalDateTime startAt;

    private LocalDateTime endAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public static final String STATUS_ON = "ON";
    public static final String STATUS_OFF = "OFF";
}
