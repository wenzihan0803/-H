package com.pw.im.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** 客户 ↔ 客服 聊天与站内通知的请求/响应对象。 */
public final class ImDto {

    private ImDto() {
    }

    @Data
    @Schema(description = "发送消息请求")
    public static class SendReq {
        @NotBlank(message = "请输入消息内容")
        @Size(max = 2000, message = "消息不能超过 2000 字")
        private String content;
    }

    @Data
    @Builder
    @Schema(description = "消息（Long 由 Jackson 统一序列化为字符串）")
    public static class MessageView {
        private Long id;
        private Long conversationId;
        /** CUSTOMER / STAFF */
        private String senderType;
        private String senderName;
        private String content;
        private LocalDateTime createdAt;
    }

    @Data
    @Builder
    @Schema(description = "客户我的会话")
    public static class MyConversationView {
        private Long id;
        private Integer unread;
        private String lastMessagePreview;
        private LocalDateTime lastMessageAt;
    }

    @Data
    @Builder
    @Schema(description = "消息汇总（tab 角标）")
    public static class SummaryView {
        private Long conversationId;
        private Integer chatUnread;
        private Integer notifyUnread;
        private Integer total;
    }

    @Data
    @Builder
    @Schema(description = "站内通知行")
    public static class NotifyRow {
        private Long id;
        /** ORDER / REFUND / ARBITRATION / SYSTEM */
        private String type;
        private String title;
        private String content;
        private String bizType;
        private Long bizId;
        private Boolean read;
        private LocalDateTime createdAt;
    }

    @Data
    @Builder
    @Schema(description = "管理端会话行（客服工作台左栏）")
    public static class AdminConversationRow {
        private Long id;
        private Long customerId;
        private String nickname;
        /** 手机号解密后完整展示（管理端口径：与用户列表一致） */
        private String phone;
        private String lastMessagePreview;
        private LocalDateTime lastMessageAt;
        private Integer staffUnread;
    }

    @Data
    @Builder
    @Schema(description = "管理端会话详情（聊天框头部）")
    public static class AdminConversationDetail {
        private Long id;
        private Long customerId;
        private String nickname;
        private String phone;
        private Integer staffUnread;
        /** 客户最近一单（可空） */
        private RecentOrder recentOrder;
    }

    @Data
    @Builder
    @Schema(description = "客户最近订单摘要")
    public static class RecentOrder {
        private Long id;
        private String orderNo;
        private String status;
        private Long payAmount;
        private LocalDateTime createdAt;
    }
}
