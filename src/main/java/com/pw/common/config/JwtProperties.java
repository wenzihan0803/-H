package com.pw.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 配置。对应 {@code application.yml} 的 {@code pw.jwt.*}。
 *
 * <p>口径来源：技术规范/01 §2 —— Access 2h、Refresh 7d，Redis 存 {@code session:{sid}}。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "pw.jwt")
public class JwtProperties {

    /** HMAC-SHA 密钥，长度必须 >= 32 字节；生产由环境变量 JWT_SECRET 覆盖 */
    private String secret;

    /** Access token 有效期（秒），默认 2 小时 */
    private long accessTokenTtlSeconds = 7200L;

    /** Refresh token 有效期（秒），默认 7 天 */
    private long refreshTokenTtlSeconds = 604800L;

    private String issuer = "pw-server";
}
