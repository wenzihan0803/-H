package com.pw.common.security;

import com.pw.common.config.JwtProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis 会话存储。
 *
 * <p>口径来源：技术规范/01 §2 —— 「Redis 存 {@code session:{sid}}，登出/封禁失效」。</p>
 *
 * <p>JWT 本身无状态、无法主动吊销，因此引入服务端会话：
 * 登出、封号、切换身份时删除对应 key，后续请求即使持有未过期 token 也会被拒。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionStore {

    private static final String KEY_PREFIX = "session:";

    private final StringRedisTemplate redisTemplate;
    private final JwtProperties jwtProperties;

    /**
     * 创建会话。TTL 取 Refresh 有效期（7 天），Access 过期后会话仍然有效以便刷新。
     *
     * @param sessionId 会话 ID
     * @param value     会话内容，格式 {@code role:subjectId}，便于运维排查
     */
    public void create(String sessionId, String value) {
        redisTemplate.opsForValue().set(KEY_PREFIX + sessionId, value,
                Duration.ofSeconds(jwtProperties.getRefreshTokenTtlSeconds()));
    }

    /** 会话是否存在且未失效。 */
    public boolean exists(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + sessionId));
    }

    /** 销毁会话（登出 / 封禁 / 切换身份）。 */
    public void destroy(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        redisTemplate.delete(KEY_PREFIX + sessionId);
        log.debug("会话已销毁 sid={}", sessionId);
    }

    /**
     * 封禁某用户的全部会话。
     *
     * <p>按 sessionId 无法反查用户，故封禁场景由调用方持有 sid 列表，
     * 或依赖单会话策略：一个用户同一角色只保留最新 sid（登录时覆盖）。</p>
     */
    public String userSessionKey(Long subjectId, String role) {
        return "user-session:" + role + ":" + subjectId;
    }

    /** 记录用户当前会话，用于强制下线（封号场景）。 */
    public void bindUserSession(Long subjectId, String role, String sessionId) {
        redisTemplate.opsForValue().set(userSessionKey(subjectId, role), sessionId,
                Duration.ofSeconds(jwtProperties.getRefreshTokenTtlSeconds()));
    }

    /** 强制下线某用户某角色的全部登录态。 */
    public void revokeUserSession(Long subjectId, String role) {
        String key = userSessionKey(subjectId, role);
        String sessionId = redisTemplate.opsForValue().get(key);
        redisTemplate.delete(key);
        if (sessionId != null) {
            destroy(sessionId);
            log.info("已强制下线 subjectId={} role={} sid={}", subjectId, role, sessionId);
        }
    }
}
