package com.pw.file.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

/**
 * 文件上传响应。
 *
 * <p>只返回「怎么取到这张图」的最小信息：objectKey 用于提交给业务接口（如履约凭证），
 * url 用于前端直接展示。不做文件表、不做多版本，保持简单。</p>
 */
public final class FileDto {

    private FileDto() {
    }

    @Data
    @Builder
    @Schema(description = "上传结果")
    public static class UploadResult {

        /** 存储标识，如 {@code 2026/10/3f2a...c1.jpg}；业务表只存它 */
        private String objectKey;

        /** 可直接访问的地址（按当前请求的域名拼接，真机/局域网自适应） */
        private String url;

        /** 字节数 */
        private Long size;

        /** SHA-256，十六进制；用于去重与凭证校验 */
        private String hash;

        /** image/jpeg、image/png、image/webp */
        private String contentType;
    }
}
