-- ============================================================================
-- 来源文档：05-数据库设计.md §6「审批、处置、管理」
-- 包含表：approval_request, approval_node, approval_rule, approval_delegate,
--          punishment, punishment_quota, punishment_appeal, admin_user,
--          role_permission, admin_log, admin_emergency_log（共 11 张表）
-- ============================================================================
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `approval_request` (
  `id` bigint NOT NULL,
  `type` varchar(32) NOT NULL, -- WITHDRAW/MEMBER/PUNISHMENT/PRICE_CHANGE
  `biz_id` bigint NOT NULL,
  `amount` bigint DEFAULT NULL,
  `applicant_id` bigint NOT NULL,
  `current_approver_id` bigint DEFAULT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'PENDING', -- PENDING/APPROVED/REJECTED/CANCELLED
  `reason` varchar(255) NOT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_type_biz` (`type`,`biz_id`),
  KEY `idx_approver_status` (`current_approver_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `approval_node` (
  `id` bigint NOT NULL,
  `request_id` bigint NOT NULL,
  `approver_id` bigint NOT NULL,
  `action` varchar(16) NOT NULL, -- APPROVE/REJECT/DELEGATE
  `remark` varchar(255) DEFAULT NULL,
  `acted_at` datetime(3) NOT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_request` (`request_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `approval_rule` (
  `id` bigint NOT NULL,
  `rule_key` varchar(64) NOT NULL,
  `rule_value` varchar(512) NOT NULL,
  `updated_by` bigint NOT NULL,
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rule` (`rule_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `approval_delegate` (
  `id` bigint NOT NULL,
  `admin_id` bigint NOT NULL,
  `delegate_id` bigint NOT NULL,
  `start_at` datetime(3) NOT NULL,
  `end_at` datetime(3) NOT NULL,
  `scope` varchar(128) NOT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_admin_time` (`admin_id`,`start_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `punishment` (
  `id` bigint NOT NULL,
  `target_type` varchar(16) NOT NULL, -- USER/BOOSTER
  `target_id` bigint NOT NULL,
  `type` varchar(16) NOT NULL, -- TEMP/LONG/PERMANENT
  `reason` varchar(255) NOT NULL,
  `evidence_json` json DEFAULT NULL,
  `operator_id` bigint NOT NULL,
  `start_at` datetime(3) NOT NULL,
  `end_at` datetime(3) DEFAULT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE/EXPIRED/REVOKED
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_target` (`target_type`,`target_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `punishment_quota` (
  `id` bigint NOT NULL,
  `role` varchar(16) NOT NULL,
  `daily_limit` int NOT NULL,
  `monthly_limit` int NOT NULL,
  `used_today` int NOT NULL DEFAULT 0,
  `used_month` int NOT NULL DEFAULT 0,
  `reset_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role` (`role`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `punishment_appeal` (
  `id` bigint NOT NULL,
  `punishment_id` bigint NOT NULL,
  `reason` varchar(255) NOT NULL,
  `evidence_json` json DEFAULT NULL,
  `result` varchar(16) DEFAULT NULL, -- UPHELD/REVOKED
  `reviewer_id` bigint DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_punishment` (`punishment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `admin_user` (
  `id` bigint NOT NULL,
  `username` varchar(64) NOT NULL,
  `password_hash` varchar(255) NOT NULL,
  `role` varchar(16) NOT NULL, -- CS/ADMIN/SUPER_ADMIN
  `status` varchar(16) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE/DISABLED
  `last_login_at` datetime(3) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `role_permission` (
  `id` bigint NOT NULL,
  `role` varchar(16) NOT NULL,
  `menu_key` varchar(64) NOT NULL,
  `action` varchar(32) NOT NULL,
  `data_scope` varchar(32) NOT NULL, -- SELF/ALL/SYSTEM
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_menu_action` (`role`,`menu_key`,`action`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `admin_log` (
  `id` bigint NOT NULL,
  `admin_id` bigint NOT NULL,
  `module` varchar(64) NOT NULL,
  `action` varchar(64) NOT NULL,
  `before_json` json DEFAULT NULL,
  `after_json` json DEFAULT NULL,
  `ip` varchar(64) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_admin_time` (`admin_id`,`created_at`),
  KEY `idx_module_time` (`module`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `admin_emergency_log` (
  `id` bigint NOT NULL,
  `admin_id` bigint NOT NULL,
  `scene` varchar(64) NOT NULL,
  `action` varchar(255) NOT NULL,
  `video_url` varchar(512) DEFAULT NULL,
  `review_status` varchar(16) NOT NULL DEFAULT 'PENDING', -- PENDING/PASSED/REJECTED
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_admin_time` (`admin_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
