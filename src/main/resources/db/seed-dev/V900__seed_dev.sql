-- ============================================================================
-- dev 环境种子数据（仅限 dev profile）
-- 来源：09-本地开发基线与Mock策略.md §2「本地运行清单」
--       05-数据库设计.md §11「迁移与初始化」
-- 说明：版本号 V900 > V6，确保在所有建表脚本之后执行
--       所有 INSERT 使用 IGNORE 保证可重复执行
--       ID 段规划：管理端 9001-9003, 类目 1001-1004, SKU 2001-2008,
--                   客户 user 3001-3002, 打手 user 4001-4003 / booster 5001-5003,
--                   banner 6001-6002, 权限 7001+, 配额 8001-8002, 规则 8101+
-- ============================================================================
SET NAMES utf8mb4;

-- --------------------------------------------------------------------------
-- 1. 管理端账号（3 个）
--    dev 口令 Dev@123456，仅限 dev profile
--    BCrypt 哈希由 htpasswd -bnBC 10 生成（$2y$ 前缀，Spring Security 兼容）
-- --------------------------------------------------------------------------
INSERT IGNORE INTO `admin_user` (`id`, `username`, `password_hash`, `role`, `status`) VALUES
(9001, 'superadmin', '$2y$10$tlwj1dgijPxFi6VH.rwMAOUgF8IQIdhpQc8afkyYJpUXZHfXw7TXe', 'SUPER_ADMIN', 'ACTIVE'),
(9002, 'admin',      '$2y$10$tlwj1dgijPxFi6VH.rwMAOUgF8IQIdhpQc8afkyYJpUXZHfXw7TXe', 'ADMIN',       'ACTIVE'),
(9003, 'cs01',       '$2y$10$tlwj1dgijPxFi6VH.rwMAOUgF8IQIdhpQc8afkyYJpUXZHfXw7TXe', 'CS',          'ACTIVE');

-- --------------------------------------------------------------------------
-- 2. 类目 + SKU（4 类目 × 2 SKU = 8 SKU）
-- --------------------------------------------------------------------------
INSERT IGNORE INTO `service_category` (`id`, `name`, `unit`, `verify_type`, `enabled`, `sort`) VALUES
(1001, '陪玩开黑', 'ROUND',  'NONE', 1, 1),
(1002, '护航带出', 'HOUR',   'NONE', 1, 2),
(1003, '战术教学', 'LESSON', 'NONE', 1, 3),
(1004, '固定车队', 'TIME',   'NONE', 1, 4);

INSERT IGNORE INTO `service_sku` (`id`, `category_id`, `name`, `unit`, `price`, `commission_rate`, `min_qty`, `max_qty`, `enabled`, `sort`) VALUES
-- 陪玩开黑
(2001, 1001, '钻石局陪玩一局',   'ROUND', 3000, 15, 1, 10, 1, 1),
(2002, 1001, '王者局陪玩一局',   'ROUND', 5000, 15, 1, 10, 1, 2),
-- 护航带出
(2003, 1002, '黄金护航1小时',    'HOUR',  4000, 15, 1, 10, 1, 1),
(2004, 1002, '钻石护航1小时',    'HOUR',  6000, 15, 1, 10, 1, 2),
-- 战术教学
(2005, 1003, '基础枪法教学一课', 'LESSON', 3500, 15, 1, 10, 1, 1),
(2006, 1003, '进阶战术教学一课', 'LESSON', 5500, 15, 1, 10, 1, 2),
-- 固定车队
(2007, 1004, '三排固定车队一次', 'TIME',  8000, 15, 1, 10, 1, 1),
(2008, 1004, '五排固定车队一次', 'TIME', 12000, 15, 1, 10, 1, 2);

