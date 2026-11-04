package com.pw.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 微信手机号一键登录请求。对应 {@code POST /auth/wx-phone-login}。
 *
 * <p>流程：{@code <button open-type="getPhoneNumber">} → {@code e.detail.code}
 * → 微信 {@code /wxa/business/getuserphonenumber} → 手机号 → 查/建用户 → 签发 JWT。</p>
 *
 * <p><b>前置条件</b>：需要后端配置 {@code WX_APPID} / {@code WX_SECRET}，
 * 且小程序主体已开通「手机号快速验证」。未配置时 dev 环境走 mock 手机号（见 09 §3）。</p>
 */
@Data
@Schema(description = "微信手机号一键登录请求")
public class WxPhoneLoginReq {

    @NotBlank(message = "缺少微信手机号凭证，请重试")
    @Schema(description = "getPhoneNumber 回调中的 code",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String code;

    @NotBlank(message = "请选择登录身份")
    @Pattern(regexp = "CUSTOMER|BOOSTER", message = "身份只能是客户或打手，请重新选择")
    @Schema(description = "登录身份", allowableValues = {"CUSTOMER", "BOOSTER"},
            requiredMode = Schema.RequiredMode.REQUIRED, example = "CUSTOMER")
    private String role;
}
