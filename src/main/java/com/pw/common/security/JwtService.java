package com.pw.common.security;

import com.pw.common.config.JwtProperties;
import com.pw.common.enums.Role;
import com.pw.common.exception.BizException;
import com.pw.common.exception.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/**
 * JWT 签发与解析。
 *
 * <p>claims 口径（技术规范/01 §2）：{@code sub / role / sid / exp}，
 * 额外携带 {@code type}（access|refresh）、{@code boosterId}（打手端）、{@code username}（管理端）。</p>
 *
 * <p>密钥使用 HMAC-SHA256，长度不足 32 字节时启动即失败，避免生产误用弱密钥。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JwtService {

    public static final String CLAIM_TYPE = "type";
    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_SID = "sid";
    public static final String CLAIM_BOOSTER_ID = "boosterId";
    public static final String CLAIM_USERNAME = "username";
    public static final String CLAIM_OPENID = "openid";

    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";

    private static final int MIN_SECRET_BYTES = 32;

    private final JwtProperties properties;
    private SecretKey secretKey;

    @PostConstruct
    void init() {
        String secret = properties.getSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("pw.jwt.secret 未配置或长度不足 32 字节，请通过环境变量 JWT_SECRET 设置");
        }
        this.secretKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        log.info("JWT 初始化完成 issuer={} accessTtl={}s refreshTtl={}s",
                properties.getIssuer(), properties.getAccessTokenTtlSeconds(), properties.getRefreshTokenTtlSeconds());
    }

    /** 新建会话 ID。每次登录/切换身份重新签发，旧会话可单独失效。 */
    public String newSessionId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 签发 Access Token。
     *
     * @param subjectId 客户/打手为 user_id，管理端为 admin_user_id
     * @param role      角色
     * @param sessionId 会话 ID，用于 Redis 会话校验与强制登出
     * @param extras    额外 claims（boosterId / username / openid），可为 null
     */
    public String issueAccessToken(Long subjectId, Role role, String sessionId, Map<String, Object> extras) {
        return issue(subjectId, role, sessionId, TYPE_ACCESS, properties.getAccessTokenTtlSeconds(), extras);
    }

    /** 签发 Refresh Token。与 Access 共用 sessionId，登出时一并失效。 */
    public String issueRefreshToken(Long subjectId, Role role, String sessionId, Map<String, Object> extras) {
        return issue(subjectId, role, sessionId, TYPE_REFRESH, properties.getRefreshTokenTtlSeconds(), extras);
    }

    private String issue(Long subjectId, Role role, String sessionId, String type, long ttlSeconds,
                         Map<String, Object> extras) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(String.valueOf(subjectId))
                .issuer(properties.getIssuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .claim(CLAIM_TYPE, type)
                .claim(CLAIM_ROLE, role.name())
                .claim(CLAIM_SID, sessionId);
        if (extras != null) {
            extras.forEach((key, value) -> {
                if (value != null) {
                    builder.claim(key, value);
                }
            });
        }
        return builder.signWith(secretKey).compact();
    }

    /**
     * 解析并校验 token。
     *
     * @throws BizException 40102（签名错误/格式非法）、40101（已过期）
     */
    public Claims parse(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(secretKey)
                    .requireIssuer(properties.getIssuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (io.jsonwebtoken.ExpiredJwtException ex) {
            throw BizException.of(ErrorCode.UNAUTHORIZED, "登录已过期，请重新登录");
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("JWT 解析失败: {}", ex.getMessage());
            throw BizException.of(ErrorCode.TOKEN_INVALID, "登录凭证无效，请重新登录");
        }
    }

    /** 解析 Refresh Token，类型不符时拒绝（防止用 Access 换 Refresh）。 */
    public Claims parseRefreshToken(String token) {
        Claims claims = parse(token);
        if (!TYPE_REFRESH.equals(claims.get(CLAIM_TYPE, String.class))) {
            throw BizException.of(ErrorCode.TOKEN_INVALID, "刷新凭证无效，请重新登录");
        }
        return claims;
    }

    public long getAccessTtlSeconds() {
        return properties.getAccessTokenTtlSeconds();
    }

    public long getRefreshTtlSeconds() {
        return properties.getRefreshTokenTtlSeconds();
    }

    /** 从 claims 还原登录态。 */
    public LoginUser toLoginUser(Claims claims) {
        Role role = Role.of(claims.get(CLAIM_ROLE, String.class));
        if (role == null) {
            throw BizException.of(ErrorCode.TOKEN_INVALID, "登录凭证角色非法，请重新登录");
        }
        Long subjectId = parseLong(claims.getSubject());
        if (subjectId == null) {
            throw BizException.of(ErrorCode.TOKEN_INVALID, "登录凭证无效，请重新登录");
        }
        return LoginUser.builder()
                .subjectId(subjectId)
                .role(role)
                .sessionId(claims.get(CLAIM_SID, String.class))
                .boosterId(parseLong(asString(claims.get(CLAIM_BOOSTER_ID))))
                .openid(claims.get(CLAIM_OPENID, String.class))
                .username(claims.get(CLAIM_USERNAME, String.class))
                .build();
    }

    private static Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
