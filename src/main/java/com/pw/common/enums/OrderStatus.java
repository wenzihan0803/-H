package com.pw.common.enums;

/**
 * 订单状态枚举（唯一版本）。
 *
 * <p>口径来源：技术规范/README.md §1、/01 §3、/04 §1.5、/05 §10。</p>
 *
 * <p><b>共 8 个状态，没有 PENDING_PAY / AFTER_SALE / REFUNDING / CANCELLED / SETTLED。</b></p>
 *
 * <pre>
 * PENDING_ACCEPT → ACCEPTED → IN_SERVICE → PENDING_CONFIRM → COMPLETED
 *
 * PENDING_ACCEPT / ACCEPTED --客户自助全额退--> REFUNDED
 *
 * IN_SERVICE / PENDING_CONFIRM --客服争议退款--> DISPUTING
 *   DISPUTING --主管驳回--> 回到原状态
 *   DISPUTING --100%--> REFUNDED
 *   DISPUTING --1%-99%--> PARTIAL_REFUNDED（结算剩余部分）
 * </pre>
 */
public enum OrderStatus {

    /** 待接单：已创建并扣金砖，托管金 HELD */
    PENDING_ACCEPT(false),
    /** 已接单待交接 */
    ACCEPTED(false),
    /** 服务中：开局照 + 账号 ID + 交接 ACK 后进入 */
    IN_SERVICE(false),
    /** 订单完成待确认：两照 + 账号 ID；confirm_deadline = service_completed_at + 7 天 */
    PENDING_CONFIRM(false),
    /** 争议退款审核中：客服已提交比例，等待主管；暂停确认与自动确认 */
    DISPUTING(false),
    /** 已确认已结算，终态：托管金清零，打手收益直接到账 */
    COMPLETED(true),
    /** 全额退款结束，终态 */
    REFUNDED(true),
    /** 部分退款结束，终态 */
    PARTIAL_REFUNDED(true),
    ;

    /** 是否终态。终态订单不能再流转、不能退款（COMPLETED 走仲裁工单） */
    private final boolean terminal;

    OrderStatus(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }

    /** 客户可自助全额退的状态：服务开始前（README §2.1） */
    public boolean canCustomerSelfRefund() {
        return this == PENDING_ACCEPT || this == ACCEPTED;
    }

    /** 客服可发起争议退款的状态：服务开始后（README §2.2） */
    public boolean canDisputeRefund() {
        return this == IN_SERVICE || this == PENDING_CONFIRM;
    }

    /** 是否处于履约中（打手不可同时接第二单） */
    public boolean isInProgress() {
        return this == ACCEPTED || this == IN_SERVICE || this == PENDING_CONFIRM;
    }


    /** 中文名（错误提示与页面展示用，禁止再拼 {@code name()}） */
    public String label() {
        return switch (this) {
            case PENDING_ACCEPT -> "待接单";
            case ACCEPTED -> "已接单";
            case IN_SERVICE -> "服务中";
            case PENDING_CONFIRM -> "待确认";
            case DISPUTING -> "仲裁中";
            case COMPLETED -> "已完成";
            case REFUNDED -> "已退款";
            case PARTIAL_REFUNDED -> "部分退款";
        };
    }

    public static OrderStatus of(String value) {
        if (value == null) {
            return null;
        }
        for (OrderStatus status : values()) {
            if (status.name().equalsIgnoreCase(value)) {
                return status;
            }
        }
        return null;
    }
}
