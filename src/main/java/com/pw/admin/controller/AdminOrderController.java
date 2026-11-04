package com.pw.admin.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.pw.admin.dto.AdminDto;
import com.pw.booster.entity.Booster;
import com.pw.booster.mapper.BoosterMapper;
import com.pw.common.enums.Role;
import com.pw.common.exception.BizException;
import com.pw.common.exception.ErrorCode;
import com.pw.common.result.PageResult;
import com.pw.common.result.Result;
import com.pw.common.security.LoginUser;
import com.pw.common.security.RequireRole;
import com.pw.common.security.SecurityContexts;
import com.pw.order.dto.OrderDto;
import com.pw.order.dto.OrderReq;
import com.pw.order.entity.BizOrder;
import com.pw.order.service.AdminOrderService;
import com.pw.order.service.OrderQueryService;
import com.pw.refund.entity.Refund;
import com.pw.refund.service.RefundService;
import com.pw.user.entity.User;
import com.pw.user.mapper.UserMapper;
import com.pw.withdraw.entity.Withdraw;
import com.pw.withdraw.service.WithdrawService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 管理端订单与资金接口。对应 04 §3.7。
 *
 * <p><b>权限分级</b>（README §2.3）：</p>
 * <ul>
 *   <li>客服（CS）：可发起争议退款、下发交接、指派/改派；<b>不可审批退款、不可看流水</b>；</li>
 *   <li>管理员（ADMIN）：可审批退款、确认提现、查看流水；</li>
 *   <li>超管（SUPER_ADMIN）：在管理员基础上，管理员发起的退款须由超管审批。</li>
 * </ul>
 *
 * <p>角色门禁用 {@link RequireRole}，资源级与动作级权限（如「客服不能审批」）
 * 由各 Service 二次校验，控制器只做登录态提取与装配。</p>
 */
@Tag(name = "07-订单与资金（管理端）", description = "订单管理、交接、指派、退款审批、提现、流水")
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
@RequireRole({Role.CS, Role.ADMIN, Role.SUPER_ADMIN})
public class AdminOrderController {

    private final OrderQueryService queryService;
    private final AdminOrderService adminOrderService;
    private final RefundService refundService;
    private final WithdrawService withdrawService;
    private final com.pw.admin.service.AdminLedgerService ledgerService;
    private final UserMapper userMapper;
    private final BoosterMapper boosterMapper;

    // ------------------------------------------------------------------
    // 订单管理
    // ------------------------------------------------------------------

