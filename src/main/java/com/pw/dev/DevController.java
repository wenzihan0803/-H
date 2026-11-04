package com.pw.dev;

import com.pw.auth.dto.LoginResp;
import com.pw.auth.service.AuthService;
import com.pw.booster.entity.Booster;
import com.pw.booster.entity.BoosterEarningAccount;
import com.pw.booster.entity.BoosterGameAccount;
import com.pw.booster.entity.BoosterSchedule;
import com.pw.booster.mapper.BoosterEarningAccountMapper;
import com.pw.booster.mapper.BoosterGameAccountMapper;
import com.pw.booster.mapper.BoosterMapper;
import com.pw.booster.mapper.BoosterScheduleMapper;
import com.pw.common.exception.BizException;
import com.pw.common.exception.ErrorCode;
import com.pw.common.result.Result;
import com.pw.common.util.IdGenerator;
import com.pw.job.OrderScheduledJobs;
import com.pw.order.entity.BizOrder;
import com.pw.order.mapper.BizOrderMapper;
import com.pw.payment.entity.RechargeOrder;
import com.pw.payment.service.RechargeCreditService;
import com.pw.user.entity.User;
import com.pw.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * dev 专用接口。对应技术规范/09 §3「dev-only 接口（生产 profile 下必须 404）」。
 *
 * <p><b>三重保护</b>，缺一不可：</p>
 * <ol>
 *   <li>{@code @Profile("dev")}：非 dev profile 时 Bean 根本不注册，接口 404；</li>
 *   <li>启动时打 WARNING 日志，提醒运维当前进程带 dev 接口；</li>
 *   <li>09 §3 要求「仅内网」——生产网关不得暴露 /dev/*（部署侧保证，见 06 文档）。</li>
 * </ol>
 *
 * <p>09 §5 上线前必须回补清单第 5 条：<b>下线全部 /dev/* 接口并确认生产 404</b>。</p>
 */
@Slf4j
@Profile("dev")
@Tag(name = "99-DEV 专用", description = "仅 dev profile 生效；生产构建不包含，访问返回 404")
@RestController
@RequestMapping("/dev")
@RequiredArgsConstructor
public class DevController {

    private final AuthService authService;
    private final UserService userService;
    private final RechargeCreditService rechargeCreditService;
    private final BoosterMapper boosterMapper;
    private final BoosterEarningAccountMapper earningAccountMapper;
    private final BoosterGameAccountMapper gameAccountMapper;
    private final BoosterScheduleMapper scheduleMapper;
    private final OrderScheduledJobs scheduledJobs;
    private final BizOrderMapper orderMapper;
    private final IdGenerator idGenerator;

    @PostConstruct
    void warnOnStartup() {
        log.warn("""

                ============================================================
                  警告：当前进程启用了 dev 专用接口 /dev/*
                  包含：模拟充值到账、模拟登录、一键造打手
                  这些接口会直接改动资金与账号数据，禁止暴露到公网。
                  生产构建必须移除本类（@Profile("dev") 已保证非 dev 不注册）。
                ============================================================
                """);
    }

    @Operation(summary = "模拟充值到账",
            description = "直接加金砖 + 写 RECHARGE 流水 + 满 2,000 触发会员申请，等价于微信支付回调成功。09 §1：支付需商户号，dev 走模拟")
    @PostMapping("/recharge-sim")
    @Transactional(rollbackFor = Exception.class)
    public Result<Map<String, Object>> rechargeSim(@Valid @RequestBody RechargeSimReq req) {
        User user = userService.requireById(req.getUserId());

        // 与真实链路完全一致：先建充值单，再走到账，保证流水与单据可追溯
        RechargeOrder order = rechargeCreditService.create(user.getId(), req.getAmount(), req.getPurpose());
        // UUID 防撞：毫秒时间戳在同一毫秒内两次模拟会撞 transaction_id 唯一索引
        String simulatedTransactionId = "SIM-" + java.util.UUID.randomUUID().toString().replace("-", "");
        boolean credited = rechargeCreditService.credit(order, simulatedTransactionId,
                "{\"simulated\":true,\"source\":\"/dev/recharge-sim\"}");

        long balance = userService.buildProfile(user.getId(), true).getGoldBalance();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rechargeId", order.getId());
        data.put("rechargeNo", order.getRechargeNo());
        data.put("amount", order.getAmount());
        data.put("credited", credited);
        data.put("balanceAfter", balance);
        log.warn("[DEV] 模拟充值到账 userId={} amount={}分 balanceAfter={}分",
                user.getId(), order.getAmount(), balance);
        return Result.ok(data);
    }

