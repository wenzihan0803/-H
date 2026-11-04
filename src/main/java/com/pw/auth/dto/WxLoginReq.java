package com.pw.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 小程序登录请求。对应 04 §3.1 {@code POST /auth/wx-login}。
 *
 * <p>流程（01 §2）：{@code wx.login} → code → {@code jscode2session} → openid
 * → 查/建 user → 按所选角色签发 JWT。</p>
 *
 * <p><b>客户与打手 token 分离</b>：同一 openid 用不同 role 登录会得到不同 token，
 * 切换身份需重新调用本接口（README/01 §2）。</p>
 */
@Data
@Schema(description = "小程序登录请求")
public class WxLoginReq {

    @NotBlank(message = "缺少微信登录凭证，请重新进入小程序")
    @Schema(description = "wx.login 返回的 code", requiredMode = Schema.RequiredMode.REQUIRED)
    private String code;

    @NotBlank(message = "请选择登录身份")
    @Pattern(regexp = "CUSTOMER|BOOSTER", message = "身份只能是客户或打手，请重新选择")
    @Schema(description = "登录身份", allowableValues = {"CUSTOMER", "BOOSTER"},
            requiredMode = Schema.RequiredMode.REQUIRED, example = "CUSTOMER")
    private String role;
}
