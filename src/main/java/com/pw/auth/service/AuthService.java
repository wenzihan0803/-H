package com.pw.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pw.admin.entity.AdminUser;
import com.pw.admin.mapper.AdminUserMapper;
import com.pw.auth.dto.AdminLoginReq;
import com.pw.auth.dto.LoginResp;
import com.pw.auth.dto.RefreshReq;
import com.pw.auth.dto.UserProfileVO;
import com.pw.auth.dto.WxLoginReq;
import com.pw.booster.entity.Booster;
import com.pw.booster.mapper.BoosterMapper;
import com.pw.common.config.ExternalProperties;
import com.pw.common.enums.Role;
import com.pw.common.exception.BizException;
import com.pw.common.exception.ErrorCode;
import com.pw.common.security.JwtService;
import com.pw.common.security.LoginUser;
import com.pw.common.security.SessionStore;
import com.pw.user.entity.User;
import com.pw.user.service.UserService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 认证服务：登录、刷新、登出。
 *
 * <p>口径来源：技术规范/01 §2、/04 §1.3、§3.1。</p>
 *
 * <p>核心规则：</p>
 * <ul>
 *   <li><b>客户与打手 token 分离</b>：同一 openid 按所选角色签发不同 token，切换身份重新签发；</li>
 *   <li>Access 2h / Refresh 7d，共用同一 sessionId；</li>
 *   <li>会话写 Redis，登出/封禁时删除即可让未过期 token 立即失效；</li>
 *   <li>管理端超管需二次验证（otp）。</li>
 * </ul>
 *
 * <p>约束（01 §1）：微信/Redis 调用不放在数据库事务内。本类的事务边界只包住用户落库，
 * token 签发与会话写入在事务外完成。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final WechatAuthService wechatAuthService;
    private final UserService userService;
    private final BoosterMapper boosterMapper;
    private final AdminUserMapper adminUserMapper;
    private final JwtService jwtService;
    private final SessionStore sessionStore;
    private final PasswordEncoder passwordEncoder;
    private final ExternalProperties.Wechat wechatProperties;

    /**
     * 小程序登录。对应 04 §3.1 {@code POST /auth/wx-login}。
     *
     * @param req code + role（CUSTOMER | BOOSTER）
     * @return 登录响应，含双 token 与用户资料
     */
    public LoginResp wxLogin(WxLoginReq req) {
        Role role = Role.of(req.getRole());
        if (role != Role.CUSTOMER && role != Role.BOOSTER) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "身份只能是客户或打手，请重新选择");
        }

        WechatAuthService.WxSession session = wechatAuthService.jscode2session(req.getCode());
        User user = userService.getOrCreateByOpenid(session.openid(), session.unionid());
        userService.requireNotFrozen(user);

        // 打手身份需要已入驻；未入驻时返回 boosterStatus 让前端引导申请，不阻断登录
        Booster booster = null;
        if (role == Role.BOOSTER) {
            booster = boosterMapper.selectByUserId(user.getId());
        }

        // goldBalance 仅客户侧展示（04 §2.1）
        UserProfileVO profile = userService.buildProfile(user.getId(), role == Role.CUSTOMER);

        Map<String, Object> extras = new HashMap<>();
        extras.put(JwtService.CLAIM_OPENID, session.openid());
        if (booster != null) {
            extras.put(JwtService.CLAIM_BOOSTER_ID, String.valueOf(booster.getId()));
        }

        LoginResp resp = issueTokens(user.getId(), role, extras, profile);
        if (role == Role.BOOSTER) {
            resp.setBoosterStatus(booster == null ? null : booster.getStatus());
        }
        log.info("小程序登录成功 userId={} role={} boosterStatus={}",
                user.getId(), role, resp.getBoosterStatus());
        return resp;
    }

    /**
     * dev 专用登录：直接用 openid 签发 token，跳过微信。
     *
     * <p>口径来源：09 §3 {@code POST /dev/mock-login}。仅 dev profile 注册，
     * 生产构建不包含该 Controller，且 {@code mock-login=false} 时拒绝。</p>
     *
     * @param openid 测试用 openid，如 {@code dev_openid_customer_01}（与种子数据对应）
     * @param role   CUSTOMER | BOOSTER
     */
    public LoginResp mockLogin(String openid, String role) {
        if (!wechatProperties.isMockLogin()) {
            throw BizException.of(ErrorCode.FORBIDDEN, "当前环境不允许模拟登录");
        }
        if (openid == null || openid.isBlank()) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "请提供 openid");
        }
        Role targetRole = Role.of(role);
        if (targetRole != Role.CUSTOMER && targetRole != Role.BOOSTER) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "身份只能是客户或打手，请重新选择");
        }

        User user = userService.getOrCreateByOpenid(openid, null);
        userService.requireNotFrozen(user);

        Booster booster = targetRole == Role.BOOSTER ? boosterMapper.selectByUserId(user.getId()) : null;
        UserProfileVO profile = userService.buildProfile(user.getId(), targetRole == Role.CUSTOMER);

        Map<String, Object> extras = new HashMap<>();
        extras.put(JwtService.CLAIM_OPENID, openid);
        if (booster != null) {
            extras.put(JwtService.CLAIM_BOOSTER_ID, String.valueOf(booster.getId()));
        }

        LoginResp resp = issueTokens(user.getId(), targetRole, extras, profile);
        if (targetRole == Role.BOOSTER) {
            resp.setBoosterStatus(booster == null ? null : booster.getStatus());
        }
        log.warn("[DEV] 模拟登录成功 userId={} role={}，生产环境必须关闭 mock-login", user.getId(), targetRole);
        return resp;
    }

    /**
     * 手机号登录。对应 {@code POST /auth/phone-login}。
     *
     * <p><b>仅 dev 开放</b>：当前没有短信服务，手机号直登等于任意号码可登录，
     * 因此用 {@code pw.wechat.mock-login} 作为开关（与 mock-login 同源）。
     * 生产环境调用返回 40301，必须走微信一键登录或后续短信验证码。</p>
     *
     * @param phone 11 位手机号
     * @param role  登录身份
     */
    public LoginResp phoneLogin(String phone, Role role) {
        if (!wechatProperties.isMockLogin()) {
            throw BizException.of(ErrorCode.FORBIDDEN, "手机号登录仅限开发环境，请使用微信一键登录");
        }
        return phoneLoginInternal(phone, role, "dev-phone");
    }

    /**
     * 微信手机号一键登录。对应 {@code POST /auth/wx-phone-login}。
     *
     * <p>流程：{@code getPhoneNumber} 的 code → 微信换取手机号 → 查/建用户 → 签发 JWT。
     * 未配置 {@code WX_APPID/WX_SECRET} 时，dev 走 mock 手机号（09 §3）。</p>
     *
     * @param code getPhoneNumber 回调中的 code
     * @param role 登录身份
     */
    public LoginResp wxPhoneLogin(String code, Role role) {
        String phone = wechatAuthService.getPhoneNumber(code);
        return phoneLoginInternal(phone, role, "wx-phone");
    }

    /** 手机号登录公共链路：校验身份 → 查/建用户 → 签发双 token。 */
    private LoginResp phoneLoginInternal(String phone, Role role, String source) {
        if (role != Role.CUSTOMER && role != Role.BOOSTER) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "身份只能是客户或打手，请重新选择");
        }
        if (phone == null || !phone.matches("1[3-9]\\d{9}")) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "请输入正确的 11 位手机号");
        }

        User user = userService.getOrCreateByPhone(phone);
        userService.requireNotFrozen(user);

        Booster booster = role == Role.BOOSTER ? boosterMapper.selectByUserId(user.getId()) : null;
        // 打手入口已对用户开放：没有打手档案的账号不能进打手端，否则会拿着
        // role=BOOSTER 的 token 进去后每个接口都报「打手资料未初始化」。
        if (role == Role.BOOSTER && booster == null) {
            throw BizException.of(ErrorCode.ACCOUNT_RESTRICTED,
                    "该账号还不是打手，请先联系客服申请入驻");
        }
        UserProfileVO profile = userService.buildProfile(user.getId(), role == Role.CUSTOMER);

        Map<String, Object> extras = new HashMap<>();
        extras.put(JwtService.CLAIM_OPENID, user.getOpenid());
        if (booster != null) {
            extras.put(JwtService.CLAIM_BOOSTER_ID, String.valueOf(booster.getId()));
        }

        LoginResp resp = issueTokens(user.getId(), role, extras, profile);
        if (role == Role.BOOSTER) {
            resp.setBoosterStatus(booster == null ? null : booster.getStatus());
        }
        log.info("{}登录成功 userId={} role={} boosterStatus={}",
                source, user.getId(), role, resp.getBoosterStatus());
        return resp;
    }

    /**
     * 管理端登录。对应 04 §3.1 {@code POST /auth/admin-login}。
     *
     * <p>流程（01 §2）：账号密码 + BCrypt → <b>超管二次验证</b> → JWT。</p>
     *
     * <p>安全要点：</p>
     * <ul>
     *   <li>账号不存在与密码错误返回同一提示，避免账号枚举；</li>
     *   <li>DISABLED 账号返回 40302；</li>
     *   <li>登录成功回写 {@code last_login_at}。</li>
     * </ul>
     */
    @Transactional(rollbackFor = Exception.class)
    public LoginResp adminLogin(AdminLoginReq req) {
        AdminUser admin = adminUserMapper.selectOne(new LambdaQueryWrapper<AdminUser>()
                .eq(AdminUser::getUsername, req.getUsername())
                .last("LIMIT 1"));

        if (admin == null || !passwordEncoder.matches(req.getPassword(), admin.getPasswordHash())) {
            log.warn("管理端登录失败 username={}", req.getUsername());
            throw BizException.of(ErrorCode.UNAUTHORIZED, "账号或密码有误，请检查后重新登录");
        }
        if (!admin.isEnabled()) {
            throw BizException.of(ErrorCode.ACCOUNT_RESTRICTED, "账号已停用，请联系超管恢复");
        }

        Role role = Role.of(admin.getRole());
        if (role == null || !role.isAdminSide()) {
            log.error("管理端账号角色非法 adminId={} role={}", admin.getId(), admin.getRole());
            throw BizException.of(ErrorCode.FORBIDDEN, "账号角色异常，请联系超管处理");
        }

        // 超管二次验证（01 §2、04 §1.3）
        if (role == Role.SUPER_ADMIN) {
            verifySuperAdminOtp(req.getOtp());
        }

        AdminUser update = new AdminUser();
        update.setId(admin.getId());
        update.setLastLoginAt(LocalDateTime.now());
        adminUserMapper.updateById(update);

        // 管理端不下发金砖余额，profile 只带账号信息
        UserProfileVO profile = UserProfileVO.builder()
                .id(admin.getId())
                .nickname(admin.getUsername())
                .verified(true)
                .memberLevel("NORMAL")
                .goldBalance(0L)
                .build();

        Map<String, Object> extras = new HashMap<>();
        extras.put(JwtService.CLAIM_USERNAME, admin.getUsername());

        LoginResp resp = issueTokens(admin.getId(), role, extras, profile);
        log.info("管理端登录成功 adminId={} role={}", admin.getId(), role);
        return resp;
    }

    /**
     * 超管二次验证。
     *
     * <p><b>本期为形式校验</b>（01 §2 要求「超管二次验证」，但未指定实现）：
     * otp 必填且为 6 位数字。这样 dev 联调不被阻断，同时保留调用链与错误码。</p>
     *
     * <p><b>上线前必须替换为真实 TOTP 校验</b>（如 Google Authenticator + 管理员密钥），
     * 否则超管账号缺少实际的第二因子保护。这是已知缺口，不是最终实现。</p>
     */
    private void verifySuperAdminOtp(String otp) {
        if (otp == null || otp.isBlank()) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "超管登录需二次验证，请输入 6 位动态验证码");
        }
        if (!otp.matches("^\\d{6}$")) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "验证码为 6 位数字，请检查后重新输入");
        }
    }

    /**
     * 刷新 token。对应 04 §3.1 {@code POST /auth/refresh}。
     *
     * <p>沿用原 sessionId：刷新不产生新会话，避免旧 Access 在有效期内仍可用。
     * 会话已被销毁（登出/封禁）时刷新失败，返回 40101。</p>
     */
    public LoginResp refresh(RefreshReq req) {
        Claims claims = jwtService.parseRefreshToken(req.getRefreshToken());
        LoginUser loginUser = jwtService.toLoginUser(claims);

        if (!sessionStore.exists(loginUser.getSessionId())) {
            throw BizException.of(ErrorCode.UNAUTHORIZED, "登录已失效，请重新登录");
        }

        UserProfileVO profile = loadProfile(loginUser);

        Map<String, Object> extras = new HashMap<>();
        if (loginUser.getOpenid() != null) {
            extras.put(JwtService.CLAIM_OPENID, loginUser.getOpenid());
        }
        if (loginUser.getBoosterId() != null) {
            extras.put(JwtService.CLAIM_BOOSTER_ID, String.valueOf(loginUser.getBoosterId()));
        }
        if (loginUser.getUsername() != null) {
            extras.put(JwtService.CLAIM_USERNAME, loginUser.getUsername());
        }

        LoginResp resp = issueTokensWithSession(loginUser.getSubjectId(), loginUser.getRole(),
                loginUser.getSessionId(), extras, profile);
        if (loginUser.isBooster() && loginUser.getBoosterId() != null) {
            Booster booster = boosterMapper.selectById(loginUser.getBoosterId());
            resp.setBoosterStatus(booster == null ? null : booster.getStatus());
        }
        return resp;
    }

    /**
     * 登出。对应 04 §3.1 {@code POST /auth/logout}。
     *
     * <p>删除 Redis 会话，使 Access / Refresh 立即失效（01 §2「登出/封禁失效」）。</p>
     */
    public void logout(LoginUser loginUser) {
        sessionStore.destroy(loginUser.getSessionId());
        log.info("登出成功 subjectId={} role={}", loginUser.getSubjectId(), loginUser.getRole());
    }

    /**
     * 按登录态装配资料。刷新场景下避免重复查询角色无关数据。
     */
    private UserProfileVO loadProfile(LoginUser loginUser) {
        if (loginUser.isAdmin()) {
            return UserProfileVO.builder()
                    .id(loginUser.getSubjectId())
                    .nickname(loginUser.getUsername())
                    .verified(true)
                    .memberLevel("NORMAL")
                    .goldBalance(0L)
                    .build();
        }
        return userService.buildProfile(loginUser.getSubjectId(), loginUser.isCustomer());
    }

    /** 签发双 token 并创建新会话。 */
    private LoginResp issueTokens(Long subjectId, Role role, Map<String, Object> extras, UserProfileVO profile) {
        String sessionId = jwtService.newSessionId();
        sessionStore.create(sessionId, role.name() + ":" + subjectId);
        // 绑定用户会话，供封号时强制下线
        sessionStore.bindUserSession(subjectId, role.name(), sessionId);
        return issueTokensWithSession(subjectId, role, sessionId, extras, profile);
    }

    /** 用指定 sessionId 签发双 token（刷新场景复用原会话）。 */
    private LoginResp issueTokensWithSession(Long subjectId, Role role, String sessionId,
                                            Map<String, Object> extras, UserProfileVO profile) {
        String accessToken = jwtService.issueAccessToken(subjectId, role, sessionId, extras);
        String refreshToken = jwtService.issueRefreshToken(subjectId, role, sessionId, extras);
        return LoginResp.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .expiresIn(jwtService.getAccessTtlSeconds())
                .role(role.name())
                .profile(profile)
                .build();
    }
}
