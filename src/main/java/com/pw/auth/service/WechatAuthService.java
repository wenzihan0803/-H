package com.pw.auth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pw.common.config.ExternalProperties;
import com.pw.common.exception.BizException;
import com.pw.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * 微信登录客户端：code → openid。
 *
 * <p>口径来源：技术规范/01 §2「{@code wx.login} → code → {@code jscode2session} → openid」；
 * /09 §1「微信登录：直接用微信官方 API，AppID 用微信官方测试号即可」。</p>
 *
 * <p><b>dev 降级</b>：{@code pw.wechat.mock-login=true} 且未配置 appid 时，
 * 直接把 code 当作 openid 使用，供自动化测试走 {@code /dev/mock-login}（09 §3）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WechatAuthService {

    private static final String JSCODE2SESSION_URL =
            "https://api.weixin.qq.com/sns/jscode2session?appid={appid}&secret={secret}&js_code={code}&grant_type=authorization_code";
    private static final String ACCESS_TOKEN_URL =
            "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid={appid}&secret={secret}";
    private static final String PHONE_NUMBER_URL =
            "https://api.weixin.qq.com/wxa/business/getuserphonenumber?access_token={token}";
    /** dev 未配置微信凭证时的 mock 手机号（当前联调号码，09 §3 降级口径）。 */
    private static final String MOCK_PHONE = "15035705053";

    private final ExternalProperties.Wechat wechatProperties;
    private final ObjectMapper jsonMapper;
    private final RestClient restClient = RestClient.create();

    /** access_token 内存缓存；多实例部署需换成 Redis 共享。 */
    private volatile String cachedAccessToken;
    private volatile long cachedAccessTokenExpireAt;

    /**
     * 用 code 换取微信登录态。
     *
     * @param code wx.login 返回的临时凭证
     * @return openid / unionid / sessionKey
     * @throws BizException 40102 —— 微信侧返回错误（code 已使用、无效、appid 不匹配等）
     */
    public WxSession jscode2session(String code) {
        // dev 未配置正式 appid 时走 mock：code 即 openid
        if (!wechatProperties.isConfigured()) {
            if (!wechatProperties.isMockLogin()) {
                log.error("微信 appid/secret 未配置且未开启 mock-login，无法登录");
                throw BizException.of(ErrorCode.TOKEN_INVALID, "微信登录未配置，请联系管理员");
            }
            log.warn("[DEV] 微信未配置，mock-login 生效：code 直接作为 openid。生产环境必须关闭该开关");
            return new WxSession("mock_" + code, null, null);
        }

        String body;
        try {
            body = restClient.get()
                    .uri(JSCODE2SESSION_URL, wechatProperties.getAppid(), wechatProperties.getSecret(), code)
                    .retrieve()
                    .body(String.class);
        } catch (Exception ex) {
            log.error("调用微信 jscode2session 网络异常", ex);
            throw BizException.of(ErrorCode.SYSTEM_ERROR, "微信登录服务暂时不可用，请稍后重试");
        }

        // 微信返回可能含 openid 或 errcode；不打印 secret，body 中的 session_key 属敏感信息需脱敏
        log.debug("jscode2session 返回长度={}", body == null ? 0 : body.length());
        return parse(body);
    }

    private WxSession parse(String body) {
        if (body == null || body.isBlank()) {
            throw BizException.of(ErrorCode.TOKEN_INVALID, "微信登录失败，请重新进入小程序");
        }
        try {
            JsonNode node = jsonMapper.readTree(body);
            if (node.hasNonNull("errcode") && node.get("errcode").asInt() != 0) {
                int errcode = node.get("errcode").asInt();
                String errmsg = node.path("errmsg").asText("");
                log.warn("微信登录失败 errcode={} errmsg={}", errcode, errmsg);
                // 40029=invalid code、40163=code been used、45011=频率限制
                String hint = switch (errcode) {
                    case 40029, 40163 -> "微信登录凭证已失效，请重新进入小程序";
                    case 45011 -> "微信登录过于频繁，请稍后重试";
                    default -> "微信登录失败，请重新进入小程序";
                };
                throw BizException.of(ErrorCode.TOKEN_INVALID, hint);
            }
            String openid = node.path("openid").asText(null);
            if (openid == null || openid.isBlank()) {
                throw BizException.of(ErrorCode.TOKEN_INVALID, "微信登录失败，请重新进入小程序");
            }
            return new WxSession(openid,
                    node.path("unionid").asText(null),
                    node.path("session_key").asText(null));
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("解析微信登录响应失败", ex);
            throw BizException.of(ErrorCode.SYSTEM_ERROR, "微信登录异常，请稍后重试");
        }
    }

    /**
     * 微信登录态。
     *
     * @param openid     用户唯一标识，对应 {@code user.openid}
     * @param unionid    开放平台唯一标识，可能为 null
     * @param sessionKey 会话密钥，<b>敏感</b>：只用于解密，禁止落日志、禁止下发前端
     */
    public record WxSession(String openid, String unionid, String sessionKey) {
    }

    // ------------------------------------------------------------------
    // 微信手机号一键登录
    // ------------------------------------------------------------------

    /**
     * 用 {@code getPhoneNumber} 回调的 code 换手机号。
     *
     * <p>微信文档：{@code POST /wxa/business/getuserphonenumber}，需 access_token。
     * 手机号属于敏感信息，只用于落库（加密 + 哈希），不落日志。</p>
     *
     * @param code 手机号快速验证组件回调中的 code
     * @return 11 位手机号
     * @throws BizException 40102 —— 未配置凭证 / 微信侧返回错误
     */
    public String getPhoneNumber(String code) {
        if (!wechatProperties.isConfigured()) {
            if (!wechatProperties.isMockLogin()) {
                log.error("微信 appid/secret 未配置且未开启 mock-login，无法获取手机号");
                throw BizException.of(ErrorCode.TOKEN_INVALID, "微信手机号登录未配置，请改用手机号登录");
            }
            log.warn("[DEV] 微信未配置，手机号一键登录走 mock 手机号。生产环境必须配置 WX_APPID/WX_SECRET");
            return MOCK_PHONE;
        }

        JsonNode node = requestPhoneNumber(code, true);
        String phone = node.path("phone_info").path("phoneNumber").asText(null);
        if (phone == null || phone.isBlank()) {
            phone = node.path("phone_info").path("purePhoneNumber").asText(null);
        }
        if (phone == null || phone.isBlank()) {
            throw BizException.of(ErrorCode.TOKEN_INVALID, "未能获取微信手机号，请重新授权");
        }
        return phone;
    }

    /** 调微信换取手机号；access_token 失效时刷新并重试一次。 */
    private JsonNode requestPhoneNumber(String code, boolean allowRetry) {
        String body;
        try {
            body = restClient.post()
                    .uri(PHONE_NUMBER_URL, accessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("code", code))
                    .retrieve()
                    .body(String.class);
        } catch (Exception ex) {
            log.error("调用微信 getuserphonenumber 网络异常", ex);
            throw BizException.of(ErrorCode.SYSTEM_ERROR, "微信服务暂时不可用，请稍后重试");
        }
        JsonNode node = parseJson(body);
        int errcode = node.path("errcode").asInt(0);
        if (errcode == 40001 || errcode == 40014 || errcode == 42001) {
            // access_token 过期或被顶掉：清缓存后重试一次
            invalidateAccessToken();
            if (allowRetry) {
                return requestPhoneNumber(code, false);
            }
        }
        if (errcode != 0) {
            log.warn("微信手机号获取失败 errcode={} errmsg={}", errcode, node.path("errmsg").asText(""));
            throw BizException.of(ErrorCode.TOKEN_INVALID, "未能获取微信手机号，请重新授权");
        }
        return node;
    }

    private JsonNode parseJson(String body) {
        if (body == null || body.isBlank()) {
            throw BizException.of(ErrorCode.TOKEN_INVALID, "微信返回为空，请重新授权");
        }
        try {
            return jsonMapper.readTree(body);
        } catch (Exception ex) {
            log.error("解析微信响应失败", ex);
            throw BizException.of(ErrorCode.SYSTEM_ERROR, "微信响应异常，请稍后重试");
        }
    }

    /**
     * 取小程序 access_token（内存缓存，提前 5 分钟过期）。
     *
     * <p>单实例缓存足够满足当前量级；多实例部署时应改为 Redis 共享，避免互相顶掉。</p>
     */
    private String accessToken() {
        String token = cachedAccessToken;
        if (token != null && System.currentTimeMillis() < cachedAccessTokenExpireAt) {
            return token;
        }
        synchronized (this) {
            if (cachedAccessToken != null && System.currentTimeMillis() < cachedAccessTokenExpireAt) {
                return cachedAccessToken;
            }
            String body;
            try {
                body = restClient.get()
                        .uri(ACCESS_TOKEN_URL, wechatProperties.getAppid(), wechatProperties.getSecret())
                        .retrieve()
                        .body(String.class);
            } catch (Exception ex) {
                log.error("调用微信 cgi-bin/token 网络异常", ex);
                throw BizException.of(ErrorCode.SYSTEM_ERROR, "微信服务暂时不可用，请稍后重试");
            }
            JsonNode node = parseJson(body);
            String accessToken = node.path("access_token").asText(null);
            if (accessToken == null || accessToken.isBlank()) {
                log.warn("获取 access_token 失败 errcode={} errmsg={}",
                        node.path("errcode").asInt(), node.path("errmsg").asText(""));
                throw BizException.of(ErrorCode.SYSTEM_ERROR, "微信服务暂时不可用，请稍后重试");
            }
            long expiresIn = node.path("expires_in").asLong(7200L);
            cachedAccessToken = accessToken;
            cachedAccessTokenExpireAt = System.currentTimeMillis() + Math.max(120L, expiresIn - 300L) * 1000L;
            return accessToken;
        }
    }

    private void invalidateAccessToken() {
        cachedAccessToken = null;
        cachedAccessTokenExpireAt = 0L;
    }
}
