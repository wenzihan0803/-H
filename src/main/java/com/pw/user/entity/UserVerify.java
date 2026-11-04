package com.pw.user.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 实名认证记录。对应 05 §2.2。
 *
 * <p>{@code uk_user} 唯一：一个用户只有一条实名记录，重新提交走 update。</p>
 *
 * <p>本期不做（09 §1）：{@code feature.realname=false} 时下单/入驻 guard 直接放行，
 * 上线前接三方服务并置 true。</p>
 */
@Data
@TableName("user_verify")
public class UserVerify {

    @TableId
    private Long id;

    private Long userId;

    /** 真实姓名密文 */
    private String realNameCipher;

    /** 身份证密文 */
    private String idCardCipher;

    /** 身份证 SHA-256，用于查重（idx_id_card_hash） */
    private String idCardHash;

    private Integer age;

    /** PASS / FAIL / SKIP（人脸活体结果） */
    private String faceResult;

    /** PENDING / PASSED / REJECTED */
    private String status;

    private LocalDateTime verifiedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public static final String STATUS_NONE = "NONE";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PASSED = "PASSED";
    public static final String STATUS_REJECTED = "REJECTED";

    public boolean isPassed() {
        return STATUS_PASSED.equals(status);
    }
}
