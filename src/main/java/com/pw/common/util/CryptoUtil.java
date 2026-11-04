package com.pw.common.util;

import com.pw.common.config.CryptoProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 敏感字段加解密。
 *
 * <p>口径来源：技术规范/01 §17、/05 §1「手机号、身份证、老板游戏 ID 加密；接口脱敏」。</p>
 *
 * <ul>
 *   <li>加密：AES-256-GCM，随机 IV，输出 Base64(IV ‖ ciphertext ‖ tag)，用于 *_cipher 字段；</li>
 *   <li>哈希：SHA-256，用于 *_hash 字段的等值检索（如按手机号/身份证查重）；</li>
 *   <li>密钥来自 {@code pw.crypto.secret-key}，生产必须用环境变量/KMS 覆盖。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CryptoUtil {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CryptoProperties properties;
    private SecretKeySpec secretKey;

    @PostConstruct
    void init() {
        String secret = properties.getSecretKey();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("pw.crypto.secret-key 未配置或长度不足 32 字节，请通过环境变量 CRYPTO_KEY 设置");
        }
        // 取密钥材料前 32 字节作为 AES-256 密钥
        byte[] keyBytes = new byte[32];
        byte[] src = secret.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(src, 0, keyBytes, 0, Math.min(src.length, 32));
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }

    /** 加密明文，返回 Base64。明文为空时返回 null。 */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return null;
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception ex) {
            throw new IllegalStateException("敏感字段加密失败", ex);
        }
    }

    /** 解密 Base64 密文，返回明文。入参为空时返回 null。 */
    public String decrypt(String cipherBase64) {
        if (cipherBase64 == null || cipherBase64.isEmpty()) {
            return null;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(cipherBase64);
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            byte[] ciphertext = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("敏感字段解密失败", ex);
        }
    }

    /**
     * 计算 SHA-256 哈希（小写 hex），用于等值检索字段 *_hash。
     *
     * <p>注意：GCM 加密带随机 IV，同一明文每次密文不同，无法用于查重；
     * 需要按手机号/身份证检索时用本哈希。</p>
     */
    public String sha256(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(plaintext.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 计算失败", ex);
        }
    }
}
