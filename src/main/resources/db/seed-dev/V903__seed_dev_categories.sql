-- ============================================================
-- 客户端服务类目调整（2026-10-30）
--
-- 产品口径：
--   1) 客户端当前只支持老板端，不提供「指定打手」；
--   2) 服务类目收敛为 3 个：陪玩 / 护航 / 战术教学；
--   3) 计费口径：陪玩按小时、护航按带出价值、战术教学按小时；
--   4) 「固定车队」暂停，保留历史数据但置为不可用。
--
-- 说明：/categories、/skus 接口尚未实现，前端走 @shared/api/mock/db.ts 的种子，
--      本迁移保证 DB 种子与前端 Mock 名称/单价一致，接口切真后不会打架。
-- ============================================================

UPDATE `service_category` SET `name` = '陪玩',     `unit` = 'HOUR',  `sort` = 1 WHERE `id` = 1001;
UPDATE `service_category` SET `name` = '护航',     `unit` = 'VALUE', `sort` = 2 WHERE `id` = 1002;
UPDATE `service_category` SET `name` = '战术教学', `unit` = 'HOUR',  `sort` = 3 WHERE `id` = 1003;
UPDATE `service_category` SET `enabled` = 0 WHERE `id` = 1004;

UPDATE `service_sku` SET `name` = '钻石局陪玩 · 1 小时', `unit` = 'HOUR', `max_qty` = 6 WHERE `id` = 2001;
UPDATE `service_sku` SET `name` = '王者局陪玩 · 1 小时', `unit` = 'HOUR', `max_qty` = 6 WHERE `id` = 2002;
UPDATE `service_sku` SET `name` = '护航 · 带出价值 5 万内',  `unit` = 'VALUE', `price` = 5000, `max_qty` = 5 WHERE `id` = 2003;
UPDATE `service_sku` SET `name` = '护航 · 带出价值 10 万内', `unit` = 'VALUE', `price` = 9000, `max_qty` = 5 WHERE `id` = 2004;
UPDATE `service_sku` SET `name` = '基础枪法教学 · 1 小时', `unit` = 'HOUR', `max_qty` = 6 WHERE `id` = 2005;
UPDATE `service_sku` SET `name` = '进阶战术教学 · 1 小时', `unit` = 'HOUR', `max_qty` = 6 WHERE `id` = 2006;
UPDATE `service_sku` SET `enabled` = 0 WHERE `id` IN (2007, 2008);

-- 打手技能里引用已下线类目的记录一并清掉，避免出现「能选到固定车队」的残留
DELETE FROM `booster_skill` WHERE `category_id` = 1004;
