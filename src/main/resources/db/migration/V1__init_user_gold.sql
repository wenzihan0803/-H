-- ============================================================================
-- 来源文档：05-数据库设计.md §2「用户、金砖、会员」
-- 包含表：user, user_verify, user_game_profile, customer_gold_account,
--          customer_gold_txn, member_account, member_apply,
--          invite_relation, invite_reward（共 9 张表）
-- ============================================================================
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `user` (
  `id` bigint NOT NULL,
  `openid` varchar(64) NOT NULL,
  `unionid` varchar(64) DEFAULT NULL,
  `phone_cipher` varchar(255) DEFAULT NULL,
  `phone_hash` char(64) DEFAULT NULL,
  `nickname` varchar(64) DEFAULT NULL,
  `avatar` varchar(512) DEFAULT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'NORMAL', -- NORMAL/FROZEN
  `verified` tinyint NOT NULL DEFAULT 0,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `deleted_at` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_openid` (`openid`),
  KEY `idx_unionid` (`unionid`),
  KEY `idx_phone_hash` (`phone_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `user_verify` (
  `id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `real_name_cipher` varchar(255) NOT NULL,
  `id_card_cipher` varchar(255) NOT NULL,
  `id_card_hash` char(64) NOT NULL,
  `age` int DEFAULT NULL,
  `face_result` varchar(32) DEFAULT NULL, -- PASS/FAIL/SKIP
  `status` varchar(16) NOT NULL DEFAULT 'PENDING', -- PENDING/PASSED/REJECTED
  `verified_at` datetime(3) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user` (`user_id`),
  KEY `idx_id_card_hash` (`id_card_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `user_game_profile` (
  `id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `game_id` varchar(32) NOT NULL,
  `account_id` varchar(64) NOT NULL,
  `server` varchar(64) DEFAULT NULL,
  `nickname` varchar(64) DEFAULT NULL,
  `is_default` tinyint NOT NULL DEFAULT 0,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_account` (`user_id`,`game_id`,`account_id`,`server`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `customer_gold_account` (
  `user_id` bigint NOT NULL,
  `balance` bigint NOT NULL DEFAULT 0,          -- 金砖余额，分
  `total_recharged` bigint NOT NULL DEFAULT 0,  -- 累计充值，分
  `version` int NOT NULL DEFAULT 0,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`user_id`),
  CONSTRAINT `ck_gold_balance` CHECK (`balance` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `customer_gold_txn` (
  `id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `biz_type` varchar(32) NOT NULL, -- RECHARGE/ORDER_HOLD/ORDER_REFUND/ADJUST
  `biz_no` varchar(64) NOT NULL,
  `amount` bigint NOT NULL,        -- 有符号；扣减为负
  `balance_after` bigint NOT NULL,
  `order_id` bigint DEFAULT NULL,
  `remark` varchar(255) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_biz` (`biz_type`,`biz_no`),
  KEY `idx_user_time` (`user_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `member_account` (
  `user_id` bigint NOT NULL,
  `level` varchar(16) NOT NULL DEFAULT 'NORMAL', -- NORMAL/MEMBER
  `qualified_recharge_id` bigint DEFAULT NULL,
  `is_permanent` tinyint NOT NULL DEFAULT 0,
  `approved_by` bigint DEFAULT NULL,
  `approved_at` datetime(3) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `member_apply` (
  `id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `recharge_id` bigint NOT NULL,
  `source` varchar(16) NOT NULL, -- SELF/CS
  `status` varchar(16) NOT NULL DEFAULT 'PENDING', -- PENDING/APPROVED/REJECTED
  `reject_reason` varchar(255) DEFAULT NULL,
  `reviewed_by` bigint DEFAULT NULL,
  `reviewed_at` datetime(3) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_recharge` (`recharge_id`),
  KEY `idx_user_status` (`user_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `invite_relation` (
  `id` bigint NOT NULL,
  `inviter_id` bigint NOT NULL,
  `invitee_id` bigint NOT NULL,
  `code` varchar(16) NOT NULL,
  `bound_at` datetime(3) NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'BOUND', -- BOUND/QUALIFIED/INVALID
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_invitee` (`invitee_id`),
  KEY `idx_inviter` (`inviter_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `invite_reward` (
  `id` bigint NOT NULL,
  `inviter_id` bigint NOT NULL,
  `invitee_id` bigint NOT NULL,
  `amount` bigint NOT NULL DEFAULT 500,
  `status` varchar(16) NOT NULL DEFAULT 'PENDING', -- PENDING/AVAILABLE/USED/EXPIRED/REVOKED
  `expire_at` datetime(3) NOT NULL,
  `used_order_id` bigint DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_invitee` (`invitee_id`),
  KEY `idx_inviter_status` (`inviter_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
