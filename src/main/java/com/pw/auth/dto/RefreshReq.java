package com.pw.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 刷新 token 请求。对应 04 §3.1 {@code POST /auth/refresh}。
 */
@Data
@Schema(description = "刷新 token 请求")
public class RefreshReq {

    @NotBlank(message = "缺少刷新凭证，请重新登录")
    @Schema(description = "登录时下发的 refreshToken", requiredMode = Schema.RequiredMode.REQUIRED)
    private String refreshToken;
}
