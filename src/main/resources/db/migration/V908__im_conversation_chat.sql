-- ============================================================================
-- V908：客户 ↔ 客服 聊天（站内 IM v1，轮询模式）
-- conversation：一个客户一条会话（客服共享队列，任何客服/管理员均可回复）
-- chat_message：会话消息（CUSTOMER=客户 / STAFF=客服与管理端）
-- 系统通知复用 V6 已建的 message 表，本迁移不重复建。
-- ============================================================================
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `conversation` (
  `id` bigint NOT NULL,
  `customer_id` bigint NOT NULL COMMENT '客户 user_id',
  `last_message_preview` varchar(200) DEFAULT NULL COMMENT '最后一条消息摘要（列表展示）',
  `last_message_at` datetime(3) DEFAULT NULL COMMENT '最后一条消息时间',
  `customer_unread` int NOT NULL DEFAULT 0 COMMENT '客户未读数（客服发送 +1）',
  `staff_unread` int NOT NULL DEFAULT 0 COMMENT '客服端未读数（客户发送 +1）',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_customer` (`customer_id`),
  KEY `idx_last_message_at` (`last_message_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户与客服会话';

CREATE TABLE IF NOT EXISTS `chat_message` (
  `id` bigint NOT NULL,
  `conversation_id` bigint NOT NULL,
  `sender_type` varchar(16) NOT NULL COMMENT 'CUSTOMER / STAFF',
  `sender_id` bigint NOT NULL COMMENT '客户 user_id 或 admin_user_id',
  `sender_name` varchar(64) DEFAULT NULL COMMENT '发送者昵称（客户昵称 / 客服账号名）',
  `content` varchar(2000) NOT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_conv_id` (`conversation_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话消息';
