package com.pw.common.security;

import com.pw.common.exception.BizException;
import com.pw.common.result.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * JWT 认证过滤器。
 *
 * <p>流程：取 {@code Authorization: Bearer <token>} → 验签解析 → 校验 Redis 会话 →
 * 写入 SecurityContext。无 token 时不拦截，交由 {@code SecurityConfig} 的授权规则决定。</p>
 *
 * <p>会话校验是登出与封号能即时生效的关键（技术规范/01 §2）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final SessionStore sessionStore;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = resolveToken(request);
        if (token == null) {
            chain.doFilter(request, response);
            return;
        }

        try {
            Claims claims = jwtService.parse(token);
            // 只接受 Access Token；Refresh 仅用于 /auth/refresh
            String type = claims.get(JwtService.CLAIM_TYPE, String.class);
            if (!JwtService.TYPE_ACCESS.equals(type)) {
                writeError(response, com.pw.common.exception.ErrorCode.TOKEN_INVALID, "登录凭证类型错误，请重新登录");
                return;
            }

            LoginUser loginUser = jwtService.toLoginUser(claims);

            // 会话不存在说明已登出或被封禁强制下线
            if (!sessionStore.exists(loginUser.getSessionId())) {
                writeError(response, com.pw.common.exception.ErrorCode.UNAUTHORIZED, "登录已失效，请重新登录");
                return;
            }

            var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + loginUser.getRole().name()));
            var authentication = new UsernamePasswordAuthenticationToken(loginUser, null, authorities);
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (BizException ex) {
            SecurityContextHolder.clearContext();
            writeError(response, ex.getErrorCode(), ex.getMessage());
            return;
        } catch (Exception ex) {
            log.warn("JWT 认证异常 uri={} err={}", request.getRequestURI(), ex.getMessage());
            SecurityContextHolder.clearContext();
            writeError(response, com.pw.common.exception.ErrorCode.TOKEN_INVALID, "登录凭证无效，请重新登录");
            return;
        }

        chain.doFilter(request, response);
    }

    /** 直接写回统一响应体，避免走 Security 默认的重定向/401 空响应。 */
    private void writeError(HttpServletResponse response, com.pw.common.exception.ErrorCode errorCode, String message)
            throws IOException {
        response.setStatus(errorCode.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail(errorCode, message)));
    }

    private static String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header == null || !header.startsWith(PREFIX)) {
            return null;
        }
        String token = header.substring(PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
