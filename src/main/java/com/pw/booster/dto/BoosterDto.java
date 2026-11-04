package com.pw.booster.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 打手端响应对象。对应 04 §2.5 {@code BoosterEarning} / {@code EarningTxn} / {@code Withdraw}。
 */
public final class BoosterDto {

    private BoosterDto() {
    }

    /** 收益账户三栏。对应 {@code BoosterEarning}。 */
    @Data
    @Builder
    @Schema(description = "打手收益账户")
    public static class Earning {

        /** 冻结中（提现审核期 + 封号冻结） */
        private Long frozen;

        /** 可提现 */
        private Long available;

        /** 历史累计到手 */
        private Long total;
    }

    /** 收益流水。对应 {@code EarningTxn}。 */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "收益流水")
    public static class EarningTxn {

        private Long id;

        /** SETTLE / WITHDRAW_FREEZE / WITHDRAW_SUCCESS / WITHDRAW_FAIL / PUNISH_FREEZE / PUNISH_UNFREEZE / ADJUST */
        private String bizType;

        private String bizNo;

        /** 变动金额（分），带符号 */
        private Long amount;

        private Long frozenAfter;

        private Long availableAfter;

        private Long orderId;

        private String remark;

        private LocalDateTime createdAt;
    }

    /** 提现单。对应 {@code Withdraw}。 */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "提现单")
    public static class WithdrawItem {

        private Long id;

        private String withdrawNo;

        private Long amount;

        /** APPLIED/APPROVED/PAYING/PAID/REJECTED/FAILED */
        private String status;

        private LocalDateTime createdAt;

        private LocalDateTime paidAt;

        private String failReason;
    }

    /** 打手统计。对应 04 §3.6 {@code GET /booster/stats}。 */
    @Data
    @Builder
    @Schema(description = "打手统计")
    public static class Stats {

        private Long todayOrders;

        private Long monthOrders;

        /** 服务评分 */
        private String score;

        /** 完成率（百分比） */
        private String completeRate;
    }

    /** 打手游戏账号。对应 04 §3.6 {@code GameAccount}。 */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "打手游戏账号")
    public static class GameAccount {

        private Long id;

        private String gameId;

        private String accountId;

        private String server;

        /** ACTIVE / DISABLED */
        private String status;

        private LocalDateTime createdAt;
    }
}
