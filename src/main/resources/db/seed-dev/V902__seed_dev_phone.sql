-- ============================================================
-- dev 种子补充：给测试账号绑定手机号
--
-- 目的：登录页改为「手机号登录 / 微信一键登录」后，用手机号仍能
--      命中已有种子数据（金砖余额、历史订单、打手状态），
--      避免每次登录都变成无数据的新用户。
--
-- 约定手机号（仅 dev）：
--   13800000001 → dev_openid_customer_01（测试老板A，有余额/订单）
--   13800000002 → dev_openid_customer_02（测试老板B）
--   13800000003 → dev_openid_booster_01（测试打手A）
--   13800000004 → dev_openid_booster_02（测试打手B）
--   13800000005 → dev_openid_booster_03（测试打手C）
--
-- phone_hash 口径与 CryptoUtil.sha256 一致：SHA-256 小写十六进制，
-- 因此这里可直接用 MySQL SHA2(x, 256) 对齐，不需要应用侧再补数据。
-- phone_cipher 留空：dev 不展示脱敏手机号，加密写入走应用侧真实链路。
-- ============================================================

UPDATE `user` SET phone_hash = SHA2('13800000001', 256) WHERE openid = 'dev_openid_customer_01';
UPDATE `user` SET phone_hash = SHA2('13800000002', 256) WHERE openid = 'dev_openid_customer_02';
UPDATE `user` SET phone_hash = SHA2('13800000003', 256) WHERE openid = 'dev_openid_booster_01';
UPDATE `user` SET phone_hash = SHA2('13800000004', 256) WHERE openid = 'dev_openid_booster_02';
UPDATE `user` SET phone_hash = SHA2('13800000005', 256) WHERE openid = 'dev_openid_booster_03';
