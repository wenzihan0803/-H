-- ============================================================================
-- dev 环境业务数据复位脚本（可重复执行）
--
-- 用途：端到端验证脚本涉及「每日仅 1 次提现」「同一订单只能一条活跃退款」等
--       有状态限制，重跑前需把业务数据清回种子初始状态。
--
-- 做法：只 DELETE 业务流水数据 + 把资金账户余额复位到种子值，
--       **不删表、不重跑 Flyway、不动种子主数据**（用户/打手/类目/SKU/管理端账号）。
--       相比 DROP DATABASE，这样不会丢失表结构，也不会触发迁移历史冲突。
--
-- 执行：
--   /opt/homebrew/opt/mysql@8.4/bin/mysql -u root -proot pw_dev < scripts/dev_reset_data.sql
--
-- 注意：仅限 dev profile 的本地开发库，切勿对生产库执行。
-- ============================================================================
SET NAMES utf8mb4;

-- ---- 1. 清理订单相关全部业务数据 ----
DELETE FROM order_progress;
DELETE FROM order_evidence;
DELETE FROM order_timer;
DELETE FROM order_terms;
DELETE FROM handover_log;
DELETE FROM handover_task;
DELETE FROM order_reassign;
DELETE FROM order_fund_hold;
DELETE FROM biz_order;

-- ---- 2. 清理资金单据与流水 ----
DELETE FROM refund;
DELETE FROM settlement;
DELETE FROM withdraw;
DELETE FROM recharge_order;
DELETE FROM customer_gold_txn;
DELETE FROM booster_earning_txn;

-- ---- 3. 清理幂等记录，使同一 Idempotency-Key 可再次使用 ----
DELETE FROM idempotency_record;

-- ---- 4. 资金账户复位到种子初始值（客户各 100000 分，打手全 0）----
-- 注意：customer_gold_account 实际只有 user_id/balance/total_recharged/version 四列
-- （05 §2.4），没有 total_consumed 与 frozen 列，不要写入不存在的字段。
UPDATE customer_gold_account
   SET balance = 100000,
       total_recharged = 100000,
       version = 0,
       updated_at = NOW(3);

UPDATE booster_earning_account
   SET frozen = 0,
       available = 0,
       total = 0,
       version = 0,
       updated_at = NOW(3);

-- ---- 5. 打手状态复位（验证过程可能改过排班/评分）----
UPDATE booster
   SET status = 'ACTIVE',
       ban_until = NULL,
       service_score = 5.00,
       complete_rate = 100.00,
       updated_at = NOW(3);

UPDATE booster_schedule
   SET status = 'ONLINE',
       updated_at = NOW(3);

UPDATE booster_game_account
   SET status = 'ACTIVE';

-- ---- 6. 会员状态复位（充值满 2000 元会触发会员申请）----
UPDATE member_account
   SET level = 'NORMAL',
       qualified_recharge_id = NULL,
       approved_by = NULL,
       approved_at = NULL,
       updated_at = NOW(3);

DELETE FROM member_apply;

-- ---- 7. 管理端登录时间复位（便于确认种子账号未被改动）----
UPDATE admin_user SET last_login_at = NULL;

-- ---- 8. 复位结果自检 ----
SELECT '--- 复位后业务数据量（应全为 0）---' AS info;
SELECT 'biz_order' AS tbl, COUNT(*) AS cnt FROM biz_order
UNION ALL SELECT 'refund', COUNT(*) FROM refund
UNION ALL SELECT 'settlement', COUNT(*) FROM settlement
UNION ALL SELECT 'withdraw', COUNT(*) FROM withdraw
UNION ALL SELECT 'recharge_order', COUNT(*) FROM recharge_order
UNION ALL SELECT 'customer_gold_txn', COUNT(*) FROM customer_gold_txn
UNION ALL SELECT 'booster_earning_txn', COUNT(*) FROM booster_earning_txn
UNION ALL SELECT 'idempotency_record', COUNT(*) FROM idempotency_record;

SELECT '--- 资金账户余额（客户应 100000，打手应 0）---' AS info;
SELECT user_id, balance, total_recharged FROM customer_gold_account ORDER BY user_id;
SELECT booster_id, frozen, available, total FROM booster_earning_account ORDER BY booster_id;

SELECT '--- 种子主数据应保留 ---' AS info;
SELECT 'user' AS tbl, COUNT(*) AS cnt FROM user
UNION ALL SELECT 'booster', COUNT(*) FROM booster
UNION ALL SELECT 'admin_user', COUNT(*) FROM admin_user
UNION ALL SELECT 'service_category', COUNT(*) FROM service_category
UNION ALL SELECT 'service_sku', COUNT(*) FROM service_sku
UNION ALL SELECT 'banner', COUNT(*) FROM banner;
