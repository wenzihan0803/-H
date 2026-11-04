package com.pw.common.security;

import com.pw.common.enums.Role;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

/**
 * 当前登录态。由 JWT 解析 + Redis 会话校验后写入 SecurityContext。
 *
 * <p>口径来源：技术规范/01 §2 —— claims 为 {@code sub / role / sid / exp}；
 * Redis 存 {@code session:{sid}}，登出与封禁即时失效。</p>
 *
 * <p>{@code subjectId} 的含义随角色变化：</p>
 * <ul>
 *   <li>CUSTOMER：{@code user.id}；</li>
 *   <li>BOOSTER：{@code user.id}（打手侧另有 {@code booster.id}，业务层按 user_id 查）；</li>
 *   <li>CS/ADMIN/SUPER_ADMIN：{@code admin_user.id}。</li>
 * </ul>
 */
@Getter
@Builder
@ToString(exclude = {"sessionId"})
public class LoginUser {

    /** JWT 的 sub：客户/打手为 user_id，管理端为 admin_user_id */
    private final Long subjectId;

    private final Role role;

    /** 会话 ID，用于 Redis 会话校验与强制登出 */
    private final String sessionId;

    /** 仅打手：booster 表主键，登录时预解析避免每次查询 */
    private final Long boosterId;

    /** 仅客户：微信 openid */
    private final String openid;

    /** 仅管理端：登录账号名 */
    private final String username;

    public boolean isAdmin() {
        return role != null && role.isAdminSide();
    }

    public boolean isCustomer() {
        return role == Role.CUSTOMER;
    }

    public boolean isBooster() {
        return role == Role.BOOSTER;
    }

    /** 是否为审批角色（主管/超管）。 */
    public boolean canApproveRefund() {
        return role != null && role.canApproveRefund();
    }

    /** 是否为超管。 */
    public boolean isSuperAdmin() {
        return role == Role.SUPER_ADMIN;
    }
}
