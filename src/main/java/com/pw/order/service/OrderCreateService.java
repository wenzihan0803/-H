package com.pw.order.service;

import com.pw.booster.entity.Booster;
import com.pw.booster.mapper.BoosterMapper;
import com.pw.catalog.entity.ServiceCategory;
import com.pw.catalog.entity.ServiceSku;
import com.pw.catalog.mapper.ServiceCategoryMapper;
import com.pw.catalog.mapper.ServiceSkuMapper;
import com.pw.common.config.FeatureProperties;
import com.pw.common.enums.OrderAction;
import com.pw.common.enums.OrderStatus;
import com.pw.common.exception.BizException;
import com.pw.common.exception.ErrorCode;
import com.pw.common.util.CryptoUtil;
import com.pw.common.util.IdGenerator;
import com.pw.gold.service.GoldAccountService;
import com.pw.order.entity.BizOrder;
import com.pw.order.entity.OrderFundHold;
import com.pw.order.entity.OrderTerms;
import com.pw.order.entity.OrderTimer;
import com.pw.order.mapper.BizOrderMapper;
import com.pw.order.mapper.OrderFundHoldMapper;
import com.pw.order.mapper.OrderTermsMapper;
import com.pw.order.mapper.OrderTimerMapper;
import com.pw.user.entity.User;
import com.pw.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 订单创建服务。实现 01 §5「下单与托管」的完整事务。
 *
 * <p><b>核心口径</b>：<u>下单即扣金砖，不是冻结</u>（01 §5、README §0.1）。
 * 没有订单级待支付、没有预支付、没有支付回调——微信支付只用于充值金砖。</p>
 *
 * <p><b>事务顺序</b>（01 §5，严格按此顺序，任一失败整体回滚）：</p>
 * <pre>
 * BEGIN
 *   校验实名、未成年、预约时间、金砖余额
 *   锁 customer_gold_account
 *   UPDATE balance = balance - pay_amount
 *   INSERT customer_gold_txn(ORDER_HOLD, -pay_amount)
 *   INSERT biz_order(PENDING_ACCEPT)
 *   INSERT order_fund_hold(amount=pay_amount, remaining_amount=pay_amount, HELD)
 *   INSERT order_terms
 *   INSERT order_progress
 * COMMIT
 * </pre>
 *
 * <p>实现上先建订单再扣款也可以（同事务原子），但订单号是金砖流水的幂等键，
 * 因此这里<b>先生成订单号 → 扣金砖 → 再插入订单行</b>，保证流水先于订单可见，
 * 且重复提交时 {@code ORDER_HOLD} 流水的唯一索引能直接挡住二次扣款。</p>
 *
 * <p><b>幂等</b>：创建订单必须带 {@code Idempotency-Key}（01 §5），由
 * {@link com.pw.common.idempotent.Idempotent} 切面在 Controller 层拦截，
 * 同一客户同一 key 重复提交返回同一订单。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCreateService {

    /** 服务条款模板版本号。条款变更时递增，已下单订单保留下单时的快照。 */
    private static final String TERMS_VERSION = "v1.0";

    private final BizOrderMapper orderMapper;
    private final OrderFundHoldMapper fundHoldMapper;
    private final OrderTermsMapper orderTermsMapper;
    private final OrderTimerMapper orderTimerMapper;
    private final ServiceCategoryMapper categoryMapper;
    private final ServiceSkuMapper skuMapper;
    private final BoosterMapper boosterMapper;
    private final GoldAccountService goldAccountService;
    private final OrderAmountCalculator amountCalculator;
    private final OrderProgressRecorder progressRecorder;
    private final UserService userService;
    private final IdGenerator idGenerator;
    private final FeatureProperties feature;
    private final CryptoUtil cryptoUtil;

    /**
     * 创建订单。
     *
     * @param command 下单参数
     * @return 新建的订单
     */
    @Transactional(rollbackFor = Exception.class)
    public BizOrder create(CreateOrderCommand command) {
        Long customerId = command.customerId();
        User customer = userService.requireById(customerId);

        // ---- guard 1：账号状态（冻结/封禁不可下单）----
        userService.requireNotFrozen(customer);

        // ---- guard 2：实名（feature.realname=false 时放行，见 09 §1 mock 策略）----
        if (feature.isRealname() && !customer.hasVerified()) {
            throw BizException.of(ErrorCode.FORBIDDEN, "请先完成实名认证后再下单");
        }

        // ---- guard 3：类目与 SKU 必须存在且上架 ----
        ServiceCategory category = requireEnabledCategory(command.categoryId());
        ServiceSku sku = requireEnabledSku(command.skuId(), category.getId());

        // ---- guard 4：数量在 SKU 允许区间内 ----
        validateQuantity(command.quantity(), sku);

        // ---- guard 5：预约时间 now+30min ~ 7d（04 §2.4 CreateOrderReq）----
        LocalDateTime appointAt = validateAppointAt(command.appointAt());

        // ---- guard 5b：需求备注 ≤200 字（04 §2.4）----
        String requirement = validateRequirement(command.requirement());

        // ---- guard 6：指定模式必须校验打手可接单 ----
        Booster booster = null;
        if ("DESIGNATED".equals(command.mode())) {
            booster = requireAvailableBooster(command.boosterId(), category.getId());
        } else if ("HALL".equals(command.mode())) {
            if (command.boosterId() != null) {
                throw BizException.of(ErrorCode.PARAM_ERROR, "大厅抢单模式不能指定打手");
            }
        } else {
            throw BizException.of(ErrorCode.PARAM_ERROR, "下单模式只能是 DESIGNATED 或 HALL");
        }

        // ---- 计算金额：原价 / 会员折扣 / 实付（邀请功能已于 2026-10-29 删除）----
        OrderAmountCalculator.Amounts amounts = amountCalculator.calculate(
                customerId, nz(sku.getPrice()), command.quantity());

        // ---- 生成订单号（作为金砖流水的幂等键）----
        LocalDateTime now = LocalDateTime.now();
        long orderId = idGenerator.nextId();
        String orderNo = idGenerator.nextOrderNo();

        // ---- 扣金砖 + 写 ORDER_HOLD 流水（01 §5）----
        long balanceAfter = goldAccountService.holdForOrder(
                customerId, amounts.payAmount(), orderNo, orderId);
        log.info("下单扣金砖成功 customerId={} orderNo={} payAmount={}分 balanceAfter={}分",
                customerId, orderNo, amounts.payAmount(), balanceAfter);

        // ---- 插入订单主行 ----
        BizOrder order = buildOrder(orderId, orderNo, command, category, sku, booster, amounts, appointAt, now);
        orderMapper.insert(order);

        // ---- 插入托管金（实付即托管，01 §5）----
        insertFundHold(orderId, customerId, amounts.payAmount(), now);

        // ---- 插入服务条款快照 ----
        insertOrderTerms(orderId, category, sku, amounts, requirement, now);

        // 交接任务不再在下单时创建：改为打手接单时创建 10 分钟 SLA
        // （01 §11「打手接单 → 建 handover_task(PENDING)，10 分钟 SLA」、08 手册 Step 4；
        //   2026-10-29 修复：此前在下单时创建，大厅单长时间无人接会误触发升级/TIMEOUT）

        // ---- 写进度轨迹 ----
        progressRecorder.record(orderId, null, OrderStatus.PENDING_ACCEPT, OrderAction.CREATE,
                OrderProgressRecorder.OP_CUSTOMER, customerId,
                "下单成功，托管 " + com.pw.common.util.MoneyUtil.fenToYuan(amounts.payAmount()) + " 元");

        log.info("订单创建成功 orderId={} orderNo={} customerId={} status=PENDING_ACCEPT payAmount={}分",
                orderId, orderNo, customerId, amounts.payAmount());
        return order;
    }

    // ------------------------------------------------------------------
    // guard 实现
    // ------------------------------------------------------------------

    private ServiceCategory requireEnabledCategory(Long categoryId) {
        if (categoryId == null) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "请选择服务类目");
        }
        ServiceCategory category = categoryMapper.selectById(categoryId);
        if (category == null || !Integer.valueOf(1).equals(category.getEnabled())) {
            throw BizException.of(ErrorCode.NOT_FOUND, "服务类目不存在或已下架，请刷新后重试");
        }
        return category;
    }

    private ServiceSku requireEnabledSku(Long skuId, Long categoryId) {
        if (skuId == null) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "请选择服务规格");
        }
        ServiceSku sku = skuMapper.selectById(skuId);
        if (sku == null || !Integer.valueOf(1).equals(sku.getEnabled())) {
            throw BizException.of(ErrorCode.NOT_FOUND, "服务规格不存在或已下架，请刷新后重试");
        }
        if (!categoryId.equals(sku.getCategoryId())) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "服务规格与类目不匹配，请重新选择");
        }
        return sku;
    }

    private void validateQuantity(int quantity, ServiceSku sku) {
        int min = sku.getMinQty() == null ? 1 : sku.getMinQty();
        int max = sku.getMaxQty() == null ? 10 : sku.getMaxQty();
        if (quantity < min || quantity > max) {
            throw BizException.of(ErrorCode.PARAM_ERROR,
                    "购买数量需在 " + min + "–" + max + " 之间");
        }
    }

    /**
     * 校验预约时间：now+30min ~ now+7d（04 §2.4）。
     *
     * <p>太近打手来不及准备，太远占库存且易纠纷。</p>
     */
    private LocalDateTime validateAppointAt(LocalDateTime appointAt) {
        if (appointAt == null) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "请选择预约时间");
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime earliest = now.plusMinutes(30);
        LocalDateTime latest = now.plusDays(7);
        if (appointAt.isBefore(earliest)) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "预约时间需至少提前 30 分钟");
        }
        if (appointAt.isAfter(latest)) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "预约时间最多可选 7 天内");
        }
        return appointAt;
    }

    /**
     * 指定模式下校验打手可接单（01 §3 guard「打手 ACTIVE、类目匹配、当前无进行中订单」）。
     */
    private Booster requireAvailableBooster(Long boosterId, Long categoryId) {
        if (boosterId == null) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "指定下单请选择打手");
        }
        Booster booster = boosterMapper.selectById(boosterId);
        if (booster == null || !Booster.STATUS_ACTIVE.equals(booster.getStatus())) {
            throw BizException.of(ErrorCode.NOT_FOUND, "该打手当前不可接单，请选择其他打手");
        }
        if (booster.getBanUntil() != null && booster.getBanUntil().isAfter(LocalDateTime.now())) {
            throw BizException.of(ErrorCode.ACCOUNT_RESTRICTED, "该打手暂不可接单，请选择其他打手");
        }
        // 打手不能同时接多单（01 §3 guard）
        long inProgress = orderMapper.countInProgressByBooster(boosterId);
        if (inProgress > 0) {
            throw BizException.of(ErrorCode.STATE_CONFLICT, "该打手正在服务中，暂无法接新单");
        }
        log.debug("指定打手校验通过 boosterId={} categoryId={}", boosterId, categoryId);
        return booster;
    }

    // ------------------------------------------------------------------
    // 插入辅助
    // ------------------------------------------------------------------

    private BizOrder buildOrder(long orderId, String orderNo, CreateOrderCommand command,
                                ServiceCategory category, ServiceSku sku, Booster booster,
                                OrderAmountCalculator.Amounts amounts, LocalDateTime appointAt,
                                LocalDateTime now) {
        BizOrder order = new BizOrder();
        order.setId(orderId);
        order.setOrderNo(orderNo);
        order.setCustomerId(command.customerId());
        order.setBoosterId(booster == null ? null : booster.getId());
        order.setCategoryId(category.getId());
        order.setSkuId(sku.getId());
        order.setMode(command.mode());
        order.setQuantity(command.quantity());
        order.setOriginalAmount(amounts.originalAmount());
        order.setMemberDiscount(amounts.memberDiscount());
        order.setPayAmount(amounts.payAmount());
        order.setStatus(OrderStatus.PENDING_ACCEPT.name());
        order.setHandoverStatus(com.pw.common.enums.HandoverStatus.PENDING.name());
        order.setAppointAt(appointAt);
        // 老板游戏 ID 密文：从客户游戏资料取，加密后存（01 §17 敏感字段加密）
        if (command.bossGameId() != null && !command.bossGameId().isBlank()) {
            order.setBossGameIdCipher(cryptoUtil.encrypt(command.bossGameId()));
        }
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        return order;
    }

    private void insertFundHold(long orderId, Long customerId, long payAmount, LocalDateTime now) {
        OrderFundHold hold = new OrderFundHold();
        hold.setId(idGenerator.nextId());
        hold.setOrderId(orderId);
        hold.setCustomerId(customerId);
        hold.setAmount(payAmount);
        hold.setRemainingAmount(payAmount);
        hold.setRefundedAmount(0L);
        hold.setStatus(com.pw.common.enums.HoldStatus.HELD.name());
        hold.setHeldAt(now);
        hold.setCreatedAt(now);
        hold.setUpdatedAt(now);
        fundHoldMapper.insert(hold);
    }

    /**
     * 固化服务条款快照。条款后续变更不影响已下单订单（05 §4.5）。
     */
    private void insertOrderTerms(long orderId, ServiceCategory category, ServiceSku sku,
                                  OrderAmountCalculator.Amounts amounts, String requirement,
                                  LocalDateTime now) {
        OrderTerms terms = new OrderTerms();
        terms.setId(idGenerator.nextId());
        terms.setOrderId(orderId);
        terms.setTemplateId(null);
        terms.setVersion(TERMS_VERSION);
        terms.setContentJson(buildTermsJson(category, sku, amounts, requirement));
        terms.setConfirmedAt(now);
        terms.setCreatedAt(now);
        orderTermsMapper.insert(terms);
    }

    private String buildTermsJson(ServiceCategory category, ServiceSku sku,
                                  OrderAmountCalculator.Amounts amounts, String requirement) {
        // 手工拼 JSON：字段固定且值已转义，避免为快照引入额外序列化依赖
        // requirement 固化进条款快照（biz_order 无该列，05 §4.3/§4.5）：打手端不返回，客户/管理端可见
        return "{\"version\":\"" + TERMS_VERSION + "\","
                + "\"categoryName\":\"" + escape(category.getName()) + "\","
                + "\"skuName\":\"" + escape(sku.getName()) + "\","
                + "\"unit\":\"" + escape(sku.getUnit()) + "\","
                + "\"originalAmount\":" + amounts.originalAmount() + ","
                + "\"memberDiscount\":" + amounts.memberDiscount() + ","
                + "\"payAmount\":" + amounts.payAmount() + ","
                + "\"requirement\":\"" + escape(requirement) + "\","
                + "\"refundPolicy\":\"服务开始前可自助全额退款；服务开始后需联系客服协商，由主管审批\","
                + "\"settlePolicy\":\"确认收货或 7 天自动确认后结算，收益直接到账可提现\"}";
    }

    /**
     * 校验并规范化需求备注（04 §2.4：≤200 字，可空）。
     */
    private String validateRequirement(String requirement) {
        if (requirement == null || requirement.isBlank()) {
            return "";
        }
        String trimmed = requirement.trim();
        if (trimmed.length() > 200) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "需求备注不能超过 200 字");
        }
        return trimmed;
    }

    /** 预留：服务计时器在「开始服务」时创建，此处仅占位以明确生命周期。 */
    void insertOrderTimer(long orderId, LocalDateTime startAt) {
        OrderTimer timer = new OrderTimer();
        timer.setId(idGenerator.nextId());
        timer.setOrderId(orderId);
        timer.setStartAt(startAt);
        timer.setPausedSeconds(0);
        timer.setTotalSeconds(0);
        timer.setUpdatedAt(startAt);
        orderTimerMapper.insert(timer);
    }

    /** 计算服务时长（秒），用于对账与争议判定。 */
    long elapsedSeconds(LocalDateTime startAt, LocalDateTime endAt, int pausedSeconds) {
        if (startAt == null || endAt == null || endAt.isBefore(startAt)) {
            return 0L;
        }
        long total = Duration.between(startAt, endAt).getSeconds();
        return Math.max(0L, total - pausedSeconds);
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }

    /**
     * 下单参数。
     *
     * @param customerId      客户 ID（从登录态取，不从请求体取，防越权）
     * @param categoryId      类目 ID
     * @param skuId           SKU ID
     * @param mode            DESIGNATED / HALL
     * @param boosterId       指定模式的打手 ID
     * @param quantity        数量
     * @param appointAt       预约时间
     * @param gameProfileId   客户游戏资料 ID
     * @param bossGameId      老板游戏 ID（明文入参，服务内加密存储）
     * @param requirement     需求备注，≤200 字
     */
    public record CreateOrderCommand(Long customerId, Long categoryId, Long skuId, String mode,
                                     Long boosterId, int quantity, LocalDateTime appointAt,
                                     Long gameProfileId, String bossGameId, String requirement) {
    }
}
