package com.pw.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理端登录请求。对应 04 §3.1 {@code POST /auth/admin-login}。
 *
 * <p>流程（01 §2）：账号密码 + BCrypt → <b>超管二次验证</b> → JWT。</p>
 *
 * <p>{@code otp} 为超管二次验证码。角色为 SUPER_ADMIN 时必填，
 * CS/ADMIN 忽略该字段。</p>
 */
@Data
@Schema(description = "管理端登录请求")
public class AdminLoginReq {

    @NotBlank(message = "请输入账号")
    @Size(max = 64, message = "账号过长，请检查后重试")
    @Schema(description = "登录账号", requiredMode = Schema.RequiredMode.REQUIRED, example = "admin")
    private String username;

    @NotBlank(message = "请输入密码")
    @Size(max = 128, message = "密码过长，请检查后重试")
    @Schema(description = "登录密码", requiredMode = Schema.RequiredMode.REQUIRED)
    private String password;

    @Schema(description = "超管二次验证码；仅 SUPER_ADMIN 必填", example = "123456")
    private String otp;
}
