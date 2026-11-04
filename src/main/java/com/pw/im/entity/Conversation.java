package com.pw.im.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 客户与客服会话。对应 {@code conversation}（V908）。一个客户一条会话，客服共享队列。 */
@Data
@TableName("conversation")
public class Conversation {

    @TableId
    private Long id;

    private Long customerId;

    /** 最后一条消息摘要（列表展示，超长截断） */
    private String lastMessagePreview;

    private LocalDateTime lastMessageAt;

    /** 客户未读数（客服发送 +1，客户拉取消息时清零） */
    private Integer customerUnread;

    /** 客服端未读数（客户发送 +1，客服拉取消息时清零） */
    private Integer staffUnread;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public static final String SENDER_CUSTOMER = "CUSTOMER";
    public static final String SENDER_STAFF = "STAFF";
}