    @Operation(summary = "订单列表",
            description = "支持订单号/客户/打手ID/打手昵称/手机号/状态/下单与完成时间区间")
    @GetMapping("/orders")
    public Result<PageResult<OrderDto.Summary>> listOrders(
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) Long boosterId,
            @RequestParam(required = false) String boosterName,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdTo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate completedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate completedTo,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        requireAdminSide();
        int limit = clamp(pageSize);
        IPage<BizOrder> orders = queryService.listForAdmin(orderNo, customerId, boosterId,
                boosterName, phone, status, createdFrom, createdTo, completedFrom, completedTo, page, limit);
        List<OrderDto.Summary> items = new ArrayList<>(orders.getRecords().size());
        for (BizOrder order : orders.getRecords()) {
            items.add(queryService.toSummary(order, Role.ADMIN, null));
        }
        return Result.ok(PageResult.from(orders, items));
    }

    @Operation(summary = "订单详情（管理端）")
    @GetMapping("/orders/{id}")
    public Result<OrderDto.Detail> orderDetail(@PathVariable Long id) {
        requireAdminSide();
        BizOrder order = queryService.requireOrder(id);
        return Result.ok(queryService.toDetail(order, Role.ADMIN, null));
    }

    @Operation(summary = "指派打手", description = "仅待接单且未归属的大厅单可指派")
    @PostMapping("/orders/{id}/assign")
    public Result<OrderDto.Detail> assign(@PathVariable Long id,
                                          @Valid @RequestBody OrderReq.AssignReq req) {
        LoginUser me = requireAdminSide();
        BizOrder order = adminOrderService.assign(id, req.getBoosterId(),
                me.getSubjectId(), me.getRole().name());
        return Result.ok(queryService.toDetail(order, me.getRole(), null));
    }

    @Operation(summary = "改派打手", description = "已接单可改派，回到待交接，不动资金")
    @PostMapping("/orders/{id}/reassign")
    public Result<OrderDto.Detail> reassign(@PathVariable Long id,
                                            @Valid @RequestBody OrderReq.AssignReq req) {
        LoginUser me = requireAdminSide();
        BizOrder order = adminOrderService.reassign(id, req.getBoosterId(), req.getReason(),
                me.getSubjectId(), me.getRole().name());
        return Result.ok(queryService.toDetail(order, me.getRole(), null));
    }

    @Operation(summary = "客服下发交接", description = "向打手发送老板游戏 ID 与上号指引，状态置 SENT")
    @PostMapping("/orders/{id}/handover")
    public Result<OrderDto.HandoverInfo> handover(@PathVariable Long id,
                                                  @Valid @RequestBody OrderReq.HandoverReq req) {
        LoginUser me = requireAdminSide();
        adminOrderService.handover(id, me.getSubjectId(), req.getChannel(),
                req.getBossGameId(), req.getGuide(), req.getRemark());
        // 重新装配详情，取最新的交接信息返回
        BizOrder order = queryService.requireOrder(id);
        OrderDto.Detail detail = queryService.toDetail(order, me.getRole(), null);
        return Result.ok(detail.getHandover());
    }

    // ------------------------------------------------------------------
    // 争议退款：发起 / 审批
    // ------------------------------------------------------------------

    @Operation(summary = "发起争议退款",
            description = "客服/管理员对 IN_SERVICE/PENDING_CONFIRM 订单发起 1%-100% 退款，订单转 DISPUTING")
    @PostMapping("/orders/{id}/refund-requests")
    public Result<AdminDto.AdminRefund> createRefund(@PathVariable Long id,
                                                     @Valid @RequestBody OrderReq.DisputeRefundReq req) {
        LoginUser me = requireAdminSide();
        String evidenceJson = toEvidenceJson(req.getEvidenceKeys());
        Refund refund = refundService.createDisputeRefund(id, req.getRefundRate(),
                req.getReason(), evidenceJson, me.getRole(), me.getSubjectId());
        return Result.ok(toAdminRefund(refund, me));
    }

    @Operation(summary = "退款申请列表",
            description = "客服看自己发起的，管理员/超管看全部（README §2.3）")
    @GetMapping("/refund-requests")
    public Result<PageResult<AdminDto.AdminRefund>> listRefunds(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        LoginUser me = requireAdminSide();
        int limit = clamp(pageSize);
        // 客服只看自己发起的申请；管理员/超管看全部
        Long applicantScope = me.getRole() == Role.CS ? me.getSubjectId() : null;
        IPage<Refund> refundPage = refundService.listForAdmin(status, applicantScope, page, limit);
        List<Refund> refunds = refundPage.getRecords();
        List<AdminDto.AdminRefund> items = new ArrayList<>(refunds.size());
        for (Refund r : refunds) {
            items.add(toAdminRefund(r, me));
        }
        return Result.ok(PageResult.from(refundPage, items));
    }

    @Operation(summary = "退款申请详情")
    @GetMapping("/refund-requests/{id}")
    public Result<AdminDto.AdminRefund> refundDetail(@PathVariable Long id) {
        LoginUser me = requireAdminSide();
        Refund refund = refundService.requireById(id);
        // 客服只能看自己发起的
        if (me.getRole() == Role.CS && !me.getSubjectId().equals(refund.getApplicantId())) {
            throw BizException.of(ErrorCode.FORBIDDEN, "只能查看自己发起的退款申请");
        }
        return Result.ok(toAdminRefund(refund, me));
    }

    @Operation(summary = "修改待审退款比例", description = "仅管理员/超管；通过前可改，金额按新比例重算")
    @PutMapping("/refund-requests/{id}/rate")
    public Result<AdminDto.AdminRefund> updateRefundRate(@PathVariable Long id,
                                                         @Valid @RequestBody OrderReq.UpdateRefundRateReq req) {
        LoginUser me = requireApprover();
        Refund refund = refundService.updatePendingRate(id, req.getRefundRate(), me.getSubjectId());
        return Result.ok(toAdminRefund(refund, me));
    }

    @Operation(summary = "审批通过退款", description = "仅管理员/超管；发起人不能审批自己的单")
    @PostMapping("/refund-requests/{id}/approve")
    public Result<AdminDto.AdminRefund> approveRefund(@PathVariable Long id) {
        LoginUser me = requireApprover();
        Refund refund = refundService.approveDisputeRefund(id, me.getSubjectId(), me.getRole());
        return Result.ok(toAdminRefund(refund, me));
    }

    @Operation(summary = "驳回退款", description = "仅管理员/超管；订单回到原状态，托管金不变")
    @PostMapping("/refund-requests/{id}/reject")
    public Result<AdminDto.AdminRefund> rejectRefund(@PathVariable Long id,
                                                     @Valid @RequestBody OrderReq.RejectRefundReq req) {
        LoginUser me = requireApprover();
        Refund refund = refundService.rejectDisputeRefund(id, me.getSubjectId(), me.getRole(),
                req.getReason());
        return Result.ok(toAdminRefund(refund, me));
    }

    // ------------------------------------------------------------------
    // 流水（对账）：仅管理员/超管，客服 403
    // ------------------------------------------------------------------

    @Operation(summary = "订单流水列表",
            description = "仅管理员/超管可访问，客服 403（README §2.3）")
    @GetMapping("/ledger/orders")
    public Result<PageResult<OrderDto.Summary>> ledgerOrders(
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) Long boosterId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        requireLedgerViewer();
        int limit = clamp(pageSize);
        IPage<BizOrder> orders = queryService.listForAdmin(orderNo, customerId, boosterId,
                null, null, status, null, null, null, null, page, limit);
        List<OrderDto.Summary> items = new ArrayList<>(orders.getRecords().size());
        for (BizOrder order : orders.getRecords()) {
            items.add(queryService.toSummary(order, Role.ADMIN, null));
        }
        return Result.ok(PageResult.from(orders, items));
    }

    @Operation(summary = "订单完整流水",
            description = "状态轨迹 + 退款历史 + 结算单 + 金砖流水 + 收益流水 + 交接日志，用于对账")
    @GetMapping("/ledger/orders/{id}")
    public Result<AdminDto.OrderLedger> ledgerDetail(@PathVariable Long id) {
        LoginUser me = requireLedgerViewer();
        return Result.ok(ledgerService.buildLedger(id, me.getRole()));
    }

    // ------------------------------------------------------------------
    // 提现审批
    // ------------------------------------------------------------------

    @Operation(summary = "提现列表", description = "按状态过滤；待审核优先")
    @GetMapping("/withdrawals")
    public Result<PageResult<AdminDto.AdminWithdraw>> listWithdrawals(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        requireApprover();
        int limit = clamp(pageSize);
        IPage<Withdraw> pageData = withdrawService.listForAdmin(status, page, limit);
        List<AdminDto.AdminWithdraw> items = new ArrayList<>(pageData.getRecords().size());
        for (Withdraw w : pageData.getRecords()) {
            items.add(toAdminWithdraw(w));
        }
        return Result.ok(PageResult.from(pageData, items));
    }

    @Operation(summary = "确认提现", description = "APPLIED → APPROVED，不动资金（申请时已冻结）")
    @PostMapping("/withdrawals/{id}/approve")
    public Result<AdminDto.AdminWithdraw> approveWithdraw(@PathVariable Long id) {
        LoginUser me = requireApprover();
        Withdraw withdraw = withdrawService.approve(id, me.getSubjectId());
        return Result.ok(toAdminWithdraw(withdraw));
    }

    @Operation(summary = "标记打款成功", description = "APPROVED/PAYING → PAID，扣减冻结金额")
    @PostMapping("/withdrawals/{id}/pay")
    public Result<AdminDto.AdminWithdraw> payWithdraw(@PathVariable Long id) {
        LoginUser me = requireApprover();
        Withdraw withdraw = withdrawService.markPaid(id, me.getSubjectId());
        return Result.ok(toAdminWithdraw(withdraw));
    }

    @Operation(summary = "拒绝提现", description = "frozen 退回 available")
    @PostMapping("/withdrawals/{id}/reject")
    public Result<AdminDto.AdminWithdraw> rejectWithdraw(@PathVariable Long id,
                                                         @Valid @RequestBody OrderReq.RejectWithdrawReq req) {
        LoginUser me = requireApprover();
        Withdraw withdraw = withdrawService.reject(id, me.getSubjectId(), req.getReason());
        return Result.ok(toAdminWithdraw(withdraw));
    }

    // ------------------------------------------------------------------
    // 装配与门禁辅助
    // ------------------------------------------------------------------

    /** 管理端角色门禁（含客服）。返回登录态供取 subjectId/role。 */
    private static LoginUser requireAdminSide() {
        return SecurityContexts.requireAdmin();
    }

    /** 审批角色门禁：仅管理员/超管，客服 403（README §2.3）。 */
    private static LoginUser requireApprover() {
        LoginUser me = SecurityContexts.requireAdmin();
        if (!me.canApproveRefund()) {
            throw BizException.of(ErrorCode.FORBIDDEN, "客服无审批权限，请联系管理员处理");
        }
        return me;
    }

    /** 流水查看门禁：仅管理员/超管，客服 403（README §2.3「客服不可看流水」）。 */
    private static LoginUser requireLedgerViewer() {
        LoginUser me = SecurityContexts.requireAdmin();
        if (!me.getRole().canViewLedger()) {
            throw BizException.of(ErrorCode.FORBIDDEN, "客服无权查看资金流水，请联系管理员");
        }
        return me;
    }

    private AdminDto.AdminRefund toAdminRefund(Refund r, LoginUser me) {
        BizOrder order = queryService.requireOrder(r.getOrderId());
        User customer = userMapper.selectById(order.getCustomerId());
        boolean canApprove = me.canApproveRefund()
                && !me.getSubjectId().equals(r.getApplicantId())
                && isPendingApproval(r);
        return AdminDto.AdminRefund.builder()
                .id(r.getId())
                .refundNo(r.getRefundNo())
                .orderId(r.getOrderId())
                .orderNo(order.getOrderNo())
                .customerId(order.getCustomerId())
                .customerNickname(customer == null ? null : customer.getNickname())
                .type(r.getType())
                .refundRate(r.getRefundRate())
                .payAmount(r.getPayAmount())
                .refundAmount(r.getRefundAmount())
                .retainedAmount(r.getRetainedAmount())
                .commissionAmount(r.getCommissionAmount())
                .boosterAmount(r.getBoosterAmount())
                .previousOrderStatus(r.getPreviousOrderStatus())
                .status(r.getStatus())
                .reason(r.getReason())
                .applicantType(r.getApplicantType())
                .applicantId(r.getApplicantId())
                .approverId(r.getApproverId())
                .createdAt(r.getCreatedAt())
                .canApprove(canApprove)
                .build();
    }

    /** 仅待审批且非本人发起时可审批。管理员发起的须超管审批，这里只做基础判定，精确校验在 Service。 */
    private static boolean isPendingApproval(Refund r) {
        return "PENDING_APPROVAL".equals(r.getStatus());
    }

    private AdminDto.AdminWithdraw toAdminWithdraw(Withdraw w) {
        Booster booster = boosterMapper.selectById(w.getBoosterId());
        String boosterName = null;
        if (booster != null) {
            User user = userMapper.selectById(booster.getUserId());
            boosterName = user == null ? null : user.getNickname();
        }
        return AdminDto.AdminWithdraw.builder()
                .id(w.getId())
                .withdrawNo(w.getWithdrawNo())
                .boosterId(w.getBoosterId())
                .boosterName(boosterName)
                .amount(w.getAmount())
                .status(w.getStatus())
                .createdAt(w.getCreatedAt())
                .approvedAt(w.getApprovedAt())
                .paidAt(w.getPaidAt())
                .failReason(w.getFailReason())
                .build();
    }

    private static String toEvidenceJson(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("\"").append(keys.get(i).replace("\"", "\\\"")).append("\"");
        }
        return sb.append("]").toString();
    }

    private static int clamp(int pageSize) {
        return Math.max(1, Math.min(pageSize, 200));
    }
}
