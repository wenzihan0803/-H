-- ============================================================
-- 服务 SKU 增加「地图 / 介绍」配置项（2026-10-30）
--
-- 产品口径：陪玩 / 护航 / 战术教学 的具体单子由管理端配置，
--          可配置项 = 地图 + 名称 + 介绍 + 价格（单价仍以分存储）。
--
-- 说明：名称(name)、单价(price)、上下架(enabled) 已有字段；
--      本次只补 map_name、description，并给已有 6 个 SKU 填默认值。
-- ============================================================

ALTER TABLE `service_sku`
  ADD COLUMN `map_name` varchar(32) DEFAULT NULL COMMENT '地图名（展示用）' AFTER `name`,
  ADD COLUMN `description` varchar(255) DEFAULT NULL COMMENT '服务介绍（展示用）' AFTER `map_name`;

UPDATE `service_sku` SET `map_name` = '零号大坝', `description` = '钻石段位陪玩，1 小时起，稳定上分' WHERE `id` = 2001;
UPDATE `service_sku` SET `map_name` = '长弓溪谷', `description` = '王者段位陪玩，高强度对局，胜率优先' WHERE `id` = 2002;
UPDATE `service_sku` SET `map_name` = '巴克什',   `description` = '护航至安全撤离，按带出价值结算' WHERE `id` = 2003;
UPDATE `service_sku` SET `map_name` = '航天基地', `description` = '高价值物资护航，全程接应' WHERE `id` = 2004;
UPDATE `service_sku` SET `map_name` = '零号大坝', `description` = '基础枪法、身法与常用点位教学' WHERE `id` = 2005;
UPDATE `service_sku` SET `map_name` = '长弓溪谷', `description` = '进阶战术、团队配合与撤离路线' WHERE `id` = 2006;
