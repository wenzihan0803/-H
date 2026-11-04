package com.pw.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 敏感字段加密配置。对应 {@code pw.crypto.*}。
 */
@Data
@Component
@ConfigurationProperties(prefix = "pw.crypto")
public class CryptoProperties {

    /** AES-256-GCM 密钥，长度 >= 32 字节；生产由环境变量 CRYPTO_KEY 覆盖 */
    private String secretKey;
}