-- --------------------------------------------------------------------------
-- 3. 测试客户（2 个）
-- --------------------------------------------------------------------------
INSERT IGNORE INTO `user` (`id`, `openid`, `nickname`, `status`, `verified`) VALUES
(3001, 'dev_openid_customer_01', '测试老板A', 'NORMAL', 1),
(3002, 'dev_openid_customer_02', '测试老板B', 'NORMAL', 1);

INSERT IGNORE INTO `customer_gold_account` (`user_id`, `balance`, `total_recharged`, `version`) VALUES
(3001, 100000, 100000, 0),
(3002, 100000, 100000, 0);

INSERT IGNORE INTO `member_account` (`user_id`, `level`) VALUES
(3001, 'NORMAL'),
(3002, 'NORMAL');

-- --------------------------------------------------------------------------
-- 4. 测试打手（3 个，status=ACTIVE，接单链路直接可用）
-- --------------------------------------------------------------------------
-- 4a. user 表
INSERT IGNORE INTO `user` (`id`, `openid`, `nickname`, `status`, `verified`) VALUES
(4001, 'dev_openid_booster_01', '测试打手A', 'NORMAL', 1),
(4002, 'dev_openid_booster_02', '测试打手B', 'NORMAL', 1),
(4003, 'dev_openid_booster_03', '测试打手C', 'NORMAL', 1);

-- 4b. booster 表
INSERT IGNORE INTO `booster` (`id`, `user_id`, `status`, `level`, `service_score`, `complete_rate`, `activated_at`) VALUES
(5001, 4001, 'ACTIVE', 1, 5.00, 100.00, '2026-10-01 00:00:00.000'),
(5002, 4002, 'ACTIVE', 1, 5.00, 100.00, '2026-10-01 00:00:00.000'),
(5003, 4003, 'ACTIVE', 1, 5.00, 100.00, '2026-10-01 00:00:00.000');

-- 4c. booster_earning_account
INSERT IGNORE INTO `booster_earning_account` (`booster_id`, `frozen`, `available`, `total`, `version`) VALUES
(5001, 0, 0, 0, 0),
(5002, 0, 0, 0, 0),
(5003, 0, 0, 0, 0);

-- 4d. booster_game_account（每个打手至少 1 条）
INSERT IGNORE INTO `booster_game_account` (`id`, `booster_id`, `game_id`, `account_id`, `server`, `status`) VALUES
(5101, 5001, 'delta_force', 'acct_booster_01', 'asia-east', 'ACTIVE'),
(5102, 5002, 'delta_force', 'acct_booster_02', 'asia-east', 'ACTIVE'),
(5103, 5003, 'delta_force', 'acct_booster_03', 'asia-east', 'ACTIVE');

-- 4e. booster_skill（每个打手覆盖至少 2 个类目，price 与 SKU 对应）
INSERT IGNORE INTO `booster_skill` (`id`, `booster_id`, `category_id`, `price`, `enabled`) VALUES
-- 打手A：陪玩开黑 + 护航带出
(5201, 5001, 1001, 3000, 1),
(5202, 5001, 1002, 4000, 1),
-- 打手B：陪玩开黑 + 战术教学
(5203, 5002, 1001, 5000, 1),
(5204, 5002, 1003, 3500, 1),
-- 打手C：护航带出 + 固定车队
(5205, 5003, 1002, 6000, 1),
(5206, 5003, 1004, 8000, 1);

-- 4f. booster_schedule（每人一条 ONLINE）
INSERT IGNORE INTO `booster_schedule` (`booster_id`, `status`, `slots_json`) VALUES
(5001, 'ONLINE', '[{"day":"MON","start":"09:00","end":"22:00"},{"day":"TUE","start":"09:00","end":"22:00"}]'),
(5002, 'ONLINE', '[{"day":"WED","start":"10:00","end":"23:00"},{"day":"THU","start":"10:00","end":"23:00"}]'),
(5003, 'ONLINE', '[{"day":"FRI","start":"08:00","end":"20:00"},{"day":"SAT","start":"08:00","end":"20:00"}]');

