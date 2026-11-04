package com.pw.common.security;

import com.pw.common.enums.Role;
import com.pw.common.exception.BizException;
import com.pw.common.exception.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 登录态读取工具。
 *
 * <p>所有按 ID 取资源的接口必须校验归属，防 IDOR（技术规范/01 §2、§17）。
 * 本类只负责「取出当前登录者」，归属校验在各模块 Service 内完成。</p>
 */
public final class SecurityContexts {

    private SecurityContexts() {
    }

    /** 获取当前登录态；未登录抛 40101。 */
    public static LoginUser require() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof LoginUser loginUser)) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        return loginUser;
    }

    /** 获取当前登录态；未登录返回 {@code null}（用于公开接口可选登录）。 */
    public static LoginUser current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof LoginUser loginUser) {
            return loginUser;
        }
        return null;
    }

    public static Long requireSubjectId() {
        return require().getSubjectId();
    }

    /** 当前登录者必须是管理端角色，否则 40301。 */
    public static LoginUser requireAdmin() {
        LoginUser loginUser = require();
        if (!loginUser.isAdmin()) {
            throw BizException.of(ErrorCode.FORBIDDEN, "该操作仅管理端可执行，请切换到管理后台");
        }
        return loginUser;
    }

    /** 当前登录者必须是客服/管理员/超管之一。 */
    public static LoginUser requireRole(Role... allowed) {
        LoginUser loginUser = require();
        for (Role role : allowed) {
            if (loginUser.getRole() == role) {
                return loginUser;
            }
        }
        throw BizException.of(ErrorCode.FORBIDDEN, "当前角色无权执行该操作，请联系管理员");
    }

    /** 当前登录者必须是客户。 */
    public static LoginUser requireCustomer() {
        LoginUser loginUser = require();
        if (!loginUser.isCustomer()) {
            throw BizException.of(ErrorCode.FORBIDDEN, "该操作仅客户可用，请切换身份后重试");
        }
        return loginUser;
    }

    /** 当前登录者必须是打手。 */
    public static LoginUser requireBooster() {
        LoginUser loginUser = require();
        if (!loginUser.isBooster()) {
            throw BizException.of(ErrorCode.FORBIDDEN, "该操作仅打手可用，请切换身份后重试");
        }
        return loginUser;
    }
}