    @Operation(summary = "模拟登录",
            description = "用 openid 直接签发 JWT，供自动化测试。09 §3。种子 openid：dev_openid_customer_01/02、dev_openid_booster_01/02/03")
    @PostMapping("/mock-login")
    public Result<LoginResp> mockLogin(@Valid @RequestBody MockLoginReq req) {
        return Result.ok(authService.mockLogin(req.getOpenid(), req.getRole()));
    }

    @Operation(summary = "一键造 ACTIVE 打手",
            description = "创建用户 + ACTIVE 打手 + 游戏账号 + 收益账户 + 在线排班，跳过入驻六道关。09 §1：六道关先 mock")
    @PostMapping("/booster/seed")
    @Transactional(rollbackFor = Exception.class)
    public Result<Map<String, Object>> seedBooster(@Valid @RequestBody SeedBoosterReq req) {
        // 1. 用户
        User user = userService.getOrCreateByOpenid(req.getOpenid(), null);
        userService.initAccounts(user.getId());

        Booster existing = boosterMapper.selectByUserId(user.getId());
        if (existing != null) {
            throw BizException.of(ErrorCode.DUPLICATE_SUBMIT,
                    "该 openid 已是打手（id=" + existing.getId() + "），无需重复创建");
        }

        LocalDateTime now = LocalDateTime.now();

        // 2. 打手主记录，直接 ACTIVE
        Booster booster = new Booster();
        booster.setId(idGenerator.nextId());
        booster.setUserId(user.getId());
        booster.setStatus(Booster.STATUS_ACTIVE);
        booster.setLevel(1);
        booster.setServiceScore(new java.math.BigDecimal("5.00"));
        booster.setCompleteRate(new java.math.BigDecimal("100.00"));
        booster.setActivatedAt(now);
        boosterMapper.insert(booster);

        // 3. 收益账户（0 起）
        BoosterEarningAccount earning = new BoosterEarningAccount();
        earning.setBoosterId(booster.getId());
        earning.setFrozen(0L);
        earning.setAvailable(0L);
        earning.setTotal(0L);
        earning.setVersion(0);
        earningAccountMapper.insert(earning);

        // 4. 游戏账号
        BoosterGameAccount account = new BoosterGameAccount();
        account.setId(idGenerator.nextId());
        account.setBoosterId(booster.getId());
        account.setGameId(req.getGameId());
        account.setAccountId(req.getAccountId());
        account.setServer(req.getServer());
        account.setStatus(BoosterGameAccount.STATUS_ACTIVE);
        account.setVerifiedAt(now);
        gameAccountMapper.insert(account);

        // 5. 在线排班
        BoosterSchedule schedule = new BoosterSchedule();
        schedule.setBoosterId(booster.getId());
        schedule.setStatus(BoosterSchedule.STATUS_ONLINE);
        schedule.setSlotsJson("[{\"day\":\"MON\",\"start\":\"09:00\",\"end\":\"23:00\"},"
                + "{\"day\":\"TUE\",\"start\":\"09:00\",\"end\":\"23:00\"},"
                + "{\"day\":\"WED\",\"start\":\"09:00\",\"end\":\"23:00\"},"
                + "{\"day\":\"THU\",\"start\":\"09:00\",\"end\":\"23:00\"},"
                + "{\"day\":\"FRI\",\"start\":\"09:00\",\"end\":\"23:00\"},"
                + "{\"day\":\"SAT\",\"start\":\"09:00\",\"end\":\"23:00\"},"
                + "{\"day\":\"SUN\",\"start\":\"09:00\",\"end\":\"23:00\"}]");
        scheduleMapper.insert(schedule);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", user.getId());
        data.put("boosterId", booster.getId());
        data.put("openid", req.getOpenid());
        data.put("status", booster.getStatus());
        data.put("gameAccountId", account.getId());
        log.warn("[DEV] 一键造打手成功 userId={} boosterId={} openid={}",
                user.getId(), booster.getId(), req.getOpenid());
        return Result.ok(data);
    }