-- --------------------------------------------------------------------------
-- 5. Banner（2 条）
-- --------------------------------------------------------------------------
INSERT IGNORE INTO `banner` (`id`, `title`, `image_object_key`, `link_type`, `sort`, `status`) VALUES
(6001, '新人首单立减', 'banner/dev-01.jpg', 'NONE',     1, 'ON'),
(6002, '热门陪玩推荐', 'banner/dev-02.jpg', 'CATEGORY', 2, 'ON');

-- --------------------------------------------------------------------------
-- 6. role_permission 基础权限
--    按 README §2.3 权限矩阵：
--    CS: 发起争议退款(create refund)，不可审批、不可看流水
--    ADMIN: 发起+审批退款，可看流水
--    SUPER_ADMIN: 全部 + 管类目/SKU/Banner
-- --------------------------------------------------------------------------
INSERT IGNORE INTO `role_permission` (`id`, `role`, `menu_key`, `action`, `data_scope`) VALUES
-- CS
(7001, 'CS',          'refund',    'create', 'ALL'),
(7002, 'CS',          'complaint', 'view',   'ALL'),
(7003, 'CS',          'complaint', 'create', 'ALL'),
-- ADMIN
(7010, 'ADMIN',       'refund',    'create', 'ALL'),
(7011, 'ADMIN',       'refund',    'approve','ALL'),
(7012, 'ADMIN',       'refund',    'reject', 'ALL'),
(7013, 'ADMIN',       'ledger',    'view',   'ALL'),
(7014, 'ADMIN',       'withdraw',  'approve','ALL'),
(7015, 'ADMIN',       'withdraw',  'reject', 'ALL'),
(7016, 'ADMIN',       'complaint', 'view',   'ALL'),
(7017, 'ADMIN',       'complaint', 'create', 'ALL'),
-- SUPER_ADMIN
(7020, 'SUPER_ADMIN', 'refund',    'create', 'ALL'),
(7021, 'SUPER_ADMIN', 'refund',    'approve','ALL'),
(7022, 'SUPER_ADMIN', 'refund',    'reject', 'ALL'),
(7023, 'SUPER_ADMIN', 'ledger',    'view',   'ALL'),
(7024, 'SUPER_ADMIN', 'withdraw',  'approve','ALL'),
(7025, 'SUPER_ADMIN', 'withdraw',  'reject', 'ALL'),
(7026, 'SUPER_ADMIN', 'catalog',   'manage', 'SYSTEM'),
(7027, 'SUPER_ADMIN', 'banner',    'manage', 'SYSTEM'),
(7028, 'SUPER_ADMIN', 'complaint', 'view',   'ALL'),
(7029, 'SUPER_ADMIN', 'complaint', 'create', 'ALL');

-- --------------------------------------------------------------------------
-- 7. punishment_quota
--    客服临时封禁 3 次/日、≤24h；管理员长期封禁 10 次/月
-- --------------------------------------------------------------------------
INSERT IGNORE INTO `punishment_quota` (`id`, `role`, `daily_limit`, `monthly_limit`, `used_today`, `used_month`, `reset_at`) VALUES
(8001, 'CS',    3, 90,  0, 0, '2026-10-29 00:00:00.000'),
(8002, 'ADMIN', 10, 10, 0, 0, '2026-10-01 00:00:00.000');

-- --------------------------------------------------------------------------
-- 8. approval_rule 基础规则
-- --------------------------------------------------------------------------
INSERT IGNORE INTO `approval_rule` (`id`, `rule_key`, `rule_value`, `updated_by`) VALUES
(8101, 'WITHDRAW_APPROVE_ROLE',   'ADMIN',       9001),
(8102, 'MEMBER_UPGRADE_ROLE',     'SUPER_ADMIN', 9001),
(8103, 'LARGE_WITHDRAW_THRESHOLD','50000',        9001),
(8104, 'LARGE_WITHDRAW_ROLE',     'SUPER_ADMIN', 9001);
