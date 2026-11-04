-- ============================================================================
-- V907：会员改为「充值满 2000 自动开通」，历史 PENDING 申请一次性生效
--
-- 2026-10-31 产品确认：单笔充值满 2000 元即成为会员，不再需要管理员审批。
-- 本迁移把历史待审申请标记为已通过，并补齐 member_account 会员资格（永久）。
-- ============================================================================
SET NAMES utf8mb4;

UPDATE `member_apply`
   SET `status` = 'APPROVED',
       `reviewed_at` = COALESCE(`reviewed_at`, NOW(3))
 WHERE `status` = 'PENDING';

INSERT INTO `member_account`
    (`user_id`, `level`, `qualified_recharge_id`, `is_permanent`, `approved_at`, `created_at`, `updated_at`)
SELECT ap.`user_id`, 'MEMBER', MAX(ap.`recharge_id`), 1, NOW(3), NOW(3), NOW(3)
  FROM `member_apply` ap
  LEFT JOIN `member_account` ma ON ma.`user_id` = ap.`user_id`
 WHERE ap.`status` = 'APPROVED' AND ma.`user_id` IS NULL
 GROUP BY ap.`user_id`;

UPDATE `member_account` ma
JOIN `member_apply` ap ON ap.`user_id` = ma.`user_id` AND ap.`status` = 'APPROVED'
   SET ma.`level` = 'MEMBER',
       ma.`is_permanent` = 1,
       ma.`approved_at` = COALESCE(ma.`approved_at`, NOW(3)),
       ma.`qualified_recharge_id` = COALESCE(ma.`qualified_recharge_id`, ap.`recharge_id`)
 WHERE ma.`level` <> 'MEMBER' OR ma.`is_permanent` = 0;
