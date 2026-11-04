package com.pw.common.util;

/**
 * 脱敏工具。
 *
 * <p>口径来源：技术规范/01 §17「身份证、手机号、老板 ID 加密；日志脱敏」；
 * /04 §2.1 {@code phoneMasked} 形如 {@code 138****0000}。</p>
 */
public final class MaskUtil {

    private MaskUtil() {
    }

    /** 手机号脱敏：138****0000。 */
    public static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return "***";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    /** 身份证脱敏：保留前 4 后 4。 */
    public static String maskIdCard(String idCard) {
        if (idCard == null || idCard.length() < 8) {
            return "****";
        }
        int keep = 4;
        return idCard.substring(0, keep) + "**********" + idCard.substring(idCard.length() - keep);
    }

    /** 真实姓名脱敏：保留姓，名用 * 代替。 */
    public static String maskRealName(String name) {
        if (name == null || name.isEmpty()) {
            return "*";
        }
        if (name.length() == 1) {
            return name;
        }
        return name.charAt(0) + "*".repeat(name.length() - 1);
    }

    /** 通用字符串脱敏：保留头尾，中间用 * 填充。 */
    public static String mask(String value, int head, int tail) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.length() <= head + tail) {
            return "*".repeat(value.length());
        }
        return value.substring(0, head) + "****" + value.substring(value.length() - tail);
    }

    /** 日志用：只保留前 4 位 + 长度，避免泄露完整敏感值。 */
    public static String forLog(String value) {
        if (value == null || value.isEmpty()) {
            return "null";
        }
        int len = Math.min(4, value.length());
        return value.substring(0, len) + "***(len=" + value.length() + ")";
    }
}
