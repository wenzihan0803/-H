package com.pw.im.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 会话消息。对应 {@code chat_message}（V908）。 */
@Data
@TableName("chat_message")
public class ChatMessage {

    @TableId
    private Long id;

    private Long conversationId;

    /** CUSTOMER / STAFF */
    private String senderType;

    /** 客户 user_id 或 admin_user_id */
    private Long senderId;

    /** 发送者昵称（客户昵称 / 客服账号名），仅展示用 */
    private String senderName;

    private String content;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
