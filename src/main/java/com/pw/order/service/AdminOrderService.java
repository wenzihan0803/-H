package com.pw.order.service;

import com.pw.booster.entity.Booster;
import com.pw.booster.mapper.BoosterMapper;
import com.pw.common.config.BusinessProperties;
import com.pw.common.enums.HandoverStatus;
import com.pw.common.enums.OrderAction;
import com.pw.common.enums.OrderStatus;
import com.pw.common.exception.BizException;
import com.pw.common.exception.ErrorCode;
import com.pw.common.util.CryptoUtil;
import com.pw.common.util.IdGenerator;
import com.pw.order.entity.BizOrder;
import com.pw.order.entity.HandoverLog;
import com.pw.order.entity.HandoverTask;
import com.pw.order.mapper.BizOrderMapper;
import com.pw.order.mapper.HandoverLogMapper;
import com.pw.order.mapper.HandoverTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 管理端订单操作服务：客服交接、指派/改派打手。
 *
 * <p>口径来源：01 §11 交接 SLA、04 §3.7、§4.4；README §5。</p>
 *
 * <p><b>交接</b>（01 §11）：客服向打手下发老板游戏 ID 与上号指引，
 * task 置 SENT，写 handover_log（老板 ID 加密存储）。
 * 打手 ACK 后才能开始服务（见 {@link OrderLifecycleService#startService}）。</p>
 *
 * <p><b>指派/改派</b>（04 §3.7）：管理端可为大厅单指派打手，或把已接单改派给他人。
 * 指派/改派<b>不动资金</b>，只改 booster_id 归属并留痕。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminOrderService {

    private final BizOrderMapper orderMapper;
    private final HandoverTaskMapper handoverTaskMapper;
    private final HandoverLogMapper handoverLogMapper;
    private final BoosterMapper boosterMapper;
    private final OrderProgressRecorder progressRecorder;
    private final CryptoUtil cryptoUtil;
    private final IdGenerator idGenerator;
    private final BusinessProperties business;

    /**
     * 客服下发交接信息。
     *
     * <p>把订单 handover_status 与 handover_task 置 SENT，并在 handover_log 写入
     * 加密后的老板游戏 ID 与指引。打手端此后才能看到 bossGameId（04 §2.4）。</p>
     *
     * @param orderId    订单 ID
     * @param csId       客服 admin_user_id
     * @param channel    IM / WECHAT / PHONE
     * @param bossGameId 老板游戏 ID（明文入参，加密存储）
     * @param guide      上号指引，打手端可见
     * @param remark     内部备注，打手端不可见，可为 null
     * @return 交接任务
     */
    @Transactional(rollbackFor = Exception.class)
    public HandoverTask handover(Long orderId, Long csId, String channel,
                                 String bossGameId, String guide, String remark) {
        if (channel == null || channel.isBlank()) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "请选择交接渠道");
        }
        if (bossGameId == null || bossGameId.isBlank()) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "请填写老板游戏 ID");
        }
        if (guide == null || guide.isBlank()) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "请填写上号指引");
        }

        BizOrder order = orderMapper.selectForUpdate(orderId);
        if (order == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "订单不存在，请返回列表刷新");
        }
        if (order.getBoosterId() == null) {
            throw BizException.of(ErrorCode.STATE_CONFLICT, "订单尚未指派打手，无法交接");
        }

        HandoverTask task = handoverTaskMapper.selectByOrderId(orderId);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime ackDueAt = now.plusMinutes(business.getHandoverSlaMinutes());
        if (task == null) {
            // 理论上创建订单时已建 task，这里兜底补建，避免历史数据缺 task
            task = new HandoverTask();
            task.setId(idGenerator.nextId());
            task.setOrderId(orderId);
            task.setStatus(HandoverStatus.SENT.name());
            task.setDueAt(ackDueAt);
            task.setEscalateLevel(0);
            task.setCreatedAt(now);
            task.setCsId(csId);
            task.setSentAt(now);
            task.setUpdatedAt(now);
            handoverTaskMapper.insert(task);
        } else {
            task.setStatus(HandoverStatus.SENT.name());
            task.setCsId(csId);
            task.setSentAt(now);
            // 发送时刻重置交接倒计时：打手从真正收到老板 ID 起有完整 SLA。
            // 原实现沿用它「接单时刻 + 10 分钟」的 dueAt，客服晚发时打手只剩几分钟，
            // 到 30 分钟即被判 TIMEOUT，之后 ACK 只认 SENT，订单永久卡在 ACCEPTED。
            task.setDueAt(ackDueAt);
            task.setUpdatedAt(now);
            handoverTaskMapper.updateById(task);
        }

        // 写交接日志：老板 ID 加密，指引明文（打手端可见）
        HandoverLog handoverLog = new HandoverLog();
        handoverLog.setId(idGenerator.nextId());
        handoverLog.setOrderId(orderId);
        handoverLog.setTaskId(task.getId());
        handoverLog.setAction(HandoverStatus.SENT.name());
        handoverLog.setChannel(channel);
        handoverLog.setBossIdCipher(cryptoUtil.encrypt(bossGameId));
        handoverLog.setGuide(guide);
        handoverLog.setOperatorId(csId);
        handoverLog.setCreatedAt(now);
        handoverLogMapper.insert(handoverLog);

        // 订单交接状态置 SENT，并把老板 ID 密文冗余到订单（详情装配可回退读取）
        order.setHandoverStatus(HandoverStatus.SENT.name());
        order.setBossGameIdCipher(cryptoUtil.encrypt(bossGameId));
        order.setUpdatedAt(now);
        orderMapper.updateById(order);

        progressRecorder.recordNoTransition(orderId, OrderStatus.of(order.getStatus()),
                OrderAction.ACCEPT, OrderProgressRecorder.OP_CS, csId,
                "客服已通过" + channel + "下发老板游戏信息与上号指引"
                        + (remark == null || remark.isBlank() ? "" : "（备注：" + remark + "）"));

        log.info("客服交接下发 orderId={} csId={} channel={}", orderId, csId, channel);
        return task;
    }

    /**
     * 管理端指派打手（大厅单 → 指派给指定打手）。
     *
     * <p>仅 PENDING_ACCEPT 且尚未归属打手的订单可指派。</p>
     *
     * @param operatorType CS / ADMIN / SUPER_ADMIN
     */
    @Transactional(rollbackFor = Exception.class)
    public BizOrder assign(Long orderId, Long boosterId, Long operatorId, String operatorType) {
        BizOrder order = orderMapper.selectForUpdate(orderId);
        if (order == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "订单不存在，请返回列表刷新");
        }
        if (!OrderStatus.PENDING_ACCEPT.name().equals(order.getStatus())) {
            throw BizException.of(ErrorCode.STATE_CONFLICT,
                    "只有待接单的订单可以指派（当前 " + order.getStatus() + "）");
        }
        if (order.getBoosterId() != null) {
            throw BizException.of(ErrorCode.STATE_CONFLICT, "该订单已有归属打手，请用改派");
        }
        requireAssignableBooster(boosterId);

        LocalDateTime now = LocalDateTime.now();
        order.setBoosterId(boosterId);
        order.setMode("DESIGNATED");
        order.setUpdatedAt(now);
        orderMapper.updateById(order);

        progressRecorder.recordNoTransition(orderId, OrderStatus.PENDING_ACCEPT,
                OrderAction.REASSIGN, operatorType, operatorId,
                "管理端指派打手 boosterId=" + boosterId);
        log.info("管理端指派打手 orderId={} boosterId={} operatorId={}", orderId, boosterId, operatorId);
        return order;
    }

    /**
     * 管理端改派打手（已接单 → 改派给他人）。
     *
     * <p>ACCEPTED 状态可改派；改派后订单回到该状态，新打手需重新走交接与开始服务。
     * 不动资金（托管金仍 HELD）。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public BizOrder reassign(Long orderId, Long toBoosterId, String reason,
                             Long operatorId, String operatorType) {
        if (reason == null || reason.isBlank()) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "请填写改派理由");
        }
        BizOrder order = orderMapper.selectForUpdate(orderId);
        if (order == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "订单不存在，请返回列表刷新");
        }
        OrderStatus status = OrderStatus.of(order.getStatus());
        if (status != OrderStatus.ACCEPTED && status != OrderStatus.PENDING_ACCEPT) {
            throw BizException.of(ErrorCode.STATE_CONFLICT,
                    "服务已开始或已结束，不可改派（当前 " + status.name() + "）");
        }
        requireAssignableBooster(toBoosterId);
        Long fromBoosterId = order.getBoosterId();

        LocalDateTime now = LocalDateTime.now();
        order.setBoosterId(toBoosterId);
        // 改派 = 指定归属：必须同步把 mode 置为 DESIGNATED。
        // 否则大厅单（HALL）带着 booster_id 留在 PENDING_ACCEPT，接单校验会要求
        // HALL 单 boosterId 必须为空——结果新打手也接不了，订单卡死。
        order.setMode("DESIGNATED");
        // 改派后交接需重做，状态回到 PENDING，打手需重新 ACK
        order.setHandoverStatus(HandoverStatus.PENDING.name());
        order.setUpdatedAt(now);
        orderMapper.updateById(order);

        progressRecorder.recordNoTransition(orderId, status, OrderAction.REASSIGN,
                operatorType, operatorId,
                "管理端改派：boosterId " + fromBoosterId + " → " + toBoosterId + "，理由：" + reason);
        log.info("管理端改派 orderId={} from={} to={} operatorId={} reason={}",
                orderId, fromBoosterId, toBoosterId, operatorId, reason);
        return order;
    }

    /** 校验打手存在、ACTIVE、未封禁、无进行中订单（本期无排班，不再校验在线）。 */
    private void requireAssignableBooster(Long boosterId) {
        if (boosterId == null) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "请指定打手");
        }
        Booster booster = boosterMapper.selectById(boosterId);
        if (booster == null || !Booster.STATUS_ACTIVE.equals(booster.getStatus())) {
            throw BizException.of(ErrorCode.NOT_FOUND, "打手不存在或当前不可接单");
        }
        if (booster.getBanUntil() != null && booster.getBanUntil().isAfter(LocalDateTime.now())) {
            throw BizException.of(ErrorCode.ACCOUNT_RESTRICTED, "该打手封禁中，不可指派");
        }
        long inProgress = orderMapper.countInProgressByBooster(boosterId);
        if (inProgress > 0) {
            throw BizException.of(ErrorCode.STATE_CONFLICT, "该打手正在服务中，不可指派新单");
        }
    }
}
