package com.pw.booster.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 入驻资料（一申请一条）。对应 {@code booster_apply_profile}。 */
@Data
@TableName("booster_apply_profile")
public class BoosterApplyProfile {

    @TableId
    private Long id;

    private Long applyId;

    private String nickname;

    /** 年龄（入驻时填写） */
    private Integer age;

    /** MALE / FEMALE */
    private String gender;

    private String city;

    /** 实名校验用密文，本期不展示 */
    private String realNameCipher;

    /** 联系方式密文（手机号/微信） */
    private String contactCipher;

    private String gameKey;

    private String gameAccount;

    private String rankText;

    private String wechat;

    private String intro;

    private String device;

    private String serviceTime;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
