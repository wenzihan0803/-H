-- ============================================================================
-- V906：打手资料（专用昵称/年龄/性别）、入驻资料、结算费率留痕
--
-- 口径（2026-10-31 产品确认）：
--   - 打手昵称独立于 user.nickname，只影响打手列表与订单里的打手名；
--   - 年龄/性别入驻必填，老打手可补填一次；
--   - 结算：平台费 = 原价 × 15%（会员 10%），打手 = 实付 − 平台费；
--     结算单记录 platform_rate / member_flag，便于事后对账。
-- ============================================================================
SET NAMES utf8mb4;

-- 1) 打手资料
ALTER TABLE `booster`
    ADD COLUMN `nickname` varchar(64) DEFAULT NULL COMMENT '打手专用昵称' AFTER `user_id`,
    ADD COLUMN `age` int DEFAULT NULL COMMENT '年龄（入驻时填写）' AFTER `status`,
    ADD COLUMN `gender` varchar(8) DEFAULT NULL COMMENT 'MALE/FEMALE' AFTER `age`,
    ADD COLUMN `nickname_updated_at` datetime(3) DEFAULT NULL COMMENT '最近改名时间，30 天限一次' AFTER `activated_at`,
    ADD KEY `idx_nickname` (`nickname`);

-- 老打手昵称从账号昵称回填
UPDATE `booster` b
JOIN `user` u ON u.id = b.`user_id`
SET b.`nickname` = u.`nickname`
WHERE b.`nickname` IS NULL;

-- 种子打手先统一为男（用户确认）
UPDATE `booster` SET `gender` = 'MALE' WHERE `id` IN (5001, 5002, 5003, 5004) AND `gender` IS NULL;

-- 2) 入驻资料：补齐昵称/性别/游戏/段位/介绍/微信
ALTER TABLE `booster_apply_profile`
    ADD COLUMN `nickname` varchar(64) DEFAULT NULL AFTER `apply_id`,
    ADD COLUMN `gender` varchar(8) DEFAULT NULL AFTER `age`,
    ADD COLUMN `game_key` varchar(32) DEFAULT NULL COMMENT '游戏标识，如 delta_force' AFTER `gender`,
    ADD COLUMN `game_account` varchar(64) DEFAULT NULL COMMENT '游戏内 ID' AFTER `game_key`,
    ADD COLUMN `rank_text` varchar(32) DEFAULT NULL COMMENT '段位/实力' AFTER `game_account`,
    ADD COLUMN `wechat` varchar(64) DEFAULT NULL COMMENT '联系微信' AFTER `rank_text`,
    ADD COLUMN `intro` varchar(500) DEFAULT NULL COMMENT '自我介绍' AFTER `wechat`;

-- 3) 结算单费率留痕
ALTER TABLE `settlement`
    ADD COLUMN `platform_rate` int DEFAULT NULL COMMENT '平台费率（15/10）' AFTER `platform_amount`,
    ADD COLUMN `member_flag` tinyint NOT NULL DEFAULT 0 COMMENT '结算时是否会员' AFTER `platform_rate`;

-- 4) 仲裁工单：记录仲裁前状态与关联退款单
ALTER TABLE `complaint`
    ADD COLUMN `previous_status` varchar(32) DEFAULT NULL COMMENT '仲裁前订单状态' AFTER `status`,
    ADD COLUMN `refund_id` bigint DEFAULT NULL COMMENT '仲裁通过后生成的退款单' AFTER `previous_status`,
    ADD COLUMN `evidence_json` json DEFAULT NULL COMMENT '客户提交的凭证 objectKey 数组' AFTER `description`;
