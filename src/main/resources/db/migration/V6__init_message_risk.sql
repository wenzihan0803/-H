-- ============================================================================
-- 来源文档：05-数据库设计.md §7「消息、评价、工单、风控」
-- 包含表：message, review, complaint, ticket, minor_refund, risk_event,
--          idempotency_record, banner（共 8 张表）
-- 备注：ticket / minor_refund / risk_event 本期不实现，仅保留 DDL
-- ============================================================================
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `message` (
  `id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `type` varchar(32) NOT NULL,
  `title` varchar(128) NOT NULL,
  `content` varchar(1024) NOT NULL,
  `biz_type` varchar(32) DEFAULT NULL,
  `biz_id` bigint DEFAULT NULL,
  `read_at` datetime(3) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_user_read` (`user_id`,`read_at`),
  KEY `idx_biz` (`biz_type`,`biz_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `review` (
  `id` bigint NOT NULL,
  `order_id` bigint NOT NULL,
  `customer_id` bigint NOT NULL,
  `booster_id` bigint NOT NULL,
  `score` tinyint NOT NULL,
  `tags_json` json DEFAULT NULL,
  `content` varchar(500) DEFAULT NULL,
  `anonymous` tinyint NOT NULL DEFAULT 0,
  `status` varchar(16) NOT NULL DEFAULT 'VISIBLE', -- VISIBLE/HIDDEN
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order` (`order_id`),
  KEY `idx_booster_time` (`booster_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `complaint` (
  `id` bigint NOT NULL,
  `type` varchar(32) NOT NULL, -- ARBITRATION=订单售后仲裁（v1 唯一在用类型）
  `priority` varchar(16) NOT NULL DEFAULT 'NORMAL',
  `order_id` bigint DEFAULT NULL,
  `customer_id` bigint NOT NULL,
  `booster_id` bigint DEFAULT NULL,
  `description` varchar(500) NOT NULL, -- 客户文字描述（必填，仲裁只收文字）
  `handle_note` varchar(500) DEFAULT NULL, -- 客服处理结论（文字）
  `owner_id` bigint DEFAULT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'OPEN', -- OPEN/PROCESSING/RESOLVED/CLOSED
  `resolved_at` datetime(3) DEFAULT NULL,
  `sla_due` datetime(3) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_status_sla` (`status`,`sla_due`),
  KEY `idx_order` (`order_id`),
  KEY `idx_customer` (`customer_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `ticket` (
  `id` bigint NOT NULL,
  `type` varchar(32) NOT NULL,
  `priority` varchar(16) NOT NULL DEFAULT 'NORMAL',
  `order_id` bigint DEFAULT NULL,
  `owner_id` bigint DEFAULT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'OPEN',
  `sla_due` datetime(3) DEFAULT NULL,
  `resolved_at` datetime(3) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_status_sla` (`status`,`sla_due`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `minor_refund` (
  `id` bigint NOT NULL,
  `order_id` bigint DEFAULT NULL,
  `guardian_name` varchar(64) NOT NULL,
  `relation` varchar(32) NOT NULL,
  `evidence_json` json DEFAULT NULL,
  `amount` bigint NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'PENDING',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `risk_event` (
  `id` bigint NOT NULL,
  `type` varchar(32) NOT NULL,
  `user_id` bigint DEFAULT NULL,
  `order_id` bigint DEFAULT NULL,
  `level` varchar(16) NOT NULL,
  `handled` tinyint NOT NULL DEFAULT 0,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_user_time` (`user_id`,`created_at`),
  KEY `idx_handled` (`handled`,`level`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `idempotency_record` (
  `id` bigint NOT NULL,
  `scene` varchar(32) NOT NULL,
  `user_id` bigint NOT NULL,
  `idem_key` varchar(64) NOT NULL,
  `request_hash` char(64) NOT NULL,
  `response_json` json DEFAULT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'PROCESSING', -- PROCESSING/DONE
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idem` (`scene`,`user_id`,`idem_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `banner` (
  `id` bigint NOT NULL,
  `title` varchar(64) NOT NULL,
  `image_object_key` varchar(512) NOT NULL,
  `link_type` varchar(16) NOT NULL DEFAULT 'NONE', -- NONE/CATEGORY/SKU/H5
  `link_value` varchar(255) DEFAULT NULL,
  `sort` int NOT NULL DEFAULT 0,
  `status` varchar(16) NOT NULL DEFAULT 'ON', -- ON/OFF
  `start_at` datetime(3) DEFAULT NULL,
  `end_at` datetime(3) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_status_sort` (`status`,`sort`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
