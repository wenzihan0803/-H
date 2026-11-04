package com.pw.common.security;

import com.pw.common.enums.Role;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 角色限制注解。
 *
 * <p>口径来源：技术规范/01 §1 包结构 {@code common/security # JWT、登录态、@RequireRole}。</p>
 *
 * <p>标注在 Controller 方法或类上，声明允许访问的角色。由 {@link RequireRoleAspect} 统一拦截，
 * 相比 {@code @PreAuthorize} 更直观，且能覆盖「客服可发起、主管可审批」这类业务分级。</p>
 *
 * <p>注意：本注解只做角色门禁，<b>不替代资源归属校验</b>。
 * 所有按 ID 取资源的接口仍需在 Service 内校验归属，防 IDOR。</p>
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {

    /** 允许访问的角色，为空表示仅需登录、不限角色。 */
    Role[] value() default {};
}
