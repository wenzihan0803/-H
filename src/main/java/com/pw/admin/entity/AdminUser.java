package com.pw.admin.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理端账号。对应 05 §6.8。
 *
 * <p>口径来源：01 §2 —— 管理端「账号密码 + BCrypt → 超管二次验证 → JWT」。</p>
 *
 * <p>角色分级（README §2.3）：</p>
 * <ul>
 *   <li>{@code CS}：客服，<b>零资金权限</b>，可发起争议退款但不可审批、不可看流水；</li>
 *   <li>{@code ADMIN}：管理员（主管），可审批退款、确认提现、查看流水；</li>
 *   <li>{@code SUPER_ADMIN}：超管，唯一，可改类目/SKU/Banner，可代审。</li>
 * </ul>
 */
@Data
@TableName("admin_user")
public class AdminUser {

    @TableId
    private Long id;

    /** 登录账号，uk_username 唯一 */
    private String username;

    /** BCrypt 哈希，绝不存明文、绝不出接口 */
    private String passwordHash;

    /** CS / ADMIN / SUPER_ADMIN */
    private String role;

    /** ACTIVE / DISABLED */
    private String status;

    private LocalDateTime lastLoginAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";

    public boolean isEnabled() {
        return STATUS_ACTIVE.equals(status);
    }
}
