-- ============================================================================
-- 来源文档：05-数据库设计.md §4「商品与订单」
-- 包含表：service_category, service_sku, biz_order, order_fund_hold,
--          order_terms, order_progress, order_evidence, order_timer,
--          order_reassign, handover_task, handover_log, sku_price_log（共 12 张表）
-- 修复：order_fund_hold 表 ck_hold_refunded CHECK 约束后多余逗号已去除
-- ============================================================================
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `service_category` (
  `id` bigint NOT NULL,
  `name` varchar(64) NOT NULL,
  `unit` varchar(16) NOT NULL, -- ROUND/HOUR/LESSON/TIME
  `verify_type` varchar(32) NOT NULL DEFAULT 'NONE',
  `enabled` tinyint NOT NULL DEFAULT 1,
  `sort` int NOT NULL DEFAULT 0,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_enabled_sort` (`enabled`,`sort`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `service_sku` (
  `id` bigint NOT NULL,
  `category_id` bigint NOT NULL,
  `name` varchar(64) NOT NULL,
  `unit` varchar(16) NOT NULL,
  `price` bigint NOT NULL,
  `commission_rate` int NOT NULL DEFAULT 15,
  `min_qty` int NOT NULL DEFAULT 1,
  `max_qty` int NOT NULL DEFAULT 10,
  `enabled` tinyint NOT NULL DEFAULT 1,
  `sort` int NOT NULL DEFAULT 0,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_category` (`category_id`,`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `biz_order` (
  `id` bigint NOT NULL,
  `order_no` varchar(32) NOT NULL,
  `customer_id` bigint NOT NULL,
  `booster_id` bigint DEFAULT NULL,
  `category_id` bigint NOT NULL,
  `sku_id` bigint NOT NULL,
  `mode` varchar(16) NOT NULL, -- DESIGNATED/HALL
  `quantity` int NOT NULL,
  `original_amount` bigint NOT NULL,
  `member_discount` bigint NOT NULL DEFAULT 0,
  `invite_discount` bigint NOT NULL DEFAULT 0,
  `pay_amount` bigint NOT NULL,
  `booster_amount` bigint DEFAULT NULL,
  `platform_amount` bigint DEFAULT NULL,
  `status` varchar(32) NOT NULL, -- PENDING_ACCEPT/ACCEPTED/IN_SERVICE/PENDING_CONFIRM/DISPUTING/COMPLETED/REFUNDED/PARTIAL_REFUNDED
  `handover_status` varchar(16) NOT NULL DEFAULT 'PENDING', -- PENDING/SENT/ACK/TIMEOUT
  `booster_game_account_id` bigint DEFAULT NULL,
  `boss_game_id_cipher` varchar(255) DEFAULT NULL,
  `appoint_at` datetime(3) DEFAULT NULL,
  `service_completed_at` datetime(3) DEFAULT NULL,
  `confirm_deadline` datetime(3) DEFAULT NULL,
  `confirmed_at` datetime(3) DEFAULT NULL,
  `refunded_at` datetime(3) DEFAULT NULL,
  `refund_reason` varchar(255) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  KEY `idx_customer_status` (`customer_id`,`status`),
  KEY `idx_booster_status` (`booster_id`,`status`),
  KEY `idx_status_deadline` (`status`,`confirm_deadline`),
  KEY `idx_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 修复说明：原文档 §4.4 最后一行 CONSTRAINT ck_hold_refunded CHECK (...) 后有
-- 多余逗号，紧接着 ) ENGINE=InnoDB，会导致语法错误。此处已去除该逗号。
CREATE TABLE IF NOT EXISTS `order_fund_hold` (
  `id` bigint NOT NULL,
  `order_id` bigint NOT NULL,
  `customer_id` bigint NOT NULL,
  `amount` bigint NOT NULL,
  `remaining_amount` bigint NOT NULL,
  `refunded_amount` bigint NOT NULL DEFAULT 0,
  `status` varchar(16) NOT NULL, -- HELD/REFUNDED/PARTIAL_REFUNDED
  `held_at` datetime(3) NOT NULL,
  `refunded_at` datetime(3) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order` (`order_id`),
  KEY `idx_customer` (`customer_id`,`status`),
  CONSTRAINT `ck_hold_amount` CHECK (`amount` >= 0),
  CONSTRAINT `ck_hold_remaining` CHECK (`remaining_amount` >= 0),
  CONSTRAINT `ck_hold_refunded` CHECK (`refunded_amount` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `order_terms` (
  `id` bigint NOT NULL,
  `order_id` bigint NOT NULL,
  `template_id` bigint DEFAULT NULL,
  `version` varchar(16) NOT NULL,
  `content_json` json NOT NULL,
  `confirmed_at` datetime(3) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `order_progress` (
  `id` bigint NOT NULL,
  `order_id` bigint NOT NULL,
  `from_status` varchar(32) DEFAULT NULL,
  `to_status` varchar(32) NOT NULL,
  `action` varchar(32) NOT NULL,
  `operator_type` varchar(16) NOT NULL, -- CUSTOMER/BOOSTER/CS/ADMIN/SYSTEM
  `operator_id` bigint DEFAULT NULL,
  `remark` varchar(255) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_order_time` (`order_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `order_evidence` (
  `id` bigint NOT NULL,
  `order_id` bigint NOT NULL,
  `type` varchar(16) NOT NULL, -- START/END/SUPPLEMENT
  `object_key` varchar(255) NOT NULL,
  `hash` char(64) NOT NULL,
  `size` int NOT NULL,
  `taken_at` datetime(3) DEFAULT NULL,
  `uploader_id` bigint NOT NULL,
  `supplement` tinyint NOT NULL DEFAULT 0,
  `verified_by` bigint DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_order_type` (`order_id`,`type`),
  KEY `idx_hash` (`hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `order_timer` (
  `id` bigint NOT NULL,
  `order_id` bigint NOT NULL,
  `start_at` datetime(3) NOT NULL,
  `paused_seconds` int NOT NULL DEFAULT 0,
  `heartbeat_at` datetime(3) DEFAULT NULL,
  `total_seconds` int NOT NULL DEFAULT 0,
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `order_reassign` (
  `id` bigint NOT NULL,
  `order_id` bigint NOT NULL,
  `from_booster_id` bigint DEFAULT NULL,
  `to_booster_id` bigint DEFAULT NULL,
  `reason` varchar(255) NOT NULL,
  `split_amount` bigint NOT NULL DEFAULT 0,
  `operator_id` bigint NOT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `handover_task` (
  `id` bigint NOT NULL,
  `order_id` bigint NOT NULL,
  `cs_id` bigint DEFAULT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'PENDING', -- PENDING/SENT/ACK/TIMEOUT
  `due_at` datetime(3) NOT NULL,
  `sent_at` datetime(3) DEFAULT NULL,
  `ack_at` datetime(3) DEFAULT NULL,
  `escalate_level` tinyint NOT NULL DEFAULT 0,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order` (`order_id`),
  KEY `idx_status_due` (`status`,`due_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `handover_log` (
  `id` bigint NOT NULL,
  `order_id` bigint NOT NULL,
  `task_id` bigint NOT NULL,
  `action` varchar(16) NOT NULL, -- SENT/ACK/TIMEOUT/ESCALATE
  `channel` varchar(16) DEFAULT NULL, -- IM/WECHAT/PHONE
  `boss_id_cipher` varchar(255) DEFAULT NULL,
  `guide` varchar(512) DEFAULT NULL,
  `operator_id` bigint DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_order_time` (`order_id`,`created_at`),
  KEY `idx_task` (`task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `sku_price_log` (
  `id` bigint NOT NULL,
  `sku_id` bigint NOT NULL,
  `before_amount` bigint NOT NULL,
  `after_amount` bigint NOT NULL,
  `operator_id` bigint NOT NULL,
  `reason` varchar(255) NOT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_sku_time` (`sku_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
