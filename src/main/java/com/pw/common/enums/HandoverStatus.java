package com.pw.common.enums;

/**
 * 客服交接状态。对应 {@code biz_order.handover_status} 与 {@code handover_task.status}。
 *
 * <p>口径来源：技术规范/README §5、/01 §11、/04 §1.5。</p>
 *
 * <pre>
 * PENDING → SENT → ACK
 *    │        │
 *    └────────┴──→ TIMEOUT（10/20/30 分钟升级）
 * </pre>
 *
 * <p>升级规则：10 分钟未发送提醒客服；20 分钟升级管理员；30 分钟打手可无责退款。</p>
 *
 * <p><b>越权约束</b>：打手端只返回 {@code bossGameId} + {@code guide}，
 * 不返回区服/备注/手机号（README §5、04 §3.6 注）。</p>
 */
public enum HandoverStatus {

    /** 打手已接单，等待客服发送老板 ID */
    PENDING,
    /** 客服已发送老板游戏 ID + 指引 */
    SENT,
    /** 打手已确认收到（POST /booster/orders/{id}/handover/ack） */
    ACK,
    /** 超时 */
    TIMEOUT,
    ;

    /** 打手可 ACK 的前置状态：必须是 SENT（04 §4.5）。 */
    public boolean canAck() {
        return this == SENT;
    }
}
