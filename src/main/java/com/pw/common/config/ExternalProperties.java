package com.pw.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 外部服务配置：微信、支付、IM、文件。
 *
 * <p>口径来源：技术规范/09-本地开发基线与Mock策略.md §1 外部依赖处置决策。</p>
 */
public class ExternalProperties {

    /** 微信小程序配置（{@code pw.wechat.*}）。dev 用微信官方测试号即可真登录。 */
    @Data
    @Component
    @ConfigurationProperties(prefix = "pw.wechat")
    public static class Wechat {
        private String appid = "";
        private String secret = "";
        /** dev 专用：允许 openid 直登，供自动化测试 */
        private boolean mockLogin = false;

        public boolean isConfigured() {
            return appid != null && !appid.isBlank() && secret != null && !secret.isBlank();
        }
    }

    /**
     * 微信支付配置（{@code pw.pay.*}）。
     *
     * <p>{@code mode=simulate} 时不调微信，用 {@code POST /dev/recharge-sim} 模拟到账；
     * {@code mode=wechat} 时走 APIv3 JSAPI（上线前联调）。</p>
     */
    @Data
    @Component
    @ConfigurationProperties(prefix = "pw.pay")
    public static class Pay {
        /** simulate | wechat */
        private String mode = "simulate";
        private String mchId = "";
        private String apiV3Key = "";
        private String notifyUrl = "";
        private String merchantSerialNumber = "";
        private String privateKeyPath = "";

        public boolean isSimulate() {
            return !"wechat".equalsIgnoreCase(mode);
        }
    }

    /** 腾讯云 IM 配置（{@code pw.im.*}）。dev 降级为站内信，{@code /im/token} 返回 501。 */
    @Data
    @Component
    @ConfigurationProperties(prefix = "pw.im")
    public static class Im {
        private boolean enabled = false;
        private long sdkAppId = 0L;
        private String secretKey = "";
    }

    /**
     * 文件存储配置（{@code pw.file.*}）。
     *
     * <p>dev 用 {@code storage=local}，文件存 {@code data/uploads/}，
     * 签名 URL 换成带 token 的本地下载路径；哈希校验照常（09 §1）。</p>
     */
    @Data
    @Component
    @ConfigurationProperties(prefix = "pw.file")
    public static class FileStorage {
        /** local | cos */
        private String storage = "local";
        private String localDir = "data/uploads";
        private String publicBaseUrl = "http://localhost:8080/api/v1/files";
        /** 读取用签名 URL 有效期（秒），04 §2.4 OrderEvidence.url 为 10 分钟 */
        private long signTtlSeconds = 600L;
        private long maxSizeMb = 10L;
        private String cosSecretId = "";
        private String cosSecretKey = "";
        private String cosBucket = "";
        private String cosRegion = "";

        public boolean isLocal() {
            return !"cos".equalsIgnoreCase(storage);
        }
    }

    /** 限流配置（{@code pw.ratelimit.*}）。 */
    @Data
    @Component
    @ConfigurationProperties(prefix = "pw.ratelimit")
    public static class RateLimit {
        private int loginPerMinute = 10;
        private int rechargePerMinute = 10;
        private int withdrawPerMinute = 5;
    }
}
