-- ============================================================================
-- 来源文档：05-数据库设计.md §3「打手与收益」
-- 包含表：booster, booster_apply, booster_apply_profile, booster_callback,
--          booster_trial, booster_game_account, booster_skill,
--          booster_schedule, booster_earning_account, booster_earning_txn（共 10 张表）
-- ============================================================================
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `booster` (
  `id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `status` varchar(32) NOT NULL, -- PENDING_REVIEW/PENDING_CALL/PENDING_TRIAL/ACTIVE/TEMP_BANNED/SUSPENDED/BANNED/REJECTED
  `level` int NOT NULL DEFAULT 1,
  `service_score` decimal(3,2) NOT NULL DEFAULT 5.00,
  `complete_rate` decimal(5,2) NOT NULL DEFAULT 100.00,
  `ban_until` datetime(3) DEFAULT NULL,
  `activated_at` datetime(3) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user` (`user_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `booster_apply` (
  `id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `step` tinyint NOT NULL DEFAULT 1,
  `status` varchar(32) NOT NULL, -- DRAFT/PENDING_REVIEW/PENDING_CALL/PENDING_TRIAL/APPROVED/REJECTED
  `reject_reason` varchar(255) DEFAULT NULL,
  `submitted_at` datetime(3) DEFAULT NULL,
  `reviewed_by` bigint DEFAULT NULL,
  `reviewed_at` datetime(3) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user` (`user_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `booster_apply_profile` (
  `id` bigint NOT NULL,
  `apply_id` bigint NOT NULL,
  `real_name_cipher` varchar(255) DEFAULT NULL,
  `age` int DEFAULT NULL,
  `city` varchar(64) DEFAULT NULL,
  `contact_cipher` varchar(255) DEFAULT NULL,
  `device` varchar(255) DEFAULT NULL,
  `service_time` varchar(255) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_apply` (`apply_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `booster_callback` (
  `id` bigint NOT NULL,
  `apply_id` bigint NOT NULL,
  `phone_cipher` varchar(255) DEFAULT NULL,
  `call_status` varchar(16) NOT NULL, -- CONNECTED/MISSED/FAILED
  `duration` int DEFAULT NULL,
  `result` varchar(255) DEFAULT NULL,
  `operator_id` bigint NOT NULL,
  `called_at` datetime(3) NOT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_apply` (`apply_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `booster_trial` (
  `id` bigint NOT NULL,
  `apply_id` bigint NOT NULL,
  `result` varchar(16) NOT NULL, -- PASS/FAIL
  `remark` varchar(255) DEFAULT NULL,
  `operator_id` bigint NOT NULL,
  `finished_at` datetime(3) NOT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_apply` (`apply_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `booster_game_account` (
  `id` bigint NOT NULL,
  `booster_id` bigint NOT NULL,
  `game_id` varchar(32) NOT NULL,
  `account_id` varchar(64) NOT NULL,
  `server` varchar(64) DEFAULT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE/DISABLED
  `verified_at` datetime(3) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_account` (`booster_id`,`game_id`,`account_id`,`server`),
  KEY `idx_booster` (`booster_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `booster_skill` (
  `id` bigint NOT NULL,
  `booster_id` bigint NOT NULL,
  `category_id` bigint NOT NULL,
  `price` bigint NOT NULL,
  `schedule_json` json DEFAULT NULL,
  `enabled` tinyint NOT NULL DEFAULT 1,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_booster_category` (`booster_id`,`category_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `booster_schedule` (
  `booster_id` bigint NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'OFF', -- ONLINE/BUSY/OFF
  `slots_json` json DEFAULT NULL,
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`booster_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `booster_earning_account` (
  `booster_id` bigint NOT NULL,
  `frozen` bigint NOT NULL DEFAULT 0,
  `available` bigint NOT NULL DEFAULT 0,
  `total` bigint NOT NULL DEFAULT 0,
  `version` int NOT NULL DEFAULT 0,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`booster_id`),
  CONSTRAINT `ck_earning_frozen` CHECK (`frozen` >= 0),
  CONSTRAINT `ck_earning_available` CHECK (`available` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `booster_earning_txn` (
  `id` bigint NOT NULL,
  `booster_id` bigint NOT NULL,
  `biz_type` varchar(32) NOT NULL, -- SETTLE/WITHDRAW_FREEZE/WITHDRAW_SUCCESS/WITHDRAW_FAIL/PUNISH_FREEZE/PUNISH_UNFREEZE/ADJUST
  `biz_no` varchar(64) NOT NULL,
  `amount` bigint NOT NULL,
  `frozen_after` bigint NOT NULL,
  `available_after` bigint NOT NULL,
  `order_id` bigint DEFAULT NULL,
  `remark` varchar(255) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_biz` (`biz_type`,`biz_no`),
  KEY `idx_booster_time` (`booster_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
