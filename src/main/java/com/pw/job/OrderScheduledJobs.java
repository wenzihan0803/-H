package com.pw.job;

import com.pw.common.config.BusinessProperties;
import com.pw.common.enums.HandoverStatus;
import com.pw.common.enums.OrderAction;
import com.pw.common.enums.OrderStatus;
import com.pw.common.lock.RedisLock;
import com.pw.order.entity.BizOrder;
import com.pw.order.entity.HandoverTask;
import com.pw.order.mapper.BizOrderMapper;
import com.pw.order.mapper.HandoverTaskMapper;
import com.pw.order.service.OrderLifecycleService;
import com.pw.order.service.OrderProgressRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单相关定时任务。对应技术规范 01 §13。
 *
 * <p><b>两条硬要求</b>（01 §13 末句）：全部任务幂等；Redis 锁保证单实例执行。
 * 多副本部署时同一时刻只有一个实例真正跑，避免自动确认重复结算。</p>
 *
 * <p>本类包含：</p>
 * <table border="1">
 *   <caption>任务清单</caption>
 *   <tr><th>任务</th><th>频率</th><th>逻辑</th></tr>
 *   <tr><td>自动确认</td><td>5 分钟</td>
 *       <td>PENDING_CONFIRM 且 now ≥ confirm_deadline → COMPLETED + 结算；DISPUTING 天然不在扫描范围</td></tr>
 *   <tr><td>交接超时</td><td>1 分钟</td>
 *       <td>10/20/30 分钟分级提醒 → 升级管理员 → 允许打手无责退款</td></tr>
 * </table>
 *
 * <p><b>为什么自动确认必须在第一期实现</b>：它是 P0「结算闭环」的必要环节——
 * 客户不点确认时，资金会永久挂在托管金上，打手永远拿不到收益。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderScheduledJobs {

    /** 单批处理上限，防止积压时一次拉太多导致长事务。 */
    private static final int BATCH_LIMIT = 200;

    private static final String LOCK_AUTO_CONFIRM = "job:lock:auto-confirm";
    private static final String LOCK_HANDOVER = "job:lock:handover-timeout";

    private final BizOrderMapper orderMapper;
    private final HandoverTaskMapper handoverTaskMapper;
    private final OrderLifecycleService lifecycleService;
    private final OrderProgressRecorder progressRecorder;
    private final BusinessProperties business;
    private final RedisLock redisLock;

    /**
     * 自动确认收货：7 天未确认则系统代为确认并结算（01 §13、§7.1）。
     *
     * <p><b>幂等性</b>由三层保证：
     * <ol>
     *   <li>Redis 锁：同一时刻单实例执行；</li>
     *   <li>状态条件更新：{@code WHERE status='PENDING_CONFIRM'}，影响行数 0 即已被处理；</li>
     *   <li>{@code settlement.uk_order} + {@code booster_earning_txn.uk_biz} 唯一索引兜底。</li>
     * </ol>
     *
     * <p><b>单笔失败不影响其他订单</b>：每单独立事务（{@code autoConfirm} 自带
     * {@code @Transactional}），异常被捕获后继续处理下一单，避免一单卡死整批。</p>
     */
    @Scheduled(fixedDelay = 5 * 60 * 1000L, initialDelay = 60 * 1000L)
    public void autoConfirm() {
        runWithLock(LOCK_AUTO_CONFIRM, Duration.ofMinutes(4), "自动确认", this::doAutoConfirm);
    }

    /**
     * 交接超时处理：10/20/30 分钟三级 SLA（01 §11、§13）。
     *
     * <p>分级动作：</p>
     * <ul>
     *   <li>超过 SLA（10 分钟）：记录提醒，客服需尽快下发；</li>
     *   <li>超过升级阈值（20 分钟）：{@code escalate_level+1}，升级管理员；</li>
     *   <li>超过无责退款阈值（30 分钟）：置 {@code TIMEOUT}，打手可无责退款。</li>
     * </ul>
     *
     * <p><b>幂等性</b>：达到 30 分钟后置 TIMEOUT 终态，不再重复处理；
     * 升级级别用 {@code escalate_level} 单调递增，避免重复升级。</p>
     */
    @Scheduled(fixedDelay = 60 * 1000L, initialDelay = 90 * 1000L)
    public void handoverTimeout() {
        runWithLock(LOCK_HANDOVER, Duration.ofSeconds(50), "交接超时", this::doHandoverTimeout);
    }

    // ------------------------------------------------------------------
    // 任务实现（供调度器与 dev 手动触发共用）
    // ------------------------------------------------------------------

    /**
     * 执行一轮自动确认。
     *
     * @return 本次成功确认并结算的订单数
     */
    public int runAutoConfirmOnce() {
        return doAutoConfirm();
    }

    /**
     * 执行一轮交接超时检查。
     *
     * @return 本次处理的任务数
     */
    public int runHandoverTimeoutOnce() {
        return doHandoverTimeout();
    }

    private int doAutoConfirm() {
        LocalDateTime now = LocalDateTime.now();
        List<BizOrder> dueOrders = orderMapper.selectAutoConfirmDue(now, BATCH_LIMIT);
        if (dueOrders.isEmpty()) {
            return 0;
        }
        log.info("自动确认扫描到 {} 笔待处理订单", dueOrders.size());

        int confirmed = 0;
        for (BizOrder order : dueOrders) {
            try {
                OrderLifecycleService.ConfirmResult result = lifecycleService.autoConfirm(order);
                if (result.settled()) {
                    confirmed++;
                    log.info("自动确认完成 orderId={} orderNo={} 结算={}",
                            order.getId(), order.getOrderNo(), result.summary());
                }
            } catch (Exception ex) {
                // 单笔失败不阻断整批；资金异常需告警对账（01 §13「自动确认延迟」告警项）
                log.error("自动确认失败，跳过该单 orderId={} orderNo={}",
                        order.getId(), order.getOrderNo(), ex);
            }
        }
        log.info("自动确认本轮完成：成功 {} 笔 / 扫描 {} 笔", confirmed, dueOrders.size());
        return confirmed;
    }

    private int doHandoverTimeout() {
        LocalDateTime now = LocalDateTime.now();
        List<HandoverTask> overdue = handoverTaskMapper.selectOverdue(now, BATCH_LIMIT);
        if (overdue.isEmpty()) {
            return 0;
        }

        int handled = 0;
        for (HandoverTask task : overdue) {
            try {
                if (handleOneHandover(task, now)) {
                    handled++;
                }
            } catch (Exception ex) {
                log.error("交接超时处理失败 taskId={} orderId={}",
                        task.getId(), task.getOrderId(), ex);
            }
        }
        if (handled > 0) {
            log.info("交接超时本轮处理 {} 笔 / 扫描 {} 笔", handled, overdue.size());
        }
        return handled;
    }

    /**
     * 处理单个超时交接任务。
     *
     * @return true 表示状态发生了变化
     */
    private boolean handleOneHandover(HandoverTask task, LocalDateTime now) {
        Long orderId = task.getOrderId();
        BizOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            return false;
        }
        // 订单已结束（退款/完成）时交接无需再推进
        OrderStatus orderStatus = OrderStatus.of(order.getStatus());
        if (orderStatus.isTerminal()) {
            return false;
        }

        long overdueMinutes = Duration.between(task.getDueAt(), now).toMinutes();
        int escalateMinutes = business.getHandoverEscalateMinutes();
        int refundMinutes = business.getHandoverBoosterRefundMinutes();

        // 达到无责退款阈值：置 TIMEOUT 终态，打手可申请无责退款
        if (overdueMinutes >= refundMinutes - business.getHandoverSlaMinutes()) {
            task.setStatus(HandoverStatus.TIMEOUT.name());
            task.setUpdatedAt(now);
            handoverTaskMapper.updateById(task);
            order.setHandoverStatus(HandoverStatus.TIMEOUT.name());
            order.setUpdatedAt(now);
            orderMapper.updateById(order);

            progressRecorder.recordNoTransition(orderId, orderStatus, OrderAction.URGE,
                    OrderProgressRecorder.OP_SYSTEM, null,
                    "交接超时 " + (overdueMinutes + business.getHandoverSlaMinutes())
                            + " 分钟，打手可申请无责退款");
            log.warn("交接超时置 TIMEOUT orderId={} overdueMinutes={}", orderId, overdueMinutes);
            return true;
        }

        // 达到升级阈值：升级管理员（escalate_level 单调递增，避免重复升级）
        int expectedLevel = (int) ((overdueMinutes + business.getHandoverSlaMinutes()) / escalateMinutes);
        if (expectedLevel > nz(task.getEscalateLevel())) {
            task.setEscalateLevel(expectedLevel);
            task.setUpdatedAt(now);
            handoverTaskMapper.updateById(task);

            progressRecorder.recordNoTransition(orderId, orderStatus, OrderAction.URGE,
                    OrderProgressRecorder.OP_SYSTEM, null,
                    "交接超时已升级至管理员处理（级别 " + expectedLevel + "）");
            log.warn("交接超时升级 orderId={} escalateLevel={} status={}",
                    orderId, expectedLevel, task.getStatus());
            return true;
        }

        return false;
    }

    /**
     * 带 Redis 锁执行任务体，锁获取失败则跳过本轮（说明另一实例正在跑）。
     */
    private void runWithLock(String lockKey, Duration ttl, String taskName,
                             java.util.function.IntSupplier body) {
        String token = redisLock.tryLock(lockKey, ttl);
        if (token == null) {
            log.debug("{}任务跳过本轮：未获取到锁（其他实例执行中）", taskName);
            return;
        }
        try {
            body.getAsInt();
        } catch (Exception ex) {
            log.error("{}任务执行异常", taskName, ex);
        } finally {
            redisLock.unlock(lockKey, token);
        }
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
