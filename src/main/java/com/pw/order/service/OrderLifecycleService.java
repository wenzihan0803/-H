package com.pw.order.service;

import com.pw.booster.entity.Booster;
import com.pw.booster.entity.BoosterEarningAccount;
import com.pw.booster.entity.BoosterGameAccount;
import com.pw.booster.mapper.BoosterEarningAccountMapper;
import com.pw.booster.mapper.BoosterGameAccountMapper;
import com.pw.booster.mapper.BoosterMapper;
import com.pw.common.enums.HandoverStatus;
import com.pw.common.enums.HoldStatus;
import com.pw.common.enums.OrderAction;
import com.pw.common.enums.OrderStatus;
import com.pw.common.exception.BizException;
import com.pw.common.exception.ErrorCode;
import com.pw.common.util.MoneyUtil;
import com.pw.order.entity.BizOrder;
import com.pw.order.entity.HandoverTask;
import com.pw.order.entity.OrderEvidence;
import com.pw.order.entity.OrderFundHold;
import com.pw.order.entity.OrderTimer;
import com.pw.order.mapper.BizOrderMapper;
import com.pw.order.mapper.HandoverTaskMapper;
import com.pw.order.mapper.OrderEvidenceMapper;
import com.pw.order.mapper.OrderFundHoldMapper;
import com.pw.order.mapper.OrderTimerMapper;
import com.pw.order.state.OrderTransitionTable;
import com.pw.im.entity.SiteMessage;
import com.pw.im.service.ImService;
import com.pw.settlement.entity.Settlement;
import com.pw.settlement.service.SettlementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 订单生命周期服务：接单 → 交接 → 开始服务 → 申请完单 → 确认 → 结算。
 *
 * <p>口径来源（唯一）：技术规范/01 §3 状态机、§7 确认与结算；README §1、§3；04 §3.6/§3.7。</p>
 *
 * <p><b>所有状态变更统一走 {@link #transit}</b>（01 §3 实现要求）：
 * 查订单 → 校验 from 状态 → 执行 guard → 带条件 UPDATE → 影响行数 0 抛 40901 → 写 order_progress。</p>
 *
 * <p><b>并发安全</b>：资金相关动作（确认结算）先 {@code FOR UPDATE} 锁订单与托管金，
 * 再靠 {@code WHERE status=?} 条件更新兜底。两道防线缺一不可（01 §16）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderLifecycleService {

    private final BizOrderMapper orderMapper;
    private final OrderFundHoldMapper fundHoldMapper;
    private final OrderEvidenceMapper evidenceMapper;
    private final HandoverTaskMapper handoverTaskMapper;
    private final OrderTimerMapper orderTimerMapper;
    private final BoosterMapper boosterMapper;
    private final BoosterGameAccountMapper gameAccountMapper;
    private final BoosterEarningAccountMapper earningAccountMapper;
    private final SettlementService settlementService;
    private final OrderProgressRecorder progressRecorder;
    private final com.pw.common.util.IdGenerator idGenerator;
    private final com.pw.common.lock.RedisLock redisLock;
    private final com.pw.common.config.BusinessProperties business;
    private final ImService imService;

    // ------------------------------------------------------------------
    // 打手侧动作
    // ------------------------------------------------------------------

    /**
     * 打手接单：PENDING_ACCEPT → ACCEPTED。
     *
     * <p>guard（01 §3）：打手 ACTIVE、当前无进行中订单、订单待接单。
     * 指定单只有被指定的打手能接；大厅单任何 ACTIVE 打手都能抢。</p>
     *
     * <p><b>抢单并发两道防线</b>（01 §14）：Redis {@code SET NX} 锁挡掉同一订单的并发竞争，
     * DB 条件更新（{@code WHERE status='PENDING_ACCEPT'}）兜底，锁失效也不会双接。</p>
     *
     * <p><b>交接任务在此刻创建</b>（01 §11「打手接单 → 建 handover_task(PENDING)，10 分钟 SLA」；
     * 2026-10-29 修复：原实现在下单时创建，大厅单长时间无人接会误触发升级/TIMEOUT）。
     * 若打手拒单后订单被再次接单，已有任务会被重置，SLA 对新打手重新计时。</p>
     *
     * @param orderId   订单 ID
     * @param boosterId 打手 ID（从登录态取）
     * @return 接单后的订单
     */
    @Transactional(rollbackFor = Exception.class)
    public BizOrder accept(Long orderId, Long boosterId) {
        // 第一道防线：Redis 锁，同一订单同一时刻只允许一个打手走接单流程
        String lockKey = "lock:order:accept:" + orderId;
        String token = redisLock.tryLock(lockKey, Duration.ofSeconds(5));
        if (token == null) {
            throw BizException.of(ErrorCode.STATE_CONFLICT, "当前抢单人数过多，请稍后再试");
        }
        try {
            return doAccept(orderId, boosterId);
        } finally {
            redisLock.unlock(lockKey, token);
        }
    }

    private BizOrder doAccept(Long orderId, Long boosterId) {
        Booster booster = requireActiveBooster(boosterId);
        BizOrder order = lockOrder(orderId);

        // 指定单校验打手匹配；大厅单校验尚未被抢
        if ("DESIGNATED".equals(order.getMode())) {
            if (!boosterId.equals(order.getBoosterId())) {
                throw BizException.of(ErrorCode.FORBIDDEN, "该订单为指定单，只有被指定的打手可以接单");
            }
        } else if (order.getBoosterId() != null) {
            throw BizException.of(ErrorCode.STATE_CONFLICT, "该订单已被其他打手接走");
        }

        // 打手不能同时接多单（01 §3 guard）
        long inProgress = orderMapper.countInProgressByBooster(boosterId);
        if (inProgress > 0) {
            throw BizException.of(ErrorCode.STATE_CONFLICT, "你有正在进行中的订单，完成后再接新单");
        }

        LocalDateTime now = LocalDateTime.now();
        // 大厅单需要把 boosterId 写入订单；指定单 boosterId 已存在
        int rows = orderMapper.acceptOrder(orderId, OrderStatus.PENDING_ACCEPT.name(),
                OrderStatus.ACCEPTED.name(), boosterId, now);
        if (rows == 0) {
            throw BizException.of(ErrorCode.STATE_CONFLICT, "订单状态已变更，请刷新后重试");
        }

        // 第二步：接单成功即建 10 分钟交接 SLA（重复接单场景重置旧任务）
        createOrResetHandoverTask(orderId, now);

        progressRecorder.record(orderId, OrderStatus.PENDING_ACCEPT, OrderStatus.ACCEPTED,
                OrderAction.ACCEPT, OrderProgressRecorder.OP_BOOSTER, boosterId,
                "打手已接单，等待客服交接老板游戏信息");

        log.info("打手接单成功 orderId={} boosterId={}", orderId, boosterId);
        imService.notify(order.getCustomerId(), SiteMessage.TYPE_ORDER, "订单已被接单",
                "订单 " + order.getOrderNo() + " 已有打手接单，请留意后续交接安排",
                "ORDER", order.getId());
        order.setStatus(OrderStatus.ACCEPTED.name());
        order.setBoosterId(boosterId);
        return order;
    }

    /**
     * 接单时创建交接任务（01 §11）；已存在则重置（拒单后再接单，SLA 对新打手重新计时）。
     */
    private void createOrResetHandoverTask(Long orderId, LocalDateTime now) {
        HandoverTask task = handoverTaskMapper.selectByOrderId(orderId);
        if (task == null) {
            task = new HandoverTask();
            task.setId(idGenerator.nextId());
            task.setOrderId(orderId);
            task.setCsId(null);
            task.setStatus(HandoverStatus.PENDING.name());
            task.setDueAt(now.plusMinutes(business.getHandoverSlaMinutes()));
            task.setEscalateLevel(0);
            task.setCreatedAt(now);
            task.setUpdatedAt(now);
            handoverTaskMapper.insert(task);
            return;
        }
        task.setStatus(HandoverStatus.PENDING.name());
        task.setCsId(null);
        task.setDueAt(now.plusMinutes(business.getHandoverSlaMinutes()));
        task.setEscalateLevel(0);
        task.setUpdatedAt(now);
        handoverTaskMapper.updateById(task);
    }

    /**
     * 打手确认交接：handoverStatus SENT → ACK。
     *
     * <p>打手端只能看到客服下发的 {@code bossGameId} 与 {@code guide}，
     * 看不到客户区服、备注、手机号（04 §3.6）。</p>
     *
     * @return ACK 时间
     */
    @Transactional(rollbackFor = Exception.class)
    public LocalDateTime ackHandover(Long orderId, Long boosterId) {
        BizOrder order = lockOrder(orderId);
        if (!boosterId.equals(order.getBoosterId())) {
            throw BizException.of(ErrorCode.FORBIDDEN, "只能确认自己订单的交接");
        }
        HandoverTask task = handoverTaskMapper.selectByOrderId(orderId);
        if (task == null || !HandoverStatus.SENT.name().equals(task.getStatus())) {
            throw BizException.of(ErrorCode.HANDOVER_ACK_NOT_ALLOWED,
                    "客服尚未发送交接信息，暂不能确认");
        }
        LocalDateTime now = LocalDateTime.now();
        task.setStatus(HandoverStatus.ACK.name());
        task.setAckAt(now);
        task.setUpdatedAt(now);
        handoverTaskMapper.updateById(task);

        order.setHandoverStatus(HandoverStatus.ACK.name());
        order.setUpdatedAt(now);
        orderMapper.updateById(order);

        progressRecorder.recordNoTransition(orderId, OrderStatus.of(order.getStatus()),
                OrderAction.ACCEPT, OrderProgressRecorder.OP_BOOSTER, boosterId, "打手已确认交接信息");
        log.info("交接确认 orderId={} boosterId={}", orderId, boosterId);
        return now;
    }

    /**
     * 开始服务：ACCEPTED → IN_SERVICE。
     *
     * <p>guard（01 §3）：开局照、打手游戏账号 ID、<b>交接已 ACK</b>。</p>
     *
     * @param gameAccountId    打手用于本单的游戏账号 ID
     * @param startEvidenceId  开局照凭证 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public BizOrder startService(Long orderId, Long boosterId, Long gameAccountId, Long startEvidenceId) {
        BizOrder order = lockOrder(orderId);
        requireOrderBooster(order, boosterId);

        // guard：交接必须已 ACK
        if (!HandoverStatus.ACK.name().equals(order.getHandoverStatus())) {
            throw BizException.of(ErrorCode.HANDOVER_ACK_NOT_ALLOWED,
                    "请先确认客服发送的交接信息，再开始服务");
        }
        // guard：游戏账号必须属于该打手且可用
        BoosterGameAccount gameAccount = requireOwnGameAccount(gameAccountId, boosterId);
        // guard：开局照必须存在且属于本单
        requireEvidence(orderId, startEvidenceId, "START", "请先上传开局照");

        LocalDateTime now = LocalDateTime.now();
        int rows = orderMapper.startService(orderId, OrderStatus.ACCEPTED.name(),
                OrderStatus.IN_SERVICE.name(), gameAccount.getId(), now);
        if (rows == 0) {
            throw BizException.of(ErrorCode.STATE_CONFLICT, "订单状态已变更，请刷新后重试");
        }

        // 开始计时（01 §11 服务时长）
        upsertTimer(orderId, now);

        progressRecorder.record(orderId, OrderStatus.ACCEPTED, OrderStatus.IN_SERVICE,
                OrderAction.START_SERVICE, OrderProgressRecorder.OP_BOOSTER, boosterId, "打手已开始服务");
        log.info("开始服务 orderId={} boosterId={} gameAccountId={}", orderId, boosterId, gameAccountId);

        order.setStatus(OrderStatus.IN_SERVICE.name());
        order.setBoosterGameAccountId(gameAccount.getId());
        return order;
    }

    /**
     * 申请完单：IN_SERVICE → PENDING_CONFIRM。
     *
     * <p>guard（01 §3）：开局照 + <b>结束照</b> + 打手账号 ID。
     * 写入 {@code serviceCompletedAt}，{@code confirmDeadline = +7d}（01 §7.1）。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public BizOrder requestComplete(Long orderId, Long boosterId, Long endEvidenceId,
                                    int confirmDays) {
        BizOrder order = lockOrder(orderId);
        requireOrderBooster(order, boosterId);

        // guard：开局照与结束照都必须有
        requireStartEvidence(orderId);
        requireEvidence(orderId, endEvidenceId, "END", "请先上传结束照");
        if (order.getBoosterGameAccountId() == null) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "缺少服务使用的游戏账号信息，请联系客服");
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime deadline = now.plusDays(confirmDays);
        int rows = orderMapper.requestComplete(orderId, OrderStatus.IN_SERVICE.name(),
                OrderStatus.PENDING_CONFIRM.name(), now, deadline);
        if (rows == 0) {
            throw BizException.of(ErrorCode.STATE_CONFLICT, "订单状态已变更，请刷新后重试");
        }

        progressRecorder.record(orderId, OrderStatus.IN_SERVICE, OrderStatus.PENDING_CONFIRM,
                OrderAction.REQUEST_COMPLETE, OrderProgressRecorder.OP_BOOSTER, boosterId,
                "打手已申请完单，等待老板确认（" + confirmDays + " 天后自动确认）");
        log.info("申请完单 orderId={} boosterId={} confirmDeadline={}", orderId, boosterId, deadline);

        order.setStatus(OrderStatus.PENDING_CONFIRM.name());
        order.setServiceCompletedAt(now);
        order.setConfirmDeadline(deadline);
        return order;
    }

    // ------------------------------------------------------------------
    // 客户侧 / 系统动作：确认与结算
    // ------------------------------------------------------------------

    /**
     * 客户确认收货：PENDING_CONFIRM → COMPLETED，并<b>立即结算</b>（01 §7.1）。
     *
     * <p>guard（01 §3）：待确认、本人、非 DISPUTING。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public ConfirmResult confirmByCustomer(Long orderId, Long customerId) {
        BizOrder order = lockOrder(orderId);
        if (!customerId.equals(order.getCustomerId())) {
            throw BizException.of(ErrorCode.FORBIDDEN, "只能确认自己的订单");
        }
        if (OrderStatus.DISPUTING.name().equals(order.getStatus())) {
            throw BizException.of(ErrorCode.CONFIRM_NOT_ALLOWED,
                    "该订单正在退款处理中，暂不能确认收货");
        }
        return doConfirmAndSettle(order, OrderAction.CONFIRM,
                OrderProgressRecorder.OP_CUSTOMER, customerId, "老板已确认收货，订单结算完成");
    }

    /**
     * 7 天自动确认：PENDING_CONFIRM → COMPLETED + 结算（01 §11 定时任务，每 5 分钟）。
     *
     * <p>{@code DISPUTING} 天然不会被扫到（扫描条件限定 PENDING_CONFIRM），
     * 满足「争议中跳过自动确认」（01 §3 guard）。</p>
     *
     * @return true=本次真实确认；false=状态已变（并发或已被客户确认），按幂等跳过
     */
    @Transactional(rollbackFor = Exception.class)
    public ConfirmResult autoConfirm(BizOrder order) {
        BizOrder locked = lockOrder(order.getId());
        if (!OrderStatus.PENDING_CONFIRM.name().equals(locked.getStatus())) {
            log.info("自动确认跳过：订单状态已非待确认 orderId={} status={}",
                    locked.getId(), locked.getStatus());
            return new ConfirmResult(locked, null, false);
        }
        if (locked.getConfirmDeadline() != null && locked.getConfirmDeadline().isAfter(LocalDateTime.now())) {
            log.info("自动确认跳过：未到截止时间 orderId={} deadline={}",
                    locked.getId(), locked.getConfirmDeadline());
            return new ConfirmResult(locked, null, false);
        }
        return doConfirmAndSettle(locked, OrderAction.AUTO_CONFIRM,
                OrderProgressRecorder.OP_SYSTEM, null,
                "超过 7 天未确认，系统自动确认收货并结算");
    }

    /**
     * 确认 + 结算的统一实现。
     *
     * <p><b>资金顺序</b>（01 §7.2）：锁订单 → 锁托管金 → 条件更新订单为 COMPLETED →
     * 托管金 remaining 清零 → 写 settlement → 打手收益入账 available → 写进度。</p>
     *
     * <p>结算金额由 {@link SettlementService} 按「原价 × 85%」计算，
     * 会员/邀请折扣由平台承担，不从打手收益里扣（README §4.4）。</p>
     */
    private ConfirmResult doConfirmAndSettle(BizOrder order, OrderAction action,
                                             String operatorType, Long operatorId, String remark) {
        Long orderId = order.getId();
        LocalDateTime now = LocalDateTime.now();

        // 锁托管金，校验仍为 HELD 且有余额
        OrderFundHold hold = fundHoldMapper.selectForUpdateByOrderId(orderId);
        if (hold == null) {
            throw BizException.of(ErrorCode.HOLD_EMPTY, "订单托管金记录不存在，请联系客服");
        }
        if (!HoldStatus.HELD.name().equals(hold.getStatus())) {
            throw BizException.of(ErrorCode.ORDER_ALREADY_SETTLED,
                    "该订单托管金已处理（状态 " + hold.getStatus() + "），不可重复结算");
        }

        // 条件更新订单状态：影响行数 0 即并发冲突（01 §3 第 5 步）
        int rows = orderMapper.confirmAndSettle(orderId, OrderStatus.PENDING_CONFIRM.name(),
                OrderStatus.COMPLETED.name(), now);
        if (rows == 0) {
            throw BizException.of(ErrorCode.STATE_CONFLICT, "订单状态已变更，请刷新后重试");
        }

        // 托管金剩余清零：钱已全部分配给打手与平台
        int holdRows = fundHoldMapper.settleRemaining(orderId, nz(hold.getRemainingAmount()));
        if (holdRows == 0) {
            throw BizException.of(ErrorCode.STATE_CONFLICT, "托管金状态已变更，请刷新后重试");
        }

        // 结算 + 打手收益入账（同一事务，失败整体回滚）
        order.setStatus(OrderStatus.COMPLETED.name());
        order.setConfirmedAt(now);
        Settlement settlement = settlementService.settleNormal(order);

        // 回写订单上的收益快照，便于列表页直接展示
        order.setBoosterAmount(settlement.getBoosterAmount());
        order.setPlatformAmount(settlement.getPlatformAmount());
        order.setUpdatedAt(now);
        orderMapper.updateById(order);

        progressRecorder.record(orderId, OrderStatus.PENDING_CONFIRM, OrderStatus.COMPLETED,
                action, operatorType, operatorId, remark);

        log.info("订单确认并结算完成 orderId={} action={} boosterAmount={}分 platformAmount={}分",
                orderId, action, settlement.getBoosterAmount(), settlement.getPlatformAmount());
        return new ConfirmResult(order, settlement, true);
    }

    // ------------------------------------------------------------------
    // 通用 guard 与工具
    // ------------------------------------------------------------------

    /**
     * 状态机统一入口的轻量实现：校验迁移合法性后执行条件更新。
     *
     * <p>供催单、改派等不改资金的动作使用；资金相关动作走各自的专用方法，
     * 以便附加锁与结算逻辑。</p>
     *
     * @return 影响行数
     */
    @Transactional(rollbackFor = Exception.class)
    public int transit(Long orderId, OrderAction action, OrderStatus expectedFrom,
                       String operatorType, Long operatorId, String remark) {
        BizOrder order = lockOrder(orderId);
        OrderStatus from = OrderStatus.of(order.getStatus());
        if (from != expectedFrom) {
            throw BizException.of(ErrorCode.STATE_CONFLICT,
                    "订单当前状态为「" + from.name() + "」，请刷新后重试");
        }
        OrderStatus to = OrderTransitionTable.target(from, action);
        if (to == null) {
            throw BizException.of(ErrorCode.STATE_CONFLICT, "该动作的目标状态需在业务中指定");
        }
        int rows = orderMapper.transitStatus(orderId, from.name(), to.name(), LocalDateTime.now());
        if (rows == 0) {
            throw BizException.of(ErrorCode.STATE_CONFLICT, "订单状态已变更，请刷新后重试");
        }
        progressRecorder.record(orderId, from, to, action, operatorType, operatorId, remark);
        return rows;
    }

    /** 催单：不改状态，只留痕（04 §3.4 {@code POST /orders/{id}/urge}）。 */
    @Transactional(rollbackFor = Exception.class)
    public void urge(Long orderId, Long customerId) {
        BizOrder order = lockOrder(orderId);
        if (!customerId.equals(order.getCustomerId())) {
            throw BizException.of(ErrorCode.FORBIDDEN, "只能催促自己的订单");
        }
        OrderStatus status = OrderStatus.of(order.getStatus());
        if (!status.isInProgress()) {
            throw BizException.of(ErrorCode.STATE_CONFLICT, "该订单已结束，无需催促");
        }
        progressRecorder.recordNoTransition(orderId, status, OrderAction.URGE,
                OrderProgressRecorder.OP_CUSTOMER, customerId, "老板催促尽快开始服务");
        log.info("客户催单 orderId={}", orderId);
    }

    /**
     * 打手拒单：ACCEPTED → PENDING_ACCEPT，清空归属，订单退回大厅重新可抢。
     *
     * <p><b>不动资金</b>：托管金仍为 HELD，客户金砖不退回（04 §3.6）。
     * 客户若想退，走自助全额退款链路。</p>
     *
     * @param reason 拒单理由，必填
     */
    @Transactional(rollbackFor = Exception.class)
    public BizOrder reject(Long orderId, Long boosterId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "请填写拒单理由");
        }
        BizOrder order = lockOrder(orderId);
        requireOrderBooster(order, boosterId);

        LocalDateTime now = LocalDateTime.now();
        int rows = orderMapper.releaseByReject(orderId, OrderStatus.ACCEPTED.name(),
                OrderStatus.PENDING_ACCEPT.name(), boosterId, now);
        if (rows == 0) {
            throw BizException.of(ErrorCode.STATE_CONFLICT,
                    "只有「已接单」状态可以拒单，请刷新后重试");
        }

        progressRecorder.record(orderId, OrderStatus.ACCEPTED, OrderStatus.PENDING_ACCEPT,
                OrderAction.REASSIGN, OrderProgressRecorder.OP_BOOSTER, boosterId,
                "打手拒单：" + reason + "，订单已退回大厅重新派单");

        log.info("打手拒单 orderId={} boosterId={} reason={}", orderId, boosterId, reason);
        order.setStatus(OrderStatus.PENDING_ACCEPT.name());
        order.setBoosterId(null);
        return order;
    }

    /** 行锁读取订单，不存在抛 404。必须在事务内调用。 */
    BizOrder lockOrder(Long orderId) {
        BizOrder order = orderMapper.selectForUpdate(orderId);
        if (order == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "订单不存在，请返回列表刷新");
        }
        return order;
    }

    /** 不加锁读取订单，用于详情展示。 */
    public BizOrder requireById(Long orderId) {
        BizOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "订单不存在，请返回列表刷新");
        }
        return order;
    }

    private Booster requireActiveBooster(Long boosterId) {
        Booster booster = boosterMapper.selectById(boosterId);
        if (booster == null || !Booster.STATUS_ACTIVE.equals(booster.getStatus())) {
            throw BizException.of(ErrorCode.ACCOUNT_RESTRICTED, "打手账号状态异常，无法接单");
        }
        BoosterEarningAccount account = earningAccountMapper.selectById(boosterId);
        if (account == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "收益账户不存在，请联系客服");
        }
        return booster;
    }

    private void requireOrderBooster(BizOrder order, Long boosterId) {
        if (!boosterId.equals(order.getBoosterId())) {
            throw BizException.of(ErrorCode.FORBIDDEN, "只能操作自己接的订单");
        }
    }

    private BoosterGameAccount requireOwnGameAccount(Long gameAccountId, Long boosterId) {
        if (gameAccountId == null) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "请选择用于本单的游戏账号");
        }
        BoosterGameAccount account = gameAccountMapper.selectById(gameAccountId);
        if (account == null || !boosterId.equals(account.getBoosterId())) {
            throw BizException.of(ErrorCode.FORBIDDEN, "游戏账号不存在或不属于你");
        }
        if (!BoosterGameAccount.STATUS_ACTIVE.equals(account.getStatus())) {
            throw BizException.of(ErrorCode.STATE_CONFLICT, "该游戏账号已停用，请选择其他账号");
        }
        return account;
    }

    /** 校验指定凭证存在且属于本单、类型正确。 */
    private void requireEvidence(Long orderId, Long evidenceId, String type, String missingMessage) {
        if (evidenceId == null) {
            throw BizException.of(ErrorCode.PARAM_ERROR, missingMessage);
        }
        OrderEvidence evidence = evidenceMapper.selectById(evidenceId);
        if (evidence == null || !orderId.equals(evidence.getOrderId())) {
            throw BizException.of(ErrorCode.PARAM_ERROR, missingMessage + "（凭证不存在或不属于本订单）");
        }
        if (!type.equals(evidence.getType())) {
            throw BizException.of(ErrorCode.PARAM_ERROR,
                    "凭证类型不正确，需要" + ("START".equals(type) ? "开局照" : "结束照"));
        }
    }

    /** 申请完单时校验开局照必须已存在（01 §3 guard）。 */
    private void requireStartEvidence(Long orderId) {
        OrderEvidence start = evidenceMapper.selectByOrderAndType(orderId, "START");
        if (start == null) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "缺少开局照，无法申请完单");
        }
    }

    /**
     * 创建服务计时器（01 §11）。已存在则跳过，保证「开始服务」可安全重试。
     */
    private void upsertTimer(Long orderId, LocalDateTime startAt) {
        Long existing = orderTimerMapper.countByOrderId(orderId);
        if (existing != null && existing > 0) {
            return;
        }
        OrderTimer timer = new OrderTimer();
        timer.setId(idGenerator.nextId());
        timer.setOrderId(orderId);
        timer.setStartAt(startAt);
        timer.setPausedSeconds(0);
        timer.setTotalSeconds(0);
        timer.setUpdatedAt(startAt);
        orderTimerMapper.insert(timer);
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }

    /**
     * 确认结果。
     *
     * @param order      确认后的订单
     * @param settlement 结算单；未真实结算时为 null
     * @param settled    本次是否真实完成结算（false 表示幂等跳过）
     */
    public record ConfirmResult(BizOrder order, Settlement settlement, boolean settled) {

        /** 结算金额摘要，便于日志与响应复用。 */
        public String summary() {
            if (settlement == null) {
                return "未结算";
            }
            return "打手 " + MoneyUtil.fenToYuan(settlement.getBoosterAmount())
                    + " 元 / 平台 " + MoneyUtil.fenToYuan(settlement.getPlatformAmount()) + " 元";
        }
    }
}
