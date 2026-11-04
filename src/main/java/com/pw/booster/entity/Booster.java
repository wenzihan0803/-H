package com.pw.booster.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 打手。对应 05 §3.1。
 *
 * <p>{@code uk_user} 唯一：一个 user 最多一条打手记录。
 * 打手与用户共用 {@code user} 表身份，登录时按所选角色签发不同 token（01 §2）。</p>
 *
 * <p><b>注意 id 语义</b>：{@code booster.id} 是打手业务主键，
 * 与 {@code user.id} 不同。JWT 的 sub 存 user_id，{@code boosterId} 单独放 claim。</p>
 */
@Data
@TableName("booster")
public class Booster {

    @TableId
    private Long id;

    /** 关联 user.id，uk_user 唯一 */
    private Long userId;

    /**
     * PENDING_REVIEW / PENDING_CALL / PENDING_TRIAL / ACTIVE /
     * TEMP_BANNED / SUSPENDED / BANNED / REJECTED
     */
    private String status;

    /** 打手专用昵称，与 user.nickname 独立；30 天限改一次 */
    private String nickname;

    private Integer age;

    /** MALE / FEMALE */
    private String gender;

    private Integer level;

    /** 服务分 decimal(3,2)，默认 5.00 */
    private BigDecimal serviceScore;

    /** 完单率 decimal(5,2)，默认 100.00 */
    private BigDecimal completeRate;

    /** 封禁截止时间，TEMP_BANNED 时有值 */
    private LocalDateTime banUntil;

    private LocalDateTime activatedAt;

    /** 最近一次改昵称时间，用于 30 天限改 */
    private LocalDateTime nicknameUpdatedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public static final String STATUS_PENDING_REVIEW = "PENDING_REVIEW";
    public static final String STATUS_PENDING_CALL = "PENDING_CALL";
    public static final String STATUS_PENDING_TRIAL = "PENDING_TRIAL";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_TEMP_BANNED = "TEMP_BANNED";
    public static final String STATUS_SUSPENDED = "SUSPENDED";
    public static final String STATUS_BANNED = "BANNED";
    public static final String STATUS_REJECTED = "REJECTED";

    /** 可接单状态。仅 ACTIVE（01 §3 guard「接单：打手 ACTIVE」）。 */
    public boolean canAcceptOrder() {
        return STATUS_ACTIVE.equals(status);
    }

    /** 是否处于封禁态（临时封禁 / 停用 / 永久封禁）。 */
    public boolean isBanned() {
        return STATUS_TEMP_BANNED.equals(status)
                || STATUS_SUSPENDED.equals(status)
                || STATUS_BANNED.equals(status);
    }
}
