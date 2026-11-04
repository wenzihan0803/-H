package com.pw.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pw.booster.entity.Booster;
import com.pw.booster.mapper.BoosterMapper;
import com.pw.catalog.entity.ServiceCategory;
import com.pw.catalog.entity.ServiceSku;
import com.pw.catalog.mapper.ServiceCategoryMapper;
import com.pw.catalog.mapper.ServiceSkuMapper;
import com.pw.common.enums.OrderAction;
import com.pw.common.enums.OrderStatus;
import com.pw.common.enums.Role;
import com.pw.common.exception.BizException;
import com.pw.common.exception.ErrorCode;
import com.pw.common.util.CryptoUtil;
import com.pw.file.service.FileStorageService;
import com.pw.order.dto.OrderDto;
import com.pw.order.entity.BizOrder;
import com.pw.order.entity.HandoverLog;
import com.pw.order.entity.HandoverTask;
import com.pw.order.entity.OrderEvidence;
import com.pw.order.entity.OrderFundHold;
import com.pw.order.entity.OrderProgress;
import com.pw.order.entity.OrderTerms;
import com.pw.order.mapper.BizOrderMapper;
import com.pw.order.mapper.ComplaintMapper;
import com.pw.order.mapper.HandoverLogMapper;
import com.pw.order.mapper.HandoverTaskMapper;
import com.pw.order.mapper.OrderEvidenceMapper;
import com.pw.order.mapper.OrderFundHoldMapper;
import com.pw.order.mapper.OrderTermsMapper;
import com.pw.refund.entity.Refund;
import com.pw.refund.mapper.RefundMapper;
import com.pw.settlement.entity.Settlement;
import com.pw.settlement.service.FeePolicy;
import com.pw.settlement.mapper.SettlementMapper;
import com.pw.user.entity.User;
import com.pw.user.mapper.UserMapper;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 订单查询与 DTO 装配服务。
 *
 * <p>只读，不含任何资金变更。把 {@code BizOrder} + 关联表装配成 04 §2.4 定义的
 * {@code OrderSummary} / {@code OrderDetail} 结构。</p>
 *
 * <p><b>数据权限</b>（04 §3.4/§3.6）：</p>
 * <ul>
 *   <li>客户：只能看自己的订单，可见 requirement、bossGameId、完整金额；</li>
 *   <li>打手：<b>看不到客户区服、备注、手机号</b>（04 §3.6），
 *       只能看客服下发的 bossGameId 与 guide，且需交接 SENT 之后；</li>
 *   <li>管理端：按角色可见全部（流水页仅管理员/超管，见 README §2.3）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderQueryService {

    private final BizOrderMapper orderMapper;
    private final OrderFundHoldMapper fundHoldMapper;
    private final OrderEvidenceMapper evidenceMapper;
    private final HandoverTaskMapper handoverTaskMapper;
    private final HandoverLogMapper handoverLogMapper;
    private final OrderTermsMapper orderTermsMapper;
    private final OrderProgressRecorder progressRecorder;
    private final RefundMapper refundMapper;
    private final SettlementMapper settlementMapper;
    private final ServiceCategoryMapper categoryMapper;
    private final ServiceSkuMapper skuMapper;
    private final BoosterMapper boosterMapper;
    private final UserMapper userMapper;
    private final CryptoUtil cryptoUtil;
    private final FileStorageService fileStorage;
    private final FeePolicy feePolicy;
    private final ComplaintMapper complaintMapper;
    private final com.pw.common.config.BusinessProperties business;
    private final ObjectMapper objectMapper;

    /**
     * 装配订单详情。
     *
     * @param order  订单
     * @param viewer 查看者角色，决定字段可见性
     * @param viewerId 查看者 ID（客户 ID 或打手 ID），用于越权校验
     */
    public OrderDto.Detail toDetail(BizOrder order, Role viewer, Long viewerId) {
        checkViewPermission(order, viewer, viewerId);

        ServiceCategory category = categoryMapper.selectById(order.getCategoryId());
        ServiceSku sku = skuMapper.selectById(order.getSkuId());
        OrderFundHold hold = fundHoldMapper.selectByOrderId(order.getId());
        HandoverTask task = handoverTaskMapper.selectByOrderId(order.getId());
        Settlement settlement = settlementMapper.selectByOrderId(order.getId());
        List<Refund> refunds = refundMapper.selectList(new LambdaQueryWrapper<Refund>()
                .eq(Refund::getOrderId, order.getId())
                .orderByDesc(Refund::getCreatedAt));
        List<OrderEvidence> evidences = evidenceMapper.selectList(new LambdaQueryWrapper<OrderEvidence>()
                .eq(OrderEvidence::getOrderId, order.getId())
                .orderByAsc(OrderEvidence::getCreatedAt));
        List<OrderProgress> progresses = progressRecorder.listByOrderId(order.getId());

        OrderStatus status = OrderStatus.of(order.getStatus());
        boolean isCustomer = viewer == Role.CUSTOMER;
        boolean isBooster = viewer == Role.BOOSTER;

        return OrderDto.Detail.builder()
                .id(order.getId())
                .orderNo(order.getOrderNo())
                .status(order.getStatus())
                .categoryName(category == null ? null : category.getName())
                .skuName(sku == null ? null : sku.getName())
                .unit(sku == null ? null : sku.getUnit())
                .mode(order.getMode())
                .quantity(order.getQuantity())
                .booster(isCustomer ? null : buildBoosterBrief(order.getBoosterId()))
                .amounts(buildAmounts(order, settlement, viewer))
                .hold(buildHold(hold))
                .timestamps(buildTimestamps(order))
                .handover(buildHandover(order, task, viewer))
                .evidence(toEvidenceList(evidences))
                .progress(toProgressList(progresses))
                .settlement(isCustomer ? null : toSettlementInfo(settlement))
                .refunds(toRefundList(refunds, viewer))
                // 备注只给客户与管理端，打手不可见（04 §3.6）
                .requirement(isBooster ? null : decryptRequirement(order))
                .canRefund(isCustomer && status.canCustomerSelfRefund())
                .canRequestDispute(status.canDisputeRefund()
                        && complaintMapper.selectActiveByOrderId(order.getId()) == null)
                .canConfirm(isCustomer && status == OrderStatus.PENDING_CONFIRM)
                .canReview(isCustomer && status == OrderStatus.COMPLETED)
                .canStart(isBooster && status == OrderStatus.ACCEPTED
                        && "ACK".equals(order.getHandoverStatus()))
                .canComplete(isBooster && status == OrderStatus.IN_SERVICE)
                .build();
    }

    /** 装配订单摘要（列表页）。 */
    public OrderDto.Summary toSummary(BizOrder order, Role viewer, Long viewerId) {
        checkViewPermission(order, viewer, viewerId);
        ServiceCategory category = categoryMapper.selectById(order.getCategoryId());
        ServiceSku sku = skuMapper.selectById(order.getSkuId());
        OrderFundHold hold = fundHoldMapper.selectByOrderId(order.getId());
        OrderStatus status = OrderStatus.of(order.getStatus());

        return OrderDto.Summary.builder()
                .id(order.getId())
                .orderNo(order.getOrderNo())
                .status(order.getStatus())
                .categoryName(category == null ? null : category.getName())
                .skuName(sku == null ? null : sku.getName())
                .unit(sku == null ? null : sku.getUnit())
                .quantity(order.getQuantity())
                .booster(viewer == Role.CUSTOMER ? null : buildBoosterBrief(order.getBoosterId()))
                .payAmount(nz(order.getPayAmount()))
                .holdAmount(hold == null ? 0L : nz(hold.getRemainingAmount()))
                .canRefund(viewer == Role.CUSTOMER && status.canCustomerSelfRefund())
                .canRequestDispute(status.canDisputeRefund())
                .canConfirm(viewer == Role.CUSTOMER && status == OrderStatus.PENDING_CONFIRM)
                .createdAt(order.getCreatedAt())
                .appointAt(order.getAppointAt())
                .build();
    }

    /** 装配大厅订单（打手端抢单列表）。 */
    public OrderDto.HallOrder toHallOrder(BizOrder order, Long viewerBoosterId, String tab) {
        ServiceCategory category = categoryMapper.selectById(order.getCategoryId());
        ServiceSku sku = skuMapper.selectById(order.getSkuId());
        // 打手到手预估 = 实付 − 平台费（会员单按 10%，非会员 15%）
        long estimated = feePolicy.estimateBoosterAmount(
                nz(order.getOriginalAmount()), nz(order.getPayAmount()), order.getCustomerId());

        return OrderDto.HallOrder.builder()
                .id(order.getId())
                .orderNo(order.getOrderNo())
                .categoryName(category == null ? null : category.getName())
                .skuName(sku == null ? null : sku.getName())
                .unit(sku == null ? null : sku.getUnit())
                .quantity(order.getQuantity())
                .estimatedEarning(estimated)
                .appointAt(order.getAppointAt())
                .createdAt(order.getCreatedAt())
                .tab(tab)
                .build();
    }

    // ------------------------------------------------------------------
    // 权限校验
    // ------------------------------------------------------------------

    /**
     * 校验查看权限：客户只能看自己的单，打手只能看自己接的单，管理端放行。
     */
    private void checkViewPermission(BizOrder order, Role viewer, Long viewerId) {
        if (viewer == null) {
            throw BizException.of(ErrorCode.UNAUTHORIZED, "登录已过期，请重新登录");
        }
        if (viewer.isAdminSide()) {
            return;
        }
        if (viewer == Role.CUSTOMER) {
            if (!order.getCustomerId().equals(viewerId)) {
                throw BizException.of(ErrorCode.FORBIDDEN, "无权查看该订单");
            }
            return;
        }
        if (viewer == Role.BOOSTER) {
            // 大厅单在接单前允许打手浏览（抢单需要），已接单的只能本人看
            if (order.getBoosterId() == null
                    && OrderStatus.PENDING_ACCEPT.name().equals(order.getStatus())) {
                return;
            }
            if (!order.getBoosterId().equals(viewerId)) {
                throw BizException.of(ErrorCode.FORBIDDEN, "无权查看该订单");
            }
            return;
        }
        throw BizException.of(ErrorCode.FORBIDDEN, "无权查看该订单");
    }

    // ------------------------------------------------------------------
    // 装配辅助
    // ------------------------------------------------------------------

    private OrderDto.Amounts buildAmounts(BizOrder order, Settlement settlement, Role viewer) {
        // 客户端不看打手分成与平台费（2026-10-31 口径），字段直接不下发
        boolean hideShare = viewer == Role.CUSTOMER;
        return OrderDto.Amounts.builder()
                .originalAmount(nz(order.getOriginalAmount()))
                .memberDiscount(nz(order.getMemberDiscount()))
                .payAmount(nz(order.getPayAmount()))
                .boosterAmount(hideShare ? null
                        : (settlement == null ? order.getBoosterAmount() : settlement.getBoosterAmount()))
                .platformAmount(hideShare ? null
                        : (settlement == null ? order.getPlatformAmount() : settlement.getPlatformAmount()))
                .build();
    }

    private OrderDto.HoldInfo buildHold(OrderFundHold hold) {
        if (hold == null) {
            return null;
        }
        return OrderDto.HoldInfo.builder()
                .amount(nz(hold.getAmount()))
                .remainingAmount(nz(hold.getRemainingAmount()))
                .refundedAmount(nz(hold.getRefundedAmount()))
                .status(hold.getStatus())
                .heldAt(hold.getHeldAt())
                .refundedAt(hold.getRefundedAt())
                .build();
    }

    private OrderDto.Timestamps buildTimestamps(BizOrder order) {
        return OrderDto.Timestamps.builder()
                .createdAt(order.getCreatedAt())
                .appointAt(order.getAppointAt())
                .serviceCompletedAt(order.getServiceCompletedAt())
                .confirmDeadline(order.getConfirmDeadline())
                .confirmedAt(order.getConfirmedAt())
                .refundedAt(order.getRefundedAt())
                .build();
    }

    /**
     * 装配交接信息，按角色控制 bossGameId 可见性（04 §2.4、§3.6）。
     *
     * <p>上号指引 {@code guide} 与老板游戏 ID 密文存在 {@code handover_log}（不在 task 表），
     * 取最近一条日志。打手端只有在客服 SENT 之后才能看到老板游戏 ID；PENDING 阶段返回 null。</p>
     */
    private OrderDto.HandoverInfo buildHandover(BizOrder order, HandoverTask task, Role viewer) {
        HandoverLog latestLog = handoverLogMapper.selectLatestByOrderId(order.getId());
        if (task == null && latestLog == null) {
            return OrderDto.HandoverInfo.builder()
                    .status(order.getHandoverStatus())
                    .build();
        }
        String status = task != null ? task.getStatus() : order.getHandoverStatus();
        boolean sent = !"PENDING".equals(status);
        // 打手端：SENT 之后才解密返回；客户与管理端：始终可见
        boolean canSeeBossId = viewer != Role.BOOSTER || sent;
        String bossGameId = null;
        // 优先用日志里的密文（客服下发时写入），回退到订单上的密文
        String cipher = latestLog != null && latestLog.getBossIdCipher() != null
                ? latestLog.getBossIdCipher() : order.getBossGameIdCipher();
        if (canSeeBossId && cipher != null) {
            try {
                bossGameId = cryptoUtil.decrypt(cipher);
            } catch (Exception ex) {
                // 解密失败不阻断详情返回，只记日志（dev 环境密钥变更时可能出现）
                log.warn("老板游戏 ID 解密失败 orderId={}", order.getId(), ex);
            }
        }
        return OrderDto.HandoverInfo.builder()
                .status(status)
                .bossGameId(bossGameId)
                .guide(latestLog == null ? null : latestLog.getGuide())
                .sentAt(task == null ? null : task.getSentAt())
                .ackAt(task == null ? null : task.getAckAt())
                .dueAt(task == null ? null : task.getDueAt())
                .build();
    }

    private OrderDto.BoosterBrief buildBoosterBrief(Long boosterId) {
        if (boosterId == null) {
            return null;
        }
        Booster booster = boosterMapper.selectById(boosterId);
        if (booster == null) {
            return null;
        }
        User user = userMapper.selectById(booster.getUserId());
        return OrderDto.BoosterBrief.builder()
                .id(booster.getId())
                .name(user == null ? null : user.getNickname())
                .avatar(user == null ? null : user.getAvatar())
                .build();
    }

    private List<OrderDto.Evidence> toEvidenceList(List<OrderEvidence> evidences) {
        List<OrderDto.Evidence> list = new ArrayList<>(evidences.size());
        for (OrderEvidence e : evidences) {
            list.add(OrderDto.Evidence.builder()
                    .id(e.getId())
                    .type(e.getType())
                    // 返回可直接展示的绝对地址（按当前请求域名拼接，真机自适应）
                    .url(fileStorage.urlOf(e.getObjectKey()))
                    .takenAt(e.getTakenAt())
                    .supplement(Integer.valueOf(1).equals(e.getSupplement()))
                    .build());
        }
        return list;
    }

    private List<OrderDto.Progress> toProgressList(List<OrderProgress> progresses) {
        List<OrderDto.Progress> list = new ArrayList<>(progresses.size());
        for (OrderProgress p : progresses) {
            list.add(OrderDto.Progress.builder()
                    .fromStatus(p.getFromStatus())
                    .toStatus(p.getToStatus())
                    .action(p.getAction())
                    .operatorType(p.getOperatorType())
                    .remark(p.getRemark())
                    .createdAt(p.getCreatedAt())
                    .build());
        }
        return list;
    }

    private OrderDto.SettlementInfo toSettlementInfo(Settlement settlement) {
        if (settlement == null) {
            return null;
        }
        return OrderDto.SettlementInfo.builder()
                .orderId(settlement.getOrderId())
                .type(settlement.getType())
                .originalAmount(nz(settlement.getOriginalAmount()))
                .payAmount(nz(settlement.getPayAmount()))
                .refundAmount(nz(settlement.getRefundAmount()))
                .settlementBase(nz(settlement.getSettlementBase()))
                .boosterAmount(nz(settlement.getBoosterAmount()))
                .platformAmount(nz(settlement.getPlatformAmount()))
                .platformRate(settlement.getPlatformRate())
                .memberFlag(settlement.getMemberFlag())
                .settledAt(settlement.getSettledAt())
                .build();
    }

    /**
     * 装配退款列表。打手端不返回退款明细金额拆分（佣金/打手收益），只给状态与金额。
     */
    private List<OrderDto.RefundInfo> toRefundList(List<Refund> refunds, Role viewer) {
        List<OrderDto.RefundInfo> list = new ArrayList<>(refunds.size());
        boolean isBooster = viewer == Role.BOOSTER;
        for (Refund r : refunds) {
            list.add(OrderDto.RefundInfo.builder()
                    .id(r.getId())
                    .refundNo(r.getRefundNo())
                    .orderId(r.getOrderId())
                    .type(r.getType())
                    .refundRate(r.getRefundRate())
                    .payAmount(nz(r.getPayAmount()))
                    .refundAmount(nz(r.getRefundAmount()))
                    .retainedAmount(isBooster ? null : nz(r.getRetainedAmount()))
                    .commissionAmount(isBooster ? null : nz(r.getCommissionAmount()))
                    .boosterAmount(isBooster ? null : nz(r.getBoosterAmount()))
                    .previousOrderStatus(r.getPreviousOrderStatus())
                    .status(r.getStatus())
                    .reason(r.getReason())
                    .applicantType(r.getApplicantType())
                    .applicantId(isBooster ? null : r.getApplicantId())
                    .approverId(isBooster ? null : r.getApproverId())
                    .approvedAt(r.getApprovedAt())
                    .createdAt(r.getCreatedAt())
                    .completedAt(r.getCompletedAt())
                    .build());
        }
        return list;
    }

    /**
     * 从条款快照 {@code order_terms.content_json} 解析需求备注。
     *
     * <p>下单时 requirement 固化进条款快照（{@code OrderCreateService.buildTermsJson}），
     * {@code biz_order} 表无该列（05 §4.3）。这里读回并解析。
     * 打手端不调用本方法（详情装配已按角色置 null）。</p>
     *
     * @return 需求备注；无条款、无备注或解析失败时返回 null，不编造内容
     */
    private String decryptRequirement(BizOrder order) {
        OrderTerms terms = orderTermsMapper.selectOne(new LambdaQueryWrapper<OrderTerms>()
                .eq(OrderTerms::getOrderId, order.getId())
                .last("LIMIT 1"));
        if (terms == null || terms.getContentJson() == null || terms.getContentJson().isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(terms.getContentJson());
            JsonNode req = node.get("requirement");
            if (req == null || req.isNull()) {
                return null;
            }
            String value = req.asText();
            return value.isBlank() ? null : value;
        } catch (Exception ex) {
            // 快照损坏不应阻断详情返回，只记日志
            log.warn("条款快照解析失败 orderId={}", order.getId(), ex);
            return null;
        }
    }

    /** 查询订单（不存在抛 404）。 */
    public BizOrder requireOrder(Long orderId) {
        BizOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "订单不存在，请返回列表刷新");
        }
        return order;
    }

    /** 按客户分页查订单（page 从 1 开始，OFFSET 由分页插件按真实总数计算）。 */
    public IPage<BizOrder> listByCustomer(Long customerId, String status, int page, int pageSize) {
        LambdaQueryWrapper<BizOrder> wrapper = new LambdaQueryWrapper<BizOrder>()
                .eq(BizOrder::getCustomerId, customerId)
                .orderByDesc(BizOrder::getCreatedAt)
                .orderByDesc(BizOrder::getId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(BizOrder::getStatus, status);
        }
        return orderMapper.selectPage(Page.of(Math.max(1, page), Math.max(1, pageSize)), wrapper);
    }

    /** 按打手分页查订单。 */
    public IPage<BizOrder> listByBooster(Long boosterId, String status, int page, int pageSize) {
        LambdaQueryWrapper<BizOrder> wrapper = new LambdaQueryWrapper<BizOrder>()
                .eq(BizOrder::getBoosterId, boosterId)
                .orderByDesc(BizOrder::getCreatedAt)
                .orderByDesc(BizOrder::getId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(BizOrder::getStatus, status);
        }
        return orderMapper.selectPage(Page.of(Math.max(1, page), Math.max(1, pageSize)), wrapper);
    }

    /**
     * 大厅可抢订单：PENDING_ACCEPT 且未指派打手，或指派给当前打手的单。
     */
    public IPage<BizOrder> listHallAvailable(Long viewerBoosterId, int page, int pageSize) {
        LambdaQueryWrapper<BizOrder> wrapper = new LambdaQueryWrapper<BizOrder>()
                .eq(BizOrder::getStatus, OrderStatus.PENDING_ACCEPT.name())
                .orderByAsc(BizOrder::getAppointAt)
                .orderByAsc(BizOrder::getId)
                .and(w -> w.isNull(BizOrder::getBoosterId)
                        .or().eq(BizOrder::getBoosterId, viewerBoosterId));
        return orderMapper.selectPage(Page.of(Math.max(1, page), Math.max(1, pageSize)), wrapper);
    }

    /**
     * 管理端订单查询：订单号 / 客户 / 打手ID / 打手昵称（模糊）/ 手机号（精确）/
     * 状态 / 下单时间区间 / 完成时间区间。
     */
    public IPage<BizOrder> listForAdmin(String orderNo, Long customerId, Long boosterId,
                                        String boosterName, String phone, String status,
                                        LocalDate createdFrom, LocalDate createdTo,
                                        LocalDate completedFrom, LocalDate completedTo,
                                        int page, int pageSize) {
        LambdaQueryWrapper<BizOrder> wrapper = new LambdaQueryWrapper<BizOrder>()
                .orderByDesc(BizOrder::getCreatedAt)
                .orderByDesc(BizOrder::getId);
        if (orderNo != null && !orderNo.isBlank()) {
            wrapper.eq(BizOrder::getOrderNo, orderNo);
        }
        if (customerId != null) {
            wrapper.eq(BizOrder::getCustomerId, customerId);
        }
        if (boosterId != null) {
            wrapper.eq(BizOrder::getBoosterId, boosterId);
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(BizOrder::getStatus, status);
        }
        if (createdFrom != null) {
            wrapper.ge(BizOrder::getCreatedAt, createdFrom.atStartOfDay());
        }
        if (createdTo != null) {
            wrapper.lt(BizOrder::getCreatedAt, createdTo.plusDays(1).atStartOfDay());
        }
        if (completedFrom != null) {
            wrapper.ge(BizOrder::getConfirmedAt, completedFrom.atStartOfDay());
        }
        if (completedTo != null) {
            wrapper.lt(BizOrder::getConfirmedAt, completedTo.plusDays(1).atStartOfDay());
        }
        if ((boosterName != null && !boosterName.isBlank()) || (phone != null && !phone.isBlank())) {
            List<Long> ids = resolveBoosterIds(boosterName, phone);
            if (ids.isEmpty()) {
                return Page.of(Math.max(1, page), Math.max(1, pageSize));
            }
            wrapper.in(BizOrder::getBoosterId, ids);
        }
        return orderMapper.selectPage(Page.of(Math.max(1, page), Math.max(1, pageSize)), wrapper);
    }

    /** 打手昵称模糊 / 手机号精确 → 打手 ID 集合。 */
    private List<Long> resolveBoosterIds(String boosterName, String phone) {
        LambdaQueryWrapper<com.pw.booster.entity.Booster> wrapper =
                new LambdaQueryWrapper<com.pw.booster.entity.Booster>();
        if (boosterName != null && !boosterName.isBlank()) {
            wrapper.like(com.pw.booster.entity.Booster::getNickname, boosterName.trim());
        }
        if (phone != null && !phone.isBlank()) {
            com.pw.user.entity.User user = userMapper.selectOne(new LambdaQueryWrapper<com.pw.user.entity.User>()
                    .eq(com.pw.user.entity.User::getPhoneHash, cryptoUtil.sha256(phone.trim()))
                    .last("LIMIT 1"));
            if (user == null) {
                return List.of();
            }
            wrapper.eq(com.pw.booster.entity.Booster::getUserId, user.getId());
        }
        return boosterMapper.selectList(wrapper).stream()
                .map(com.pw.booster.entity.Booster::getId)
                .toList();
    }

    /** 打手统计：今日/本月完成单数。 */
    public long[] boosterStats(Long boosterId) {
        java.time.LocalDateTime todayStart = java.time.LocalDate.now().atStartOfDay();
        java.time.LocalDateTime monthStart = java.time.LocalDate.now().withDayOfMonth(1).atStartOfDay();
        long today = countCompletedSince(boosterId, todayStart);
        long month = countCompletedSince(boosterId, monthStart);
        return new long[]{today, month};
    }

    private long countCompletedSince(Long boosterId, java.time.LocalDateTime since) {
        Long count = orderMapper.selectCount(new LambdaQueryWrapper<BizOrder>()
                .eq(BizOrder::getBoosterId, boosterId)
                .in(BizOrder::getStatus, OrderStatus.COMPLETED.name(),
                        OrderStatus.PARTIAL_REFUNDED.name())
                .ge(BizOrder::getConfirmedAt, since));
        return count == null ? 0L : count;
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }
}
