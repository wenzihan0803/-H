-- ============================================================
-- V901: 删除邀请功能（2026-10-29 用户拍板）
-- 背景：邀请「绑码→奖励→抵扣」链路本期不做完整实现，而订单金额计算器
--       只凭入参 useInviteReward 就抵扣，存在无资格白嫖 5 元的漏洞。
--       决策：整条邀请功能从代码与库表中移除。
-- 影响：invite_relation / invite_reward 两张表删除；
--       biz_order.invite_discount 列删除（历史值恒为 0，无数据损失）。
-- 代码同步：OrderAmountCalculator / OrderCreateService / CreateOrderReq /
--           OrderDto.Amounts / BizOrder / BusinessProperties / application.yml
-- 版本号取 V901：因种子数据为 V900 且已应用，低于 900 的新迁移会被 Flyway 以 out-of-order 拒绝。
-- 口径记录见：源代码/后端/后端开发总览.md §变更记录
-- ============================================================

-- 1) 订单表移除邀请抵扣列
ALTER TABLE `biz_order` DROP COLUMN `invite_discount`;

-- 2) 删除邀请相关表（V1 中创建）
DROP TABLE IF EXISTS `invite_reward`;
DROP TABLE IF EXISTS `invite_relation`;
