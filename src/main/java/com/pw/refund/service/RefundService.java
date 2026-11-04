package com.pw.refund.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pw.common.enums.HoldStatus;
import com.pw.common.enums.OrderAction;
import com.pw.common.enums.OrderStatus;
import com.pw.common.enums.RefundStatus;
import com.pw.common.enums.RefundType;
import com.pw.common.enums.Role;
import com.pw.common.exception.BizException;
import com.pw.common.exception.ErrorCode;
import com.pw.common.util.IdGenerator;
import com.pw.common.util.MoneyUtil;
import com.pw.gold.service.GoldAccountService;
import com.pw.order.entity.BizOrder;
import com.pw.order.entity.OrderFundHold;
import com.pw.order.mapper.BizOrderMapper;
import com.pw.order.mapper.OrderFundHoldMapper;
import com.pw.order.service.OrderProgressRecorder;
import com.pw.refund.entity.Refund;
import com.pw.refund.mapper.RefundMapper;
import com.pw.settlement.entity.Settlement;
import com.pw.settlement.service.SettlementService;
import lombok.RequiredArgsConstructor;
import com.pw.im.entity.SiteMessage;
import com.pw.im.service.ImService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import java.time.LocalDateTime;

/**
 * 退款服务。实现 01 §6 的三条链路。
 *
 * <p>口径来源（唯一）：技术规范/01 §6、§7.2；README §2；04 §4.2。</p>
 *
 * <table border="1">
 *   <caption>三种退款场景</caption>
 *   <tr><th>场景</th><th>发起人</th><th>允许状态</th><th>比例</th><th>是否审批</th><th>订单终态</th></tr>
 *   <tr><td>服务开始前自助退</td><td>客户</td><td>PENDING_ACCEPT / ACCEPTED</td>
 *       <td>100%</td><td>无需</td><td>REFUNDED</td></tr>
 *   <tr><td>服务开始后争议退</td><td>客服 / 管理员</td><td>IN_SERVICE / PENDING_CONFIRM</td>
 *       <td>1–100%</td><td>主管审批</td><td>REFUNDED 或 PARTIAL_REFUNDED</td></tr>
 *   <tr><td>已结算订单售后</td><td>—</td><td>COMPLETED</td>
 *       <td>—</td><td>拒绝（60009）</td><td>走仲裁工单，不动资金</td></tr>
 * </table>
 *
 * <p><b>关键规则</b>：</p>
 * <ul>
 *   <li>退款只回<b>金砖余额</b>，不调微信退款（README §2.1）；</li>
 *   <li>发起人不能审批自己的退款单；管理员发起时由超管审批（01 §6）；</li>
 *   <li><b>平台费只在确认收货后收取（2026-10-29 用户拍板）</b>：争议退款发生在确认之前，
 *       因此部分退款时<b>不扣平台费</b>——客户按下单实付 × 比例退款，
 *       剩余金额（retained）<b>全归打手</b>，{@code commissionAmount = 0}；</li>
 *   <li>100% 退款<b>不产生结算单</b>、打手无收益（README §2.2）；</li>
 *   <li>退款后订单终态，不能继续履约，想继续打必须新订单；</li>
 *   <li>主管驳回：订单回到 {@code previousOrderStatus}，托管金不变。</li>
 * </ul>
 *
 * <p><b>并发安全</b>（01 §16「退款并发重复加金砖」）：托管金行锁 + 订单状态条件更新 +
 * {@code refund.uk_order_active} 唯一索引（数据库层保证同一订单最多一条活跃退款单）+
 * 金砖流水 {@code uk_biz} 唯一。四道防线。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundService {

    private final RefundMapper refundMapper;
    private final BizOrderMapper orderMapper;
    private final OrderFundHoldMapper fundHoldMapper;
    private final GoldAccountService goldAccountService;
    private final SettlementService settlementService;
    private final OrderProgressRecorder progressRecorder;
    private final IdGenerator idGenerator;
    private final ImService imService;

    // ------------------------------------------------------------------
    // A. 服务开始前：客户自助全额退（01 §6.1）
    // ------------------------------------------------------------------

    /**
     * 客户自助全额退款。
     *
     * <p>事务顺序严格按 01 §6.1：锁托管金 → 校验 HELD 且 remaining>0 → 校验订单状态 →
     * 托管金清零置 REFUNDED → 金砖回账 → 写 refund(SUCCESS) → 订单置 REFUNDED → 写进度。</p>
     *
     * <p><b>无需审批</b>，提交即到账。</p>
     *
     * @param orderId    订单 ID
     * @param customerId 客户 ID（从登录态取）
     * @param reason     退款原因，必填
     * @return 退款单（status=SUCCESS）
     */
    @Transactional(rollbackFor = Exception.class)
    public Refund customerSelfRefund(Long orderId, Long customerId, String reason) {
        requireReason(reason);
        BizOrder order = lockOrder(orderId);
        if (!customerId.equals(order.getCustomerId())) {
            throw BizException.of(ErrorCode.FORBIDDEN, "只能退款自己的订单");
        }

        OrderStatus status = OrderStatus.of(order.getStatus());
        // 已结算订单不能再退，引导走仲裁（README §2.3、01 §6）
        if (status == OrderStatus.COMPLETED) {
            throw BizException.of(ErrorCode.ORDER_ALREADY_SETTLED,
                    "订单已结算不可退款，请联系客服处理");
        }
        if (status.isTerminal()) {
            throw BizException.of(ErrorCode.REFUND_DUPLICATED, "该订单已退款完成，请查看退款详情");
        }
        if (!status.canCustomerSelfRefund()) {
            // 服务已开始，客户不能自助退（04 §4.2-A：IN_SERVICE 后返回 60014）
            throw BizException.of(ErrorCode.REFUND_SERVICE_STARTED,
                    "服务已开始无法自助全额退，请联系客服协商");
        }

        // 锁托管金并校验
        OrderFundHold hold = lockHold(orderId);
        long refundAmount = nz(hold.getRemainingAmount());
        if (refundAmount <= 0) {
            throw BizException.of(ErrorCode.HOLD_EMPTY, "托管金已清零或已退款，请刷新订单查看");
        }

        LocalDateTime now = LocalDateTime.now();

        // 托管金清零 + 置 REFUNDED（条件更新，并发下只有一个成功）
        int holdRows = fundHoldMapper.refundAll(orderId, refundAmount, refundAmount, now);
        if (holdRows == 0) {
            throw BizException.of(ErrorCode.STATE_CONFLICT, "退款处理中，请勿重复提交");
        }

        // 订单置 REFUNDED（条件更新）
        int orderRows = orderMapper.refundOrder(orderId, status.name(), OrderStatus.REFUNDED.name(),
                refundAmount, truncate(reason), now);
        if (orderRows == 0) {
            throw BizException.of(ErrorCode.STATE_CONFLICT, "订单状态已变更，请刷新后重试");
        }

        // 写退款单（直接 SUCCESS，无需审批）
        Refund refund = buildRefund(order, RefundType.FULL, 100, refundAmount, 0L,
                status, RefundStatus.SUCCESS, "CUSTOMER", customerId, reason, null, now);
        refund.setCompletedAt(now);
        refund.setApprovedAt(now);
        insertRefund(refund);

        // 金砖回账（幂等键 = 退款单号）
        goldAccountService.refundToGold(customerId, refundAmount, refund.getRefundNo(), orderId);
        refund.setGoldTxnId(null); // 流水 ID 由 GoldAccountService 内部生成，此处不回填

        progressRecorder.record(orderId, status, OrderStatus.REFUNDED, OrderAction.CUSTOMER_REFUND,
                OrderProgressRecorder.OP_CUSTOMER, customerId,
                "老板自助全额退款 " + MoneyUtil.fenToYuan(refundAmount) + " 元，已退回金砖余额");

        log.info("客户自助全额退款成功 orderId={} refundNo={} amount={}分",
                orderId, refund.getRefundNo(), refundAmount);
        return refund;
    }

    // ------------------------------------------------------------------
    // B. 服务开始后：客服发起争议退款（01 §6.2 前半）
    // ------------------------------------------------------------------

    /**
     * 客服/管理员发起争议退款申请。
     *
     * <p>只建退款单并把订单转入 {@code DISPUTING}，<b>不动资金</b>；
     * 资金变动发生在主管审批通过时。</p>
     *
     * @param orderId      订单 ID
     * @param refundRate   退款比例 1–100
     * @param reason       退款原因，必填
     * @param evidenceJson 证据 objectKey 数组 JSON，可为 null
     * @param applicant    发起人（CS / ADMIN / SUPER_ADMIN）
     * @param applicantId  发起人的 admin_user_id；<b>必须落库</b>，否则「禁自审」与
     *                     「客服只看自己发起的」两条规则都会失效
     * @return 退款单（status=PENDING_APPROVAL）
     */
    @Transactional(rollbackFor = Exception.class)
    public Refund createDisputeRefund(Long orderId, int refundRate, String reason,
                                      String evidenceJson, Role applicant, Long applicantId) {
        requireReason(reason);
        if (refundRate < 1 || refundRate > 100) {
            throw BizException.of(ErrorCode.REFUND_RATE_INVALID, "退款比例仅支持 1%–100%");
        }
        if (applicant != Role.CS && applicant != Role.ADMIN && applicant != Role.SUPER_ADMIN) {
            throw BizException.of(ErrorCode.FORBIDDEN, "只有客服或管理员可以发起争议退款");
        }

        BizOrder order = lockOrder(orderId);
        OrderStatus status = OrderStatus.of(order.getStatus());

        if (status == OrderStatus.COMPLETED) {
            throw BizException.of(ErrorCode.ORDER_ALREADY_SETTLED,
                    "订单已结算不可退款，请联系客服处理");
        }
        if (status.isTerminal()) {
            throw BizException.of(ErrorCode.REFUND_DUPLICATED, "该订单已退款完成，请查看退款详情");
        }
        if (!status.canDisputeRefund()) {
            throw BizException.of(ErrorCode.STATE_CONFLICT,
                    "当前状态「" + status.label() + "」不可发起争议退款");
        }
        // 同一订单同时只允许一条待审退款单（01 §3 guard）
        if (refundMapper.countPendingByOrderId(orderId) > 0) {
            throw BizException.of(ErrorCode.REFUND_PENDING_EXISTS,
                    "已有待审批的退款申请，请等待主管审批");
        }

        long payAmount = nz(order.getPayAmount());
        long refundAmount = MoneyUtil.refundAmount(payAmount, refundRate);
        long retained = MoneyUtil.retained(payAmount, refundAmount);
        if (refundAmount <= 0) {
            throw BizException.of(ErrorCode.REFUND_RATE_INVALID,
                    "按该比例计算退款金额为 0，请调整比例");
        }

        LocalDateTime now = LocalDateTime.now();

        // 订单转入 DISPUTING，暂停确认与自动确认（04 §4.2-B）
        int orderRows = orderMapper.transitStatus(orderId, status.name(),
                OrderStatus.DISPUTING.name(), now);
        if (orderRows == 0) {
            throw BizException.of(ErrorCode.STATE_CONFLICT, "订单状态已变更，请刷新后重试");
        }

        // 佣金与打手收益在审批通过时才最终确定；2026-10-29 口径：未确认收货不扣平台费，
        // 此处先按「剩余全归打手、平台费 0」预算，便于主管审批时核对
        long commission = 0L;
        Refund refund = buildRefund(order, RefundType.ofRate(refundRate), refundRate,
                refundAmount, retained, status, RefundStatus.PENDING_APPROVAL,
                applicant.name(), applicantId, reason, evidenceJson, now);
        refund.setCommissionAmount(commission);
        refund.setBoosterAmount(retained - commission);
        insertRefund(refund);

        progressRecorder.record(orderId, status, OrderStatus.DISPUTING, OrderAction.DISPUTE_SUBMIT,
                applicant == Role.CS ? OrderProgressRecorder.OP_CS : OrderProgressRecorder.OP_ADMIN,
                null, "客服发起争议退款 " + refundRate + "%（"
                        + MoneyUtil.fenToYuan(refundAmount) + " 元），等待主管审批");

        log.info("争议退款申请已提交 orderId={} refundNo={} rate={}% refundAmount={}分 retained={}分 applicant={}",
                orderId, refund.getRefundNo(), refundRate, refundAmount, retained, applicant);
        return refund;
    }

    // ------------------------------------------------------------------
    // C. 主管审批（01 §6.2 后半）
    // ------------------------------------------------------------------

    /**
     * 审批前修改待审退款的比例（仅管理员/超管）。
     *
     * <p>金额按「实付 × 新比例」重算；只能改 PENDING_APPROVAL 的单，已审批/已驳回不可改。
     * 平台上不抽成：剩余仍全额归打手。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public Refund updatePendingRate(Long refundId, int refundRate, Long operatorId) {
        if (refundRate < 1 || refundRate > 100) {
            throw BizException.of(ErrorCode.REFUND_RATE_INVALID, "退款比例仅支持 1%–100%");
        }
        Refund refund = refundMapper.selectForUpdate(refundId);
        if (refund == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "退款单不存在，请刷新列表");
        }
        if (!RefundStatus.PENDING_APPROVAL.name().equals(refund.getStatus())) {
            throw BizException.of(ErrorCode.STATE_CONFLICT, "该退款单已处理，不能修改比例");
        }
        BizOrder order = orderMapper.selectById(refund.getOrderId());
        if (order == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "订单不存在");
        }
        long payAmount = nz(order.getPayAmount());
        long refundAmount = MoneyUtil.refundAmount(payAmount, refundRate);
        if (refundAmount <= 0) {
            throw BizException.of(ErrorCode.REFUND_RATE_INVALID, "按该比例计算退款金额为 0，请调整比例");
        }
        refund.setType(RefundType.ofRate(refundRate).name());
        refund.setRefundRate(refundRate);
        refund.setRefundAmount(refundAmount);
        refund.setRetainedAmount(MoneyUtil.retained(payAmount, refundAmount));
        refundMapper.updateById(refund);
        log.info("修改待审退款比例 refundId={} rate={}% operator={}", refundId, refundRate, operatorId);
        return refund;
    }

    /**
     * 主管审批通过争议退款。
     *
     * <p>事务顺序严格按 01 §6.2：锁退款单 → 校验 PENDING_APPROVAL → 锁托管金 → 校验 HELD →
     * 金砖回账 → 更新退款单 SUCCESS（含佣金与打手收益）→ 托管金清零 →
     * 剩余>0 时结算给打手 → 订单置终态 → 写进度。</p>
     *
     * @param refundId  退款单 ID
     * @param approverId 审批人 ID
     * @param approver   审批人角色，必须 ADMIN / SUPER_ADMIN
     * @return 审批后的退款单
     */
    @Transactional(rollbackFor = Exception.class)
    public Refund approveDisputeRefund(Long refundId, Long approverId, Role approver) {
        if (approver == null || !approver.canApproveRefund()) {
            throw BizException.of(ErrorCode.REFUND_APPROVER_FORBIDDEN,
                    "非主管不能审批退款，请联系管理员处理");
        }
        Refund refund = refundMapper.selectForUpdate(refundId);
        if (refund == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "退款单不存在，请刷新列表");
        }
        if (!RefundStatus.PENDING_APPROVAL.name().equals(refund.getStatus())) {
            throw BizException.of(ErrorCode.STATE_CONFLICT,
                    "该退款单已处理（当前状态 " + refund.getStatus() + "），请刷新列表");
        }
        // 发起人不能审批自己的退款单（01 §6）
        if (approverId.equals(refund.getApplicantId())) {
            throw BizException.of(ErrorCode.REFUND_APPROVER_FORBIDDEN,
                    "不能审批自己发起的退款申请，请其他主管处理");
        }
        // 管理员发起时只能由超管审批（01 §6）
        if (Role.ADMIN.name().equals(refund.getApplicantType()) && approver != Role.SUPER_ADMIN) {
            throw BizException.of(ErrorCode.REFUND_APPROVER_FORBIDDEN,
                    "管理员发起的退款需由超级管理员审批");
        }

        Long orderId = refund.getOrderId();
        BizOrder order = lockOrder(orderId);
        if (!OrderStatus.DISPUTING.name().equals(order.getStatus())) {
            throw BizException.of(ErrorCode.STATE_CONFLICT,
                    "订单当前不在争议中（状态 " + order.getStatus() + "），无法审批退款");
        }

        OrderFundHold hold = lockHold(orderId);
        if (!HoldStatus.HELD.name().equals(hold.getStatus())) {
            throw BizException.of(ErrorCode.HOLD_EMPTY,
                    "托管金状态异常（" + hold.getStatus() + "），请联系管理员对账");
        }

        long refundAmount = nz(refund.getRefundAmount());
        long retained = nz(refund.getRetainedAmount());
        LocalDateTime now = LocalDateTime.now();

        // 金砖回账（幂等键 = 退款单号，重复审批不会二次加钱）
        boolean credited = goldAccountService.refundToGold(
                order.getCustomerId(), refundAmount, refund.getRefundNo(), orderId);
        if (!credited) {
            log.warn("退款金砖已入账过，按幂等继续处理 refundNo={}", refund.getRefundNo());
        }

        // 托管金清零：全额退置 REFUNDED，部分退置 PARTIAL_REFUNDED
        HoldStatus targetHoldStatus = HoldStatus.ofRefundRate(refund.getRefundRate());
        int holdRows = targetHoldStatus == HoldStatus.REFUNDED
                ? fundHoldMapper.refundAll(orderId, refundAmount, nz(hold.getRemainingAmount()), now)
                : fundHoldMapper.refundPartial(orderId, refundAmount, nz(hold.getRemainingAmount()), now);
        if (holdRows == 0) {
            throw BizException.of(ErrorCode.STATE_CONFLICT, "托管金已被处理，请刷新后重试");
        }

        // 佣金与打手收益最终确认。
        // 2026-10-29 口径：平台费只在确认收货后收取，争议退款（未确认）不扣平台费——
        // 剩余金额全归打手，platformFee = 0；settlePartialRefund 内部保证同一口径
        long commission = 0L;
        long boosterAmount = retained;

        // 剩余 > 0 时给打手结算（README §2.2：全额退款不产生结算）
        Settlement settlement = null;
        if (retained > 0) {
            settlement = settlementService.settlePartialRefund(order, refundAmount, retained);
            boosterAmount = settlement.getBoosterAmount();
            commission = settlement.getPlatformAmount();
        }

        // 订单置终态
        OrderStatus targetOrderStatus = RefundType.toOrderStatus(refund.getRefundRate());
        int orderRows = orderMapper.refundOrder(orderId, OrderStatus.DISPUTING.name(),
                targetOrderStatus.name(), refundAmount, truncate(refund.getReason()), now);
        if (orderRows == 0) {
            throw BizException.of(ErrorCode.STATE_CONFLICT, "订单状态已变更，请刷新后重试");
        }

        // 更新退款单为 SUCCESS
        refund.setStatus(RefundStatus.SUCCESS.name());
        refund.setCommissionAmount(commission);
        refund.setBoosterAmount(boosterAmount);
        refund.setApproverId(approverId);
        refund.setApprovedAt(now);
        refund.setCompletedAt(now);
        refund.setSettlementId(settlement == null ? null : settlement.getId());
        int rows = refundMapper.approve(refundId, RefundStatus.PENDING_APPROVAL.name(),
                approverId, commission, boosterAmount,
                settlement == null ? null : settlement.getId(), now);
        if (rows == 0) {
            throw BizException.of(ErrorCode.STATE_CONFLICT, "退款单已被处理，请刷新列表");
        }

        progressRecorder.record(orderId, OrderStatus.DISPUTING, targetOrderStatus,
                OrderAction.DISPUTE_APPROVE, OrderProgressRecorder.OP_ADMIN, approverId,
                "主管审批通过，退款 " + refund.getRefundRate() + "%（"
                        + MoneyUtil.fenToYuan(refundAmount) + " 元）已退回金砖余额"
                        + (retained > 0 ? "，剩余 " + MoneyUtil.fenToYuan(retained)
                        + " 元结算（打手 " + MoneyUtil.fenToYuan(boosterAmount) + " 元）" : ""));

        log.info("争议退款审批通过 orderId={} refundNo={} rate={}% refundAmount={}分 retained={}分 "
                        + "boosterAmount={}分 commission={}分 approverId={}",
                orderId, refund.getRefundNo(), refund.getRefundRate(), refundAmount, retained,
                boosterAmount, commission, approverId);

        imService.notify(refund.getCustomerId(), SiteMessage.TYPE_REFUND, "退款已到账",
                "订单 " + order.getOrderNo() + " 的退款 " + MoneyUtil.fenToYuan(refundAmount)
                        + " 元已退回你的金砖余额",
                "REFUND", refund.getId());
        return refund;
    }

    /**
     * 主管驳回争议退款：退款单置 REJECTED，订单回到 {@code previousOrderStatus}，<b>托管金不变</b>。
     *
     * @param refundId   退款单 ID
     * @param approverId 审批人 ID
     * @param approver   审批人角色
     * @param reason     驳回理由，必填
     * @return 驳回后的退款单
     */
    @Transactional(rollbackFor = Exception.class)
    public Refund rejectDisputeRefund(Long refundId, Long approverId, Role approver, String reason) {
        requireReason(reason);
        if (approver == null || !approver.canApproveRefund()) {
            throw BizException.of(ErrorCode.REFUND_APPROVER_FORBIDDEN,
                    "非主管不能审批退款，请联系管理员处理");
        }
        Refund refund = refundMapper.selectForUpdate(refundId);
        if (refund == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "退款单不存在，请刷新列表");
        }
        if (!RefundStatus.PENDING_APPROVAL.name().equals(refund.getStatus())) {
            throw BizException.of(ErrorCode.STATE_CONFLICT,
                    "该退款单已处理（当前状态 " + refund.getStatus() + "），请刷新列表");
        }
        if (approverId.equals(refund.getApplicantId())) {
            throw BizException.of(ErrorCode.REFUND_APPROVER_FORBIDDEN,
                    "不能审批自己发起的退款申请，请其他主管处理");
        }
        if (Role.ADMIN.name().equals(refund.getApplicantType()) && approver != Role.SUPER_ADMIN) {
            throw BizException.of(ErrorCode.REFUND_APPROVER_FORBIDDEN,
                    "管理员发起的退款需由超级管理员审批");
        }

        Long orderId = refund.getOrderId();
        BizOrder order = lockOrder(orderId);
        if (!OrderStatus.DISPUTING.name().equals(order.getStatus())) {
            throw BizException.of(ErrorCode.STATE_CONFLICT,
                    "订单当前不在争议中（状态 " + order.getStatus() + "），无法驳回退款");
        }

        // 回到发起争议前的原状态（01 §6.2「主管驳回」）
        OrderStatus restoreTo = refund.getPreviousOrderStatus() == null
                ? OrderStatus.IN_SERVICE
                : OrderStatus.of(refund.getPreviousOrderStatus());
        LocalDateTime now = LocalDateTime.now();

        int orderRows = orderMapper.transitStatus(orderId, OrderStatus.DISPUTING.name(),
                restoreTo.name(), now);
        if (orderRows == 0) {
            throw BizException.of(ErrorCode.STATE_CONFLICT, "订单状态已变更，请刷新后重试");
        }

        int rows = refundMapper.reject(refundId, RefundStatus.PENDING_APPROVAL.name(),
                approverId, truncate(reason), now);
        if (rows == 0) {
            throw BizException.of(ErrorCode.STATE_CONFLICT, "退款单已被处理，请刷新列表");
        }
        refund.setStatus(RefundStatus.REJECTED.name());
        refund.setApproverId(approverId);
        refund.setCompletedAt(now);

        progressRecorder.record(orderId, OrderStatus.DISPUTING, restoreTo,
                OrderAction.DISPUTE_REJECT, OrderProgressRecorder.OP_ADMIN, approverId,
                "主管驳回退款申请：" + reason + "（订单恢复为 " + restoreTo.name() + "，托管金不变）");

        log.info("争议退款已驳回 orderId={} refundNo={} restoreTo={} approverId={} reason={}",
                orderId, refund.getRefundNo(), restoreTo, approverId, reason);

        imService.notify(refund.getCustomerId(), SiteMessage.TYPE_REFUND, "退款申请未通过",
                "订单 " + order.getOrderNo() + " 的退款申请未通过：" + truncate(reason)
                        + "，订单已恢复原状态",
                "REFUND", refund.getId());
        return refund;
    }

    // ------------------------------------------------------------------
    // 管理端查询
    // ------------------------------------------------------------------

    /** 查询退款单（不存在抛 404）。 */
    public Refund requireById(Long refundId) {
        Refund refund = refundMapper.selectById(refundId);
        if (refund == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "退款单不存在，请刷新列表");
        }
        return refund;
    }

    /**
     * 管理端退款申请列表。
     *
     * <p><b>数据范围</b>（README §2.3）：客服只看自己发起的申请，管理员/超管看全部。
     * 该过滤由本方法实现，控制器只负责传角色。</p>
     *
     * @param applicantId 客服场景下传当前登录者 ID；管理员场景传 null
     */
    public IPage<Refund> listForAdmin(String status, Long applicantId, int page, int pageSize) {
        LambdaQueryWrapper<Refund> wrapper = new LambdaQueryWrapper<Refund>()
                .orderByDesc(Refund::getCreatedAt)
                .orderByDesc(Refund::getId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(Refund::getStatus, status);
        }
        if (applicantId != null) {
            wrapper.eq(Refund::getApplicantId, applicantId);
        }
        return refundMapper.selectPage(Page.of(Math.max(1, page), Math.max(1, pageSize)), wrapper);
    }

    /** 按订单查全部退款申请历史（订单详情与流水页用）。 */
    public List<Refund> listByOrderId(Long orderId) {
        return refundMapper.selectList(new LambdaQueryWrapper<Refund>()
                .eq(Refund::getOrderId, orderId)
                .orderByDesc(Refund::getCreatedAt));
    }

    /**
     * 客户仲裁通过后生成退款单。
     *
     * <p>调用时订单已经处于 DISPUTING（仲裁工单创建时转入），因此这里只落退款单，
     * 不再做状态迁移；随后由 {@link #approveDisputeRefund} 统一执行资金动作。</p>
     *
     * @param previousStatus 仲裁前的订单状态，用于退款单留痕
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY,
            rollbackFor = Exception.class)
    public Refund createArbitrationRefund(BizOrder order, int refundRate, String reason,
                                          String evidenceJson, Long customerId, String previousStatus) {
        long payAmount = nz(order.getPayAmount());
        long refundAmount = MoneyUtil.refundAmount(payAmount, refundRate);
        long retained = MoneyUtil.retained(payAmount, refundAmount);
        if (refundAmount <= 0) {
            throw BizException.of(ErrorCode.REFUND_RATE_INVALID, "按该比例计算退款金额为 0，请调整比例");
        }
        Refund refund = buildRefund(order, RefundType.ofRate(refundRate), refundRate,
                refundAmount, retained, OrderStatus.of(previousStatus), RefundStatus.PENDING_APPROVAL,
                "CUSTOMER", customerId, reason, evidenceJson, LocalDateTime.now());
        refund.setCommissionAmount(0L);
        refund.setBoosterAmount(retained);
        insertRefund(refund);
        return refund;
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private Refund buildRefund(BizOrder order, RefundType type, int refundRate, long refundAmount,
                               long retained, OrderStatus previousStatus, RefundStatus status,
                               String applicantType, Long applicantId, String reason,
                               String evidenceJson, LocalDateTime now) {
        Refund refund = new Refund();
        refund.setId(idGenerator.nextId());
        refund.setRefundNo(idGenerator.nextRefundNo());
        refund.setOrderId(order.getId());
        refund.setCustomerId(order.getCustomerId());
        refund.setType(type.name());
        refund.setRefundRate(refundRate);
        refund.setPayAmount(nz(order.getPayAmount()));
        refund.setRefundAmount(refundAmount);
        refund.setRetainedAmount(retained);
        refund.setCommissionAmount(0L);
        refund.setBoosterAmount(0L);
        refund.setPreviousOrderStatus(previousStatus == null ? null : previousStatus.name());
        refund.setReason(truncate(reason));
        refund.setEvidenceJson(evidenceJson);
        refund.setStatus(status.name());
        refund.setApplicantType(applicantType);
        refund.setApplicantId(applicantId);
        refund.setCreatedAt(now);
        return refund;
    }

    private void insertRefund(Refund refund) {
        try {
            refundMapper.insert(refund);
        } catch (DuplicateKeyException ex) {
            // uk_order_active 命中：同一订单已存在活跃退款单（并发提交）
            log.info("退款单并发冲突，已存在活跃退款单 orderId={} refundNo={}",
                    refund.getOrderId(), refund.getRefundNo());
            throw BizException.of(ErrorCode.REFUND_PENDING_EXISTS,
                    "该订单已有退款申请正在处理，请勿重复提交");
        }
    }

    private BizOrder lockOrder(Long orderId) {
        BizOrder order = orderMapper.selectForUpdate(orderId);
        if (order == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "订单不存在，请返回列表刷新");
        }
        return order;
    }

    private OrderFundHold lockHold(Long orderId) {
        OrderFundHold hold = fundHoldMapper.selectForUpdateByOrderId(orderId);
        if (hold == null) {
            throw BizException.of(ErrorCode.HOLD_EMPTY, "订单托管金记录不存在，请联系客服");
        }
        return hold;
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "请填写退款原因");
        }
    }

    /** reason 列上限 255，超长截断以免插入失败。 */
    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 255 ? value : value.substring(0, 255);
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }
}
