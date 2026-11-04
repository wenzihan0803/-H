package com.pw.im.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 站内通知（V6 已建 {@code message} 表，此处补齐映射）。退款/仲裁/订单关键事件写入。 */
@Data
@TableName("message")
public class SiteMessage {

    @TableId
    private Long id;

    private Long userId;

    /** 通知分类：ORDER / REFUND / ARBITRATION / SYSTEM */
    private String type;

    private String title;

    private String content;

    private String bizType;

    private Long bizId;

    /** 已读时间（null=未读，标记已读时显式赋值，禁止 INSERT 自动填充） */
    private LocalDateTime readAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    public static final String TYPE_ORDER = "ORDER";
    public static final String TYPE_REFUND = "REFUND";
    public static final String TYPE_ARBITRATION = "ARBITRATION";
    public static final String TYPE_SYSTEM = "SYSTEM";
}
