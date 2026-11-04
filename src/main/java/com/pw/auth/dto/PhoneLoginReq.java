package com.pw.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 手机号登录请求。对应 {@code POST /auth/phone-login}。
 *
 * <p>v1.2 新增：登录页不再重复选身份（身份已在启动页确定），
 * 用户直接输入 11 位手机号即可登录。</p>
 *
 * <p><b>安全边界</b>：当前阶段未接入短信服务，仅在
 * {@code pw.wechat.mock-login=true} 的开发环境开放；生产环境返回 40301，
 * 必须改走 {@code /auth/wx-phone-login}（微信手机号）或后续的短信验证码链路。</p>
 */
@Data
@Schema(description = "手机号登录请求")
public class PhoneLoginReq {

    @NotBlank(message = "请输入手机号")
    @Pattern(regexp = "1[3-9]\\d{9}", message = "请输入正确的 11 位手机号")
    @Schema(description = "11 位手机号", example = "13800000000",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String phone;

    @NotBlank(message = "请选择登录身份")
    @Pattern(regexp = "CUSTOMER|BOOSTER", message = "身份只能是客户或打手，请重新选择")
    @Schema(description = "登录身份", allowableValues = {"CUSTOMER", "BOOSTER"},
            requiredMode = Schema.RequiredMode.REQUIRED, example = "CUSTOMER")
    private String role;
}
