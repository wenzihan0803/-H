package com.pw.user.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户表。对应 05 §2.1。
 *
 * <p><b>本表是全项目唯一使用软删除的表</b>（05 §1「软删除：仅 user 使用 deleted_at；
 * 订单/资金表禁止物理删除」），故 {@code @TableLogic} 显式标注在此，不做全局配置。</p>
 *
 * <p>敏感字段口径（05 §1）：手机号存 {@code phone_cipher}（AES-GCM）+ {@code phone_hash}（SHA-256 供检索）；
 * 接口输出必须脱敏为 {@code 138****0000}。</p>
 */
@Data
@TableName("user")
public class User {

    /** 雪花 ID，对外以字符串输出 */
    @TableId
    private Long id;

    /** 微信 openid，唯一索引 uk_openid */
    private String openid;

    private String unionid;

    /** 手机号密文（AES-GCM Base64） */
    private String phoneCipher;

    /** 手机号 SHA-256，用于等值检索 */
    private String phoneHash;

    private String nickname;

    private String avatar;

    /** NORMAL / FROZEN */
    private String status;

    /** 是否已实名（0=否 1=是）。feature.realname=false 时 guard 放行但本字段仍如实记录 */
    private Integer verified;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /**
     * 软删除标记。null=未删除，非 null=删除时间。
     *
     * <p><b>必须显式指定 value/delval</b>：该列是 {@code datetime} 类型，
     * 而 {@code @TableLogic} 默认按 {@code 0/1} 生成 {@code WHERE deleted_at=0}——
     * datetime 列与 0 比较永远不匹配 NULL，会导致所有 user 查询恒返回空集，
     * 进而误判「用户不存在」去 INSERT，再撞 openid 唯一索引，登录链路全线失败。</p>
     *
     * <p>正确写法：{@code value="null"} 让查询生成 {@code deleted_at IS NULL}，
     * {@code delval="now()"} 让删除写入当前时间。</p>
     */
    @TableLogic(value = "null", delval = "now()")
    private LocalDateTime deletedAt;

    public static final String STATUS_NORMAL = "NORMAL";
    public static final String STATUS_FROZEN = "FROZEN";

    public boolean isFrozen() {
        return STATUS_FROZEN.equals(status);
    }

    /**
     * 是否已完成实名认证。
     *
     * <p><b>命名注意</b>：不能叫 {@code isVerified()}。字段 {@code verified} 是 {@code Integer}，
     * Lombok {@code @Data} 会生成 {@code getVerified()}；若再手写一个返回 {@code boolean} 的
     * {@code isVerified()}，MyBatis 反射会判定该属性存在「类型二义的重载 getter」并直接抛
     * {@code ReflectionException}，导致所有 user 表写入失败。故此处用 {@code hasVerified()}。</p>
     */
    public boolean hasVerified() {
        return verified != null && verified == 1;
    }
}
