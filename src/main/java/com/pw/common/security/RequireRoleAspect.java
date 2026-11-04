package com.pw.common.security;

import com.pw.common.enums.Role;
import com.pw.common.exception.BizException;
import com.pw.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;

/**
 * {@link RequireRole} 拦截切面。
 *
 * <p>校验顺序：先确保已登录（40101），再校验角色（40301）。
 * 方法级注解优先于类级注解。</p>
 */
@Slf4j
@Aspect
@Component
public class RequireRoleAspect {

    @Before("@annotation(com.pw.common.security.RequireRole) || @within(com.pw.common.security.RequireRole)")
    public void check(org.aspectj.lang.JoinPoint joinPoint) {
        RequireRole annotation = resolveAnnotation(joinPoint);
        if (annotation == null) {
            return;
        }
        LoginUser loginUser = SecurityContexts.require();
        Role[] allowed = annotation.value();
        if (allowed.length == 0) {
            return;
        }
        for (Role role : allowed) {
            if (loginUser.getRole() == role) {
                return;
            }
        }
        log.warn("角色门禁拒绝 subjectId={} role={} 需要={}",
                loginUser.getSubjectId(), loginUser.getRole(), allowed);
        throw BizException.of(ErrorCode.FORBIDDEN, "当前角色无权执行该操作，请联系管理员");
    }

    private RequireRole resolveAnnotation(org.aspectj.lang.JoinPoint joinPoint) {
        if (!(joinPoint instanceof org.aspectj.lang.reflect.MethodSignature signature)) {
            return null;
        }
        Object target = joinPoint.getTarget();
        if (target != null) {
            // CGLIB 代理会覆写方法且不带注解，必须解析到「最终用户类」上的真实方法
            Class<?> targetClass = AopProxyUtils.ultimateTargetClass(target);
            java.lang.reflect.Method specific =
                    AopUtils.getMostSpecificMethod(signature.getMethod(), targetClass);
            RequireRole methodAnnotation = AnnotatedElementUtils.findMergedAnnotation(specific, RequireRole.class);
            if (methodAnnotation != null) {
                return methodAnnotation;
            }
            return AnnotatedElementUtils.findMergedAnnotation(targetClass, RequireRole.class);
        }
        RequireRole methodAnnotation = signature.getMethod().getAnnotation(RequireRole.class);
        if (methodAnnotation != null) {
            return methodAnnotation;
        }
        return AnnotatedElementUtils.findMergedAnnotation(signature.getDeclaringType(), RequireRole.class);
    }
}
