package com.pw.booster.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 打手游戏账号。对应 05 §3.6。
 *
 * <p>履约守卫（README §1.1、01 §3）：开始服务与申请完单都必须带打手游戏账号 ID，
 * 用于证明是本人在打，而非代打/上号。</p>
 */
@Data
@TableName("booster_game_account")
public class BoosterGameAccount {

    @TableId
    private Long id;

    private Long boosterId;

    private String gameId;

    private String accountId;

    private String server;

    /** ACTIVE / DISABLED */
    private String status;

    private LocalDateTime verifiedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";

    public boolean isActive() {
        return STATUS_ACTIVE.equals(status);
    }
}
