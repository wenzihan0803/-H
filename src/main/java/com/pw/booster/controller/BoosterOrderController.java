package com.pw.booster.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pw.booster.dto.BoosterDto;
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
import com.pw.file.service.FileStorageService;
import com.pw.order.dto.OrderDto;
import com.pw.order.dto.OrderReq;
import com.pw.order.entity.BizOrder;
import com.pw.order.service.OrderEvidenceService;
import com.pw.order.service.OrderLifecycleService;
import com.pw.order.service.OrderQueryService;
import com.pw.order.entity.OrderEvidence;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 打手端订单接口。对应 04 §3.6。
 *
 * <p><b>身份来源</b>：打手 ID 从登录态 {@code boosterId} 取（JWT 登录时预解析），
 * <b>不是 {@code subjectId}</b>——后者是 user_id。两者混用会查到错误打手。</p>
 *
 * <p><b>数据隔离</b>（04 §3.6）：打手看不到客户区服、备注、手机号，
 * 只能看到客服下发的 {@code bossGameId} 与 {@code guide}，且需交接 SENT 之后。
 * 隔离在 {@link OrderQueryService#toDetail} 内按角色实现。</p>
 */
@Tag(name = "06-订单（打手）", description = "大厅抢单、接单、履约、完单")
@RestController
@RequestMapping("/booster")
@RequiredArgsConstructor
@RequireRole(Role.BOOSTER)
public class BoosterOrderController {

    private final OrderLifecycleService lifecycleService;
    private final OrderQueryService queryService;
    private final OrderEvidenceService evidenceService;
    private final BoosterMapper boosterMapper;
    private final FileStorageService fileStorage;

    @Operation(summary = "大厅订单列表",
            description = "tab=AVAILABLE 可抢单（含指派给我的）；ASSIGNED 已指派；BOOKED 已被抢")
    @GetMapping("/hall")
    public Result<PageResult<OrderDto.HallOrder>> hall(
            @RequestParam(defaultValue = "AVAILABLE") String tab,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        LoginUser me = requireBooster();
        Long boosterId = me.getBoosterId();
        int limit = clamp(pageSize);

        IPage<BizOrder> orders = switch (tab) {
            case "AVAILABLE" -> queryService.listHallAvailable(boosterId, page, limit);
            case "ASSIGNED" -> queryService.listByBooster(boosterId, "PENDING_ACCEPT", page, limit);
            case "BOOKED" -> Page.<BizOrder>of(Math.max(1, page), limit);
            default -> throw BizException.of(ErrorCode.PARAM_ERROR,
                    "tab 只能是 AVAILABLE / ASSIGNED / BOOKED");
        };

        List<OrderDto.HallOrder> items = new ArrayList<>(orders.getRecords().size());
        for (BizOrder order : orders.getRecords()) {
            items.add(queryService.toHallOrder(order, boosterId, tab));
        }
        return Result.ok(PageResult.from(orders, items));
    }

    @Operation(summary = "接单",
            description = "PENDING_ACCEPT → ACCEPTED；指定单只有被指派的打手能接，大厅单先到先得")
    @PostMapping("/orders/{id}/accept")
    public Result<OrderDto.Detail> accept(@PathVariable Long id) {
        LoginUser me = requireBooster();
        BizOrder order = lifecycleService.accept(id, me.getBoosterId());
        return Result.ok(queryService.toDetail(order, Role.BOOSTER, me.getBoosterId()));
    }

    @Operation(summary = "拒单",
            description = "ACCEPTED → PENDING_ACCEPT 并清空归属，订单退回大厅；不动资金")
    @PostMapping("/orders/{id}/reject")
    public Result<Void> reject(@PathVariable Long id,
                               @Valid @RequestBody OrderReq.RejectOrderReq req) {
        LoginUser me = requireBooster();
        lifecycleService.reject(id, me.getBoosterId(), req.getReason());
        return Result.ok();
    }

    @Operation(summary = "我的订单列表")
    @GetMapping("/orders")
    public Result<PageResult<OrderDto.Summary>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        LoginUser me = requireBooster();
        Long boosterId = me.getBoosterId();
        int limit = clamp(pageSize);
        IPage<BizOrder> orders = queryService.listByBooster(boosterId, status, page, limit);
        List<OrderDto.Summary> items = new ArrayList<>(orders.getRecords().size());
        for (BizOrder order : orders.getRecords()) {
            items.add(queryService.toSummary(order, Role.BOOSTER, boosterId));
        }
        return Result.ok(PageResult.from(orders, items));
    }

    @Operation(summary = "订单详情", description = "不含客户备注与区服，交接后才可见老板游戏 ID")
    @GetMapping("/orders/{id}")
    public Result<OrderDto.Detail> detail(@PathVariable Long id) {
        LoginUser me = requireBooster();
        BizOrder order = queryService.requireOrder(id);
        return Result.ok(queryService.toDetail(order, Role.BOOSTER, me.getBoosterId()));
    }

    @Operation(summary = "确认交接",
            description = "客服 SENT 之后打手确认；未 ACK 不能开始服务")
    @PostMapping("/orders/{id}/handover/ack")
    public Result<Map<String, Object>> ackHandover(@PathVariable Long id) {
        LoginUser me = requireBooster();
        LocalDateTime ackAt = lifecycleService.ackHandover(id, me.getBoosterId());
        Map<String, Object> data = new HashMap<>();
        data.put("orderId", id);
        data.put("handoverStatus", "ACK");
        data.put("ackAt", ackAt);
        return Result.ok(data);
    }

    @Operation(summary = "上传履约凭证", description = "START=开局照，END=结束照，SUPPLEMENT=补充")
    @PostMapping("/orders/{id}/evidence")
    public Result<OrderDto.Evidence> uploadEvidence(@PathVariable Long id,
                                                    @Valid @RequestBody OrderReq.EvidenceReq req) {
        LoginUser me = requireBooster();
        OrderEvidence evidence = evidenceService.upload(
                id, me.getBoosterId(), req.getType(), req.getObjectKey(), req.getTakenAt());
        return Result.ok(OrderDto.Evidence.builder()
                .id(evidence.getId())
                .type(evidence.getType())
                .url(fileStorage.urlOf(evidence.getObjectKey()))
                .takenAt(evidence.getTakenAt())
                .supplement(Integer.valueOf(1).equals(evidence.getSupplement()))
                .build());
    }

    @Operation(summary = "开始服务",
            description = "ACCEPTED → IN_SERVICE；需交接已 ACK + 开局照 + 游戏账号")
    @PostMapping("/orders/{id}/start")
    public Result<OrderDto.Detail> start(@PathVariable Long id,
                                         @Valid @RequestBody OrderReq.StartServiceReq req) {
        LoginUser me = requireBooster();
        BizOrder order = lifecycleService.startService(id, me.getBoosterId(),
                req.getGameAccountId(), req.getStartEvidenceId());
        return Result.ok(queryService.toDetail(order, Role.BOOSTER, me.getBoosterId()));
    }

    @Operation(summary = "申请完单",
            description = "IN_SERVICE → PENDING_CONFIRM；需开局照+结束照，7 天后自动确认")
    @PostMapping("/orders/{id}/complete")
    public Result<OrderDto.Detail> complete(@PathVariable Long id,
                                            @Valid @RequestBody OrderReq.CompleteOrderReq req) {
        LoginUser me = requireBooster();
        BizOrder order = lifecycleService.requestComplete(id, me.getBoosterId(),
                req.getEndEvidenceId(), 7);
        return Result.ok(queryService.toDetail(order, Role.BOOSTER, me.getBoosterId()));
    }

    @Operation(summary = "打手统计", description = "今日/本月完成单数、服务评分、完成率")
    @GetMapping("/stats")
    public Result<BoosterDto.Stats> stats() {
        LoginUser me = requireBooster();
        Long boosterId = me.getBoosterId();
        long[] counts = queryService.boosterStats(boosterId);
        Booster booster = boosterMapper.selectById(boosterId);
        return Result.ok(BoosterDto.Stats.builder()
                .todayOrders(counts[0])
                .monthOrders(counts[1])
                .score(booster == null || booster.getServiceScore() == null
                        ? "5.00" : booster.getServiceScore().toPlainString())
                .completeRate(booster == null || booster.getCompleteRate() == null
                        ? "100.00" : booster.getCompleteRate().toPlainString())
                .build());
    }

    /**
     * 取当前打手登录态，并校验 {@code boosterId} 已解析。
     *
     * <p>打手 token 的 {@code boosterId} 在登录时写入；若为空说明数据异常
     * （user 存在但 booster 记录缺失），直接拒绝而不是继续用 null 查询。</p>
     */
    private static LoginUser requireBooster() {
        LoginUser me = SecurityContexts.requireBooster();
        if (me.getBoosterId() == null) {
            throw BizException.of(ErrorCode.ACCOUNT_RESTRICTED,
                    "打手资料未初始化，请联系客服");
        }
        return me;
    }

    private static int clamp(int pageSize) {
        return Math.max(1, Math.min(pageSize, 200));
    }
}
