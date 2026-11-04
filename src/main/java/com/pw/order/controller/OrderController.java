package com.pw.order.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.pw.common.enums.Role;
import com.pw.common.idempotent.Idempotent;
import com.pw.common.result.PageResult;
import com.pw.common.result.Result;
import com.pw.common.security.LoginUser;
import com.pw.common.security.RequireRole;
import com.pw.common.security.SecurityContexts;
import com.pw.order.dto.CreateOrderReq;
import com.pw.order.dto.ComplaintDto;
import com.pw.order.dto.OrderDto;
import com.pw.order.dto.OrderReq;
import com.pw.order.entity.BizOrder;
import com.pw.order.entity.OrderProgress;
import com.pw.order.service.ArbitrationService;
import com.pw.order.service.OrderCreateService;
import com.pw.order.service.OrderEvidenceService;
import com.pw.order.service.OrderLifecycleService;
import com.pw.order.service.OrderProgressRecorder;
import com.pw.order.service.OrderQueryService;
import com.pw.refund.entity.Refund;
import com.pw.refund.service.RefundService;
import com.pw.settlement.entity.Settlement;
import com.pw.settlement.service.SettlementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端订单接口。对应 04 §3.4。
 *
 * <p><b>Controller 不写业务</b>（01 §1）：只做登录态提取、参数校验与结果包装，
 * 资金与状态流转全在 Service。</p>
 *
 * <p><b>身份来源</b>：客户 ID 一律从登录态 {@code subjectId} 取，
 * <b>绝不从请求体取</b>，防止越权下单/退款他人订单。</p>
 */
@Tag(name = "04-订单（客户）", description = "下单、查询、退款、确认收货")
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
@RequireRole(Role.CUSTOMER)
public class OrderController {

    private final OrderCreateService orderCreateService;
    private final OrderLifecycleService lifecycleService;
    private final OrderQueryService queryService;
    private final OrderEvidenceService evidenceService;
    private final OrderProgressRecorder progressRecorder;
    private final RefundService refundService;
    private final SettlementService settlementService;
    private final ArbitrationService arbitrationService;

    @Operation(summary = "创建订单",
            description = "下单即扣金砖（非冻结），需带 Idempotency-Key，重复提交返回同一订单")
    @PostMapping
    @Idempotent(scene = "CREATE_ORDER")
    public Result<OrderDto.Detail> create(@Valid @RequestBody CreateOrderReq req) {
        LoginUser me = SecurityContexts.requireCustomer();
        OrderCreateService.CreateOrderCommand command = new OrderCreateService.CreateOrderCommand(
                me.getSubjectId(),
                req.getCategoryId(),
                req.getSkuId(),
                req.getMode(),
                req.getBoosterId(),
                req.getQuantity(),
                req.getAppointAt(),
                req.getGameProfileId(),
                req.getBossGameId(),
                req.getRequirement());
        BizOrder order = orderCreateService.create(command);
        return Result.ok(queryService.toDetail(order, Role.CUSTOMER, me.getSubjectId()));
    }

