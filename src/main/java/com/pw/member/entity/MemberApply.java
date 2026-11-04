package com.pw.member.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会员申请。对应 05 §2.7。
 *
 * <p>口径（README §4、01 §4）：单笔充值 ≥ 2,000 元时，充值回调自动创建一条
 * {@code PENDING} 申请；管理员通过后 {@code member_account.level=MEMBER}（永久）。</p>
 *
 * <p>{@code uk_recharge} 唯一：一笔充值最多产生一条会员申请，回调重复投递不会重复建单。</p>
 */
@Data
@TableName("member_apply")
public class MemberApply {

    @TableId
    private Long id;

    private Long userId;

    /** 触发本申请的充值单 id */
    private Long rechargeId;

    /** SELF / CS */
    private String source;

    /** PENDING / APPROVED / REJECTED */
    private String status;

    private String rejectReason;

    private Long reviewedBy;

    private LocalDateTime reviewedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public static final String SOURCE_SELF = "SELF";
    public static final String SOURCE_CS = "CS";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
}
