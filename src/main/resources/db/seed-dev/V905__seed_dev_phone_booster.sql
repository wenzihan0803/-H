-- ============================================================================
-- dev 种子：把 15035705053（dev 微信一键登录的 mock 手机号）补成打手
--
-- 背景：该号在 dev 里已能登录老板端，但打手档案不存在，走「我是打手」入口会
--       被 40302 拦下。这里补一条 ACTIVE 打手档案 + 在线排班 + 收益账户 + 游戏账号，
--       使该号可以完整走：接单 → 交接 → 拍照 → 开始服务 → 完单。
--
-- 幂等：用户不存在才建；booster 按 user_id 唯一；排班/账户/游戏账号按 booster 判存在。
-- 仅 dev（db/seed-dev），生产不执行。
-- ============================================================================
SET NAMES utf8mb4;

-- 1) 用户不存在时补一个（openid 用固定 dev 前缀，避免与 ph_* 前缀冲突）
INSERT INTO `user` (`id`, `openid`, `nickname`, `phone_hash`, `status`, `created_at`, `updated_at`)
SELECT 3010, 'dev_openid_phone_15035705053', '玩家5053',
       SHA2('15035705053', 256), 'NORMAL', NOW(3), NOW(3)
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `user` WHERE `phone_hash` = SHA2('15035705053', 256));

-- 2) 打手档案：ACTIVE，固定 id 5004（种子打手 5001-5003 之后的空位）
INSERT INTO `booster` (`id`, `user_id`, `status`, `level`, `service_score`, `complete_rate`,
                       `activated_at`, `created_at`, `updated_at`)
SELECT 5004, u.`id`, 'ACTIVE', 1, 5.00, 100.00, NOW(3), NOW(3), NOW(3)
FROM `user` u
WHERE u.`phone_hash` = SHA2('15035705053', 256)
  AND NOT EXISTS (SELECT 1 FROM `booster` b WHERE b.`user_id` = u.`id`)
  AND NOT EXISTS (SELECT 1 FROM `booster` b WHERE b.`id` = 5004);

-- 3) 收益账户（全 0，提现可用）
INSERT INTO `booster_earning_account` (`booster_id`, `frozen`, `available`, `total`, `version`,
                                       `created_at`, `updated_at`)
SELECT b.`id`, 0, 0, 0, 0, NOW(3), NOW(3)
FROM `booster` b
JOIN `user` u ON u.`id` = b.`user_id`
WHERE u.`phone_hash` = SHA2('15035705053', 256)
  AND NOT EXISTS (SELECT 1 FROM `booster_earning_account` a WHERE a.`booster_id` = b.`id`);

-- 4) 排班 ONLINE：管理端派单候选要求在线
INSERT INTO `booster_schedule` (`booster_id`, `status`, `updated_at`)
SELECT b.`id`, 'ONLINE', NOW(3)
FROM `booster` b
JOIN `user` u ON u.`id` = b.`user_id`
WHERE u.`phone_hash` = SHA2('15035705053', 256)
  AND NOT EXISTS (SELECT 1 FROM `booster_schedule` s WHERE s.`booster_id` = b.`id`);

-- 5) 游戏账号：开始服务/申请完单必须绑定本人账号
INSERT INTO `booster_game_account` (`id`, `booster_id`, `game_id`, `account_id`, `server`, `status`, `created_at`)
SELECT 5104, b.`id`, 'delta_force', 'acct_15035705053', '微信区', 'ACTIVE', NOW(3)
FROM `booster` b
JOIN `user` u ON u.`id` = b.`user_id`
WHERE u.`phone_hash` = SHA2('15035705053', 256)
  AND NOT EXISTS (SELECT 1 FROM `booster_game_account` g WHERE g.`booster_id` = b.`id`)
  AND NOT EXISTS (SELECT 1 FROM `booster_game_account` g WHERE g.`id` = 5104);
