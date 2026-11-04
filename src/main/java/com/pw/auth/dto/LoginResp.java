package com.pw.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

/**
 * 登录响应。对应 04 §2.1 {@code interface LoginResp}，字段必须与之完全一致。
 *
 * <pre>
 * {
 *   accessToken, refreshToken, expiresIn(秒), role, profile,
 *   boosterStatus?  // 打手专用
 * }
 * </pre>
 *
 * <p>{@code boosterStatus} 只在 role=BOOSTER 时返回，取 {@code booster.status}；
 * 用于前端判断是否需要引导入驻（04 §3.6 {@code /booster/entry-check} 的前置提示）。</p>
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "登录响应")
public class LoginResp {

    @Schema(description = "访问令牌，2 小时有效", requiredMode = Schema.RequiredMode.REQUIRED)
    private String accessToken;

    @Schema(description = "刷新令牌，7 天有效", requiredMode = Schema.RequiredMode.REQUIRED)
    private String refreshToken;

    @Schema(description = "accessToken 有效期（秒）", example = "7200",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private Long expiresIn;

    @Schema(description = "当前身份",
            allowableValues = {"CUSTOMER", "BOOSTER", "CS", "ADMIN", "SUPER_ADMIN"},
            example = "CUSTOMER", requiredMode = Schema.RequiredMode.REQUIRED)
    private String role;

    @Schema(description = "用户资料", requiredMode = Schema.RequiredMode.REQUIRED)
    private UserProfileVO profile;

    @Schema(description = "打手状态，仅 role=BOOSTER 时返回",
            example = "ACTIVE")
    private String boosterStatus;
}
