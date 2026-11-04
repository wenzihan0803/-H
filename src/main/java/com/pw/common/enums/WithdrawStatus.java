package com.pw.common.enums;

/**
 * 提现单状态。对应 {@code withdraw.status}。
 *
 * <p>口径来源：技术规范/04 §1.5、/05 §5.4、/01 §8、README §6。</p>
 *
 * <pre>
 * APPLIED --管理员确认--> APPROVED --商家转账中--> PAYING --成功--> PAID
 *    |                       |                      |
 *    |                       |                      +--失败--> FAILED（frozen 退回 available）
 *    +--管理员拒绝--> REJECTED（frozen 退回 available）
 *    +--打手被封号--> REJECTED（自动拒绝，理由「打手封号，提现暂缓」）
 * </pre>
 */
public enum WithdrawStatus {

    /** 已申请，金额已从 available 转入 frozen */
    APPLIED,
    /** 管理员已确认，待打款 */
    APPROVED,
    /** 商家转账进行中 */
    PAYING,
    /** 已打款成功，frozen 清零 */
    PAID,
    /** 已拒绝（管理员拒绝 或 封号自动拒绝），frozen 退回 available */
    REJECTED,
    /** 打款失败，frozen 退回 available */
    FAILED,
    ;

    public boolean isTerminal() {
        return this == PAID || this == REJECTED || this == FAILED;
    }

    /**
     * 是否「进行中」——封号时这些状态会被自动拒绝（01 §7.3-B、README §6.2）。
     *
     * <p>封号优先级高于提现：封号动作提交时，进行中的提现流程自动拒绝，
     * 理由固定为「打手封号，提现暂缓」。</p>
     */
    public boolean isInFlight() {
        return this == APPLIED || this == APPROVED || this == PAYING;
    }

    /** 失败/拒绝时是否需要把 frozen 退回 available。 */
    public boolean shouldRefundToAvailable() {
        return this == REJECTED || this == FAILED;
    }

    /** 中文名（错误提示用） */
    public String label() {
        return switch (this) {
            case APPLIED -> "已申请";
            case APPROVED -> "已确认待打款";
            case PAYING -> "打款中";
            case PAID -> "已打款";
            case REJECTED -> "已拒绝";
            case FAILED -> "打款失败";
        };
    }
}
