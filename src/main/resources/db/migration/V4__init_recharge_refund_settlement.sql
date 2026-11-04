-- ============================================================================
-- 来源文档：05-数据库设计.md §5「充值、退款、结算、提现」
-- 包含表：recharge_order, refund, settlement, withdraw（共 4 张表）
-- ============================================================================
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `recharge_order` (
  `id` bigint NOT NULL,
  `recharge_no` varchar(40) NOT NULL,
  `user_id` bigint NOT NULL,
  `amount` bigint NOT NULL,
  `purpose` varchar(16) NOT NULL DEFAULT 'NORMAL', -- NORMAL/MEMBER
  `channel` varchar(16) NOT NULL DEFAULT 'WECHAT',
  `status` varchar(16) NOT NULL DEFAULT 'PENDING', -- PENDING/SUCCESS/FAILED/CLOSED
  `prepay_id` varchar(128) DEFAULT NULL,
  `transaction_id` varchar(64) DEFAULT NULL,
  `raw_json` json DEFAULT NULL,
  `paid_at` datetime(3) DEFAULT NULL,
  `credited_at` datetime(3) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_recharge_no` (`recharge_no`),
  UNIQUE KEY `uk_transaction` (`transaction_id`),
  KEY `idx_user_status` (`user_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `refund` (
  `id` bigint NOT NULL,
  `refund_no` varchar(40) NOT NULL,
  `order_id` bigint NOT NULL,
  `customer_id` bigint NOT NULL,
  `type` varchar(16) NOT NULL, -- FULL/PARTIAL
  `refund_rate` int NOT NULL, -- 1-100
  `pay_amount` bigint NOT NULL,
  `refund_amount` bigint NOT NULL,
  `retained_amount` bigint NOT NULL,
  `commission_amount` bigint NOT NULL DEFAULT 0,
  `booster_amount` bigint NOT NULL DEFAULT 0,
  `previous_order_status` varchar(32) DEFAULT NULL,
  `reason` varchar(255) NOT NULL,
  `evidence_json` json DEFAULT NULL,
  `status` varchar(24) NOT NULL, -- PENDING_APPROVAL/REJECTED/SUCCESS/FAILED
  `active_flag` tinyint GENERATED ALWAYS AS
    (CASE WHEN `status` IN ('PENDING_APPROVAL','SUCCESS') THEN 1 ELSE NULL END) STORED,
  `applicant_type` varchar(16) NOT NULL, -- CUSTOMER/CS/ADMIN
  `applicant_id` bigint DEFAULT NULL,
  `approver_id` bigint DEFAULT NULL,
  `approved_at` datetime(3) DEFAULT NULL,
  `gold_txn_id` bigint DEFAULT NULL,
  `settlement_id` bigint DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `completed_at` datetime(3) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_refund_no` (`refund_no`),
  UNIQUE KEY `uk_order_active` (`order_id`,`active_flag`),
  KEY `idx_order_status` (`order_id`,`status`),
  KEY `idx_status_time` (`status`,`created_at`),
  CONSTRAINT `ck_refund_rate` CHECK (`refund_rate` BETWEEN 1 AND 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `settlement` (
  `id` bigint NOT NULL,
  `order_id` bigint NOT NULL,
  `booster_id` bigint NOT NULL,
  `type` varchar(24) NOT NULL DEFAULT 'NORMAL', -- NORMAL/PARTIAL_REFUND
  `original_amount` bigint NOT NULL,
  `pay_amount` bigint NOT NULL,
  `refund_amount` bigint NOT NULL DEFAULT 0,
  `settlement_base` bigint NOT NULL,
  `booster_amount` bigint NOT NULL,
  `platform_amount` bigint NOT NULL,
  `settled_at` datetime(3) NOT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order` (`order_id`),
  KEY `idx_booster` (`booster_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `withdraw` (
  `id` bigint NOT NULL,
  `withdraw_no` varchar(40) NOT NULL,
  `booster_id` bigint NOT NULL,
  `amount` bigint NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'APPLIED', -- APPLIED/APPROVED/PAYING/PAID/REJECTED/FAILED
  `approver_id` bigint DEFAULT NULL,
  `approved_at` datetime(3) DEFAULT NULL,
  `paid_at` datetime(3) DEFAULT NULL,
  `fail_reason` varchar(255) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_withdraw_no` (`withdraw_no`),
  KEY `idx_booster_status` (`booster_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
