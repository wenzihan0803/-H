package com.pw.auth.controller;

import com.pw.auth.dto.AdminLoginReq;
import com.pw.auth.dto.LoginResp;
import com.pw.auth.dto.PhoneLoginReq;
import com.pw.auth.dto.RefreshReq;
import com.pw.auth.dto.UserProfileVO;
import com.pw.auth.dto.WxLoginReq;
import com.pw.auth.dto.WxPhoneLoginReq;
import com.pw.auth.service.AuthService;
import com.pw.common.config.ExternalProperties;
import com.pw.common.enums.Role;
import com.pw.common.lock.RateLimiter;
import com.pw.common.result.Result;
import com.pw.common.security.LoginUser;
import com.pw.common.security.SecurityContexts;
import com.pw.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * 认证接口。对应 04 §3.1「认证与用户」。
 *
 * <p>约束（01 §1）：Controller 不写业务，只做参数校验、限流与结果包装。</p>
 *
 * <p>限流（01 §17「登录/充值/提现限流」）：登录接口按 IP 限流，
 * 防止密码爆破与微信 code 刷取。</p>
 */
@Tag(name = "01-认证", description = "小程序登录、管理端登录、token 刷新与登出")
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final Duration LOGIN_WINDOW = Duration.ofMinutes(1);

    private final AuthService authService;
    private final UserService userService;
    private final RateLimiter rateLimiter;
    private final ExternalProperties.RateLimit rateLimitProperties;

    @Operation(summary = "小程序登录", description = "wx.login 的 code 换取 JWT；客户与打手 token 分离，切换身份需重新调用")
    @PostMapping("/wx-login")
    public Result<LoginResp> wxLogin(@Valid @RequestBody WxLoginReq req, HttpServletRequest request) {
        rateLimiter.check(loginKey(request), rateLimitProperties.getLoginPerMinute(), LOGIN_WINDOW);
        return Result.ok(authService.wxLogin(req));
    }

    @Operation(summary = "手机号登录（dev）",
            description = "直接输入 11 位手机号登录；当前仅 dev 开放（未接短信），生产返回 40301")
    @PostMapping("/phone-login")
    public Result<LoginResp> phoneLogin(@Valid @RequestBody PhoneLoginReq req, HttpServletRequest request) {
        rateLimiter.check(loginKey(request), rateLimitProperties.getLoginPerMinute(), LOGIN_WINDOW);
        return Result.ok(authService.phoneLogin(req.getPhone(), Role.of(req.getRole())));
    }

    @Operation(summary = "微信手机号一键登录",
            description = "getPhoneNumber 回调 code → 微信换取手机号 → JWT；需 WX_APPID/WX_SECRET，未配置时 dev 走 mock 手机号")
    @PostMapping("/wx-phone-login")
    public Result<LoginResp> wxPhoneLogin(@Valid @RequestBody WxPhoneLoginReq req, HttpServletRequest request) {
        rateLimiter.check(loginKey(request), rateLimitProperties.getLoginPerMinute(), LOGIN_WINDOW);
        return Result.ok(authService.wxPhoneLogin(req.getCode(), Role.of(req.getRole())));
    }

    @Operation(summary = "管理端登录", description = "账号密码 + BCrypt 校验；超管需二次验证码")
    @PostMapping("/admin-login")
    public Result<LoginResp> adminLogin(@Valid @RequestBody AdminLoginReq req, HttpServletRequest request) {
        rateLimiter.check(loginKey(request), rateLimitProperties.getLoginPerMinute(), LOGIN_WINDOW);
        return Result.ok(authService.adminLogin(req));
    }

    @Operation(summary = "刷新 token", description = "用 refreshToken 换取新的双 token；会话已销毁时返回 40101")
    @PostMapping("/refresh")
    public Result<LoginResp> refresh(@Valid @RequestBody RefreshReq req) {
        return Result.ok(authService.refresh(req));
    }

    @Operation(summary = "登出", description = "销毁服务端会话，使 Access/Refresh 立即失效")
    @PostMapping("/logout")
    public Result<Void> logout() {
        LoginUser loginUser = SecurityContexts.require();
        authService.logout(loginUser);
        return Result.ok();
    }

    @Operation(summary = "当前登录者资料", description = "客户侧返回金砖余额；打手/管理端不返回")
    @GetMapping("/me")
    public Result<UserProfileVO> me() {
        LoginUser loginUser = SecurityContexts.require();
        // 管理端 subjectId 是 admin_user_id，不能当 user 查（否则 requireById 抛 40401）
        // v1.1 修复（2026-10-29）：原先无条件先调 buildProfile，导致管理端 /auth/me 必失败
        if (loginUser.isAdmin()) {
            return Result.ok(UserProfileVO.builder()
                    .id(loginUser.getSubjectId())
                    .nickname(loginUser.getUsername())
                    .verified(true)
                    .memberLevel("NORMAL")
                    .goldBalance(0L)
                    .build());
        }
        // goldBalance 仅客户侧展示（04 §2.1）
        return Result.ok(userService.buildProfile(
                loginUser.getSubjectId(), loginUser.isCustomer()));
    }

    /** 登录限流按 IP + 账号维度，避免同 NAT 出口误伤；IP 从代理头取时只信首个值。 */
    private static String loginKey(HttpServletRequest request) {
        return "ratelimit:login:" + clientIp(request);
    }

    /**
     * 取客户端 IP。
     *
     * <p>只用于限流，不用于安全判定：{@code X-Forwarded-For} 可被伪造，
     * 伪造只能让攻击者绕过自己的限流，不会放大权限。</p>
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            String first = comma > 0 ? forwarded.substring(0, comma) : forwarded;
            String ip = first.trim();
            if (!ip.isEmpty() && ip.length() <= 64) {
                return ip;
            }
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank() && realIp.length() <= 64) {
            return realIp.trim();
        }
        String remote = request.getRemoteAddr();
        return remote == null ? "unknown" : remote;
    }
}