    @Operation(summary = "我的订单列表", description = "按状态过滤；不传 status 返回全部")
    @GetMapping
    public Result<PageResult<OrderDto.Summary>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        LoginUser me = SecurityContexts.requireCustomer();
        int limit = clamp(pageSize);
        IPage<BizOrder> orders = queryService.listByCustomer(me.getSubjectId(), status, page, limit);
        List<OrderDto.Summary> items = new ArrayList<>(orders.getRecords().size());
        for (BizOrder order : orders.getRecords()) {
            items.add(queryService.toSummary(order, Role.CUSTOMER, me.getSubjectId()));
        }
        return Result.ok(PageResult.from(orders, items));
    }

    @Operation(summary = "订单详情")
    @GetMapping("/{id}")
    public Result<OrderDto.Detail> detail(@PathVariable Long id) {
        LoginUser me = SecurityContexts.requireCustomer();
        BizOrder order = queryService.requireOrder(id);
        return Result.ok(queryService.toDetail(order, Role.CUSTOMER, me.getSubjectId()));
    }

    @Operation(summary = "申请退款",
            description = "仅服务开始前（PENDING_ACCEPT/ACCEPTED）可自助全额退；服务开始后返回 60014，需联系客服")
    @PostMapping("/{id}/refund")
    public Result<Refund> refund(@PathVariable Long id, @Valid @RequestBody OrderReq.RefundReq req) {
        LoginUser me = SecurityContexts.requireCustomer();
        Refund refund = refundService.customerSelfRefund(id, me.getSubjectId(), req.getReason());
        return Result.ok(refund);
    }

    @Operation(summary = "确认收货",
            description = "PENDING_CONFIRM → COMPLETED 并立即结算；争议中不可确认")
    @PostMapping("/{id}/confirm")
    public Result<OrderDto.Detail> confirm(@PathVariable Long id) {
        LoginUser me = SecurityContexts.requireCustomer();
        OrderLifecycleService.ConfirmResult result =
                lifecycleService.confirmByCustomer(id, me.getSubjectId());
        return Result.ok(queryService.toDetail(result.order(), Role.CUSTOMER, me.getSubjectId()));
    }

    @Operation(summary = "订单时间线", description = "全部状态变更轨迹")
    @GetMapping("/{id}/timeline")
    public Result<List<OrderDto.Progress>> timeline(@PathVariable Long id) {
        LoginUser me = SecurityContexts.requireCustomer();
        // 先校验归属，避免越权读他人订单轨迹
        BizOrder order = queryService.requireOrder(id);
        queryService.toSummary(order, Role.CUSTOMER, me.getSubjectId());
        List<OrderProgress> progresses = progressRecorder.listByOrderId(id);
        List<OrderDto.Progress> items = new ArrayList<>(progresses.size());
        for (OrderProgress p : progresses) {
            items.add(OrderDto.Progress.builder()
                    .fromStatus(p.getFromStatus())
                    .toStatus(p.getToStatus())
                    .action(p.getAction())
                    .operatorType(p.getOperatorType())
                    .remark(p.getRemark())
                    .createdAt(p.getCreatedAt())
                    .build());
        }
        return Result.ok(items);
    }

    @Operation(summary = "订单凭证", description = "开局照/结束照/补充凭证")
    @GetMapping("/{id}/evidence")
    public Result<List<OrderDto.Evidence>> evidence(@PathVariable Long id) {
        LoginUser me = SecurityContexts.requireCustomer();
        BizOrder order = queryService.requireOrder(id);
        queryService.toSummary(order, Role.CUSTOMER, me.getSubjectId());
        List<OrderDto.Evidence> items = new ArrayList<>();
        for (com.pw.order.entity.OrderEvidence e : evidenceService.listByOrderId(id)) {
            items.add(OrderDto.Evidence.builder()
                    .id(e.getId())
                    .type(e.getType())
                    .url(e.getObjectKey())
                    .takenAt(e.getTakenAt())
                    .supplement(Integer.valueOf(1).equals(e.getSupplement()))
                    .build());
        }
        return Result.ok(items);
    }

    @Operation(summary = "订单结算信息", description = "仅已结算订单有数据，否则返回 null")
    @GetMapping("/{id}/settlement")
    public Result<OrderDto.SettlementInfo> settlement(@PathVariable Long id) {
        LoginUser me = SecurityContexts.requireCustomer();
        BizOrder order = queryService.requireOrder(id);
        queryService.toSummary(order, Role.CUSTOMER, me.getSubjectId());
        Settlement settlement = settlementService.findByOrderId(id);
        if (settlement == null) {
            return Result.ok(null);
        }
        return Result.ok(OrderDto.SettlementInfo.builder()
                .orderId(settlement.getOrderId())
                .type(settlement.getType())
                .originalAmount(settlement.getOriginalAmount())
                .payAmount(settlement.getPayAmount())
                .refundAmount(settlement.getRefundAmount())
                .settlementBase(settlement.getSettlementBase())
                .boosterAmount(settlement.getBoosterAmount())
                .platformAmount(settlement.getPlatformAmount())
                .settledAt(settlement.getSettledAt())
                .build());
    }

    @Operation(summary = "申请仲裁",
            description = "仅服务中/待确认可发起；钱仍在托管，管理员决定扣款比例")
    @PostMapping("/{id}/arbitration")
    public Result<ComplaintDto.View> arbitration(@PathVariable Long id,
                                                 @Valid @RequestBody OrderReq.ArbitrationReq req) {
        LoginUser me = SecurityContexts.requireCustomer();
        return Result.ok(arbitrationService.create(id, me.getSubjectId(), req.getReason(),
                req.getEvidenceKeys()));
    }

    @Operation(summary = "查看仲裁", description = "返回该订单最近一次仲裁，没有返回 null")
    @GetMapping("/{id}/arbitration")
    public Result<ComplaintDto.View> arbitrationDetail(@PathVariable Long id) {
        LoginUser me = SecurityContexts.requireCustomer();
        return Result.ok(arbitrationService.my(id, me.getSubjectId()));
    }

    @Operation(summary = "催单", description = "只留痕不改状态；已结束的订单不可催")
    @PostMapping("/{id}/urge")
    public Result<Void> urge(@PathVariable Long id) {
        LoginUser me = SecurityContexts.requireCustomer();
        lifecycleService.urge(id, me.getSubjectId());
        return Result.ok();
    }

    private static int clamp(int pageSize) {
        return Math.max(1, Math.min(pageSize, 200));
    }
}