    @Operation(summary = "手动触发定时任务",
            description = "立即执行一轮「自动确认」或「交接超时」，避免验证时等 5 分钟/7 天。09 §1：定时任务 dev 走手动触发")
    @PostMapping("/jobs/{jobName}/run")
    public Result<Map<String, Object>> runJob(@PathVariable String jobName) {
        int handled = switch (jobName) {
            case "auto-confirm" -> scheduledJobs.runAutoConfirmOnce();
            case "handover-timeout" -> scheduledJobs.runHandoverTimeoutOnce();
            default -> throw BizException.of(ErrorCode.PARAM_ERROR,
                    "jobName 只能是 auto-confirm 或 handover-timeout");
        };
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("jobName", jobName);
        data.put("handled", handled);
        log.warn("[DEV] 手动触发定时任务 job={} handled={}", jobName, handled);
        return Result.ok(data);
    }

    @Operation(summary = "把订单的自动确认截止时间调到过去",
            description = "用于验证 7 天自动确认，否则需真等 7 天。仅改 confirm_deadline，不动资金与状态")
    @PostMapping("/orders/{orderId}/expire-confirm-deadline")
    @Transactional(rollbackFor = Exception.class)
    public Result<Map<String, Object>> expireConfirmDeadline(@PathVariable Long orderId) {
        BizOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "订单不存在");
        }
        if (!"PENDING_CONFIRM".equals(order.getStatus())) {
            throw BizException.of(ErrorCode.STATE_CONFLICT,
                    "只有 PENDING_CONFIRM 订单可调整截止时间，当前状态 " + order.getStatus());
        }
        LocalDateTime past = LocalDateTime.now().minusMinutes(1);
        order.setConfirmDeadline(past);
        order.setUpdatedAt(LocalDateTime.now());
        orderMapper.updateById(order);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", orderId);
        data.put("confirmDeadline", past);
        log.warn("[DEV] 已将订单自动确认截止时间调至过去 orderId={} deadline={}", orderId, past);
        return Result.ok(data);
    }

    /** 模拟充值请求。09 §3：{@code { userId, amount }}。 */
    @Data
    public static class RechargeSimReq {

        @NotNull(message = "请指定用户 ID")
        private Long userId;

        @NotNull(message = "请指定充值金额")
        @Min(value = 100, message = "充值金额最低 1 元（100 分）")
        private Long amount;

        /** NORMAL / MEMBER，默认 NORMAL */
        private String purpose;
    }

    /** 模拟登录请求。09 §3：{@code { openid, role }}。 */
    @Data
    public static class MockLoginReq {

        @NotBlank(message = "请提供 openid")
        private String openid;

        @NotBlank(message = "请选择登录身份")
        @Pattern(regexp = "CUSTOMER|BOOSTER", message = "身份只能是客户或打手")
        private String role;
    }

    /** 一键造打手请求。 */
    @Data
    public static class SeedBoosterReq {

        @NotBlank(message = "请提供 openid")
        private String openid;

        @NotBlank(message = "请提供游戏 ID")
        private String gameId;

        @NotBlank(message = "请提供游戏账号 ID")
        private String accountId;

        private String server;
    }
}
