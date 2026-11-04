package com.pw.booster.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.pw.booster.dto.BoosterDto;
import com.pw.booster.entity.BoosterEarningAccount;
import com.pw.booster.entity.BoosterEarningTxn;
import com.pw.booster.service.BoosterEarningService;
import com.pw.common.enums.Role;
import com.pw.common.exception.BizException;
import com.pw.common.exception.ErrorCode;
import com.pw.common.result.PageResult;
import com.pw.common.result.Result;
import com.pw.common.security.LoginUser;
import com.pw.common.security.RequireRole;
import com.pw.common.security.SecurityContexts;
import com.pw.order.dto.OrderReq;
import com.pw.withdraw.entity.Withdraw;
import com.pw.withdraw.service.WithdrawService;
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
 * 打手端收益与提现接口。对应 04 §3.6 收益/提现部分。
 *
 * <p><b>身份来源</b>：打手 ID 从登录态 {@code boosterId} 取，不是 {@code subjectId}。</p>
 *
 * <p><b>无 T+7</b>：结算收益直接进 available，可立即提现（README §0.1）。</p>
 */
@Tag(name = "08-收益与提现（打手）", description = "收益账户、流水、提现申请")
@RestController
@RequestMapping("/booster")
@RequiredArgsConstructor
@RequireRole(Role.BOOSTER)
public class BoosterEarningController {

    private final BoosterEarningService earningService;
    private final WithdrawService withdrawService;

    @Operation(summary = "收益账户", description = "冻结/可提现/累计三栏")
    @GetMapping("/earnings")
    public Result<BoosterDto.Earning> earnings() {
        LoginUser me = requireBooster();
        BoosterEarningAccount account = earningService.getAccount(me.getBoosterId());
        if (account == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "收益账户不存在，请联系客服");
        }
        return Result.ok(BoosterDto.Earning.builder()
                .frozen(nz(account.getFrozen()))
                .available(nz(account.getAvailable()))
                .total(nz(account.getTotal()))
                .build());
    }

    @Operation(summary = "收益流水", description = "结算/提现/封号等全部变动，按时间倒序")
    @GetMapping("/earnings/txns")
    public Result<PageResult<BoosterDto.EarningTxn>> earningTxns(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        LoginUser me = requireBooster();
        int limit = clamp(pageSize);
        IPage<BoosterEarningTxn> txnPage = earningService.listTxns(me.getBoosterId(), page, limit);
        List<BoosterEarningTxn> txns = txnPage.getRecords();
        List<BoosterDto.EarningTxn> items = new ArrayList<>(txns.size());
        for (BoosterEarningTxn t : txns) {
            items.add(BoosterDto.EarningTxn.builder()
                    .id(t.getId())
                    .bizType(t.getBizType())
                    .bizNo(t.getBizNo())
                    .amount(t.getAmount())
                    .frozenAfter(t.getFrozenAfter())
                    .availableAfter(t.getAvailableAfter())
                    .orderId(t.getOrderId())
                    .remark(t.getRemark())
                    .createdAt(t.getCreatedAt())
                    .build());
        }
        return Result.ok(PageResult.from(txnPage, items));
    }

    @Operation(summary = "申请提现",
            description = "available 转 frozen，建 APPLIED 提现单；每日 1 次、≥1 元、封禁拒绝")
    @PostMapping("/withdrawals")
    public Result<BoosterDto.WithdrawItem> applyWithdraw(@Valid @RequestBody OrderReq.WithdrawReq req) {
        LoginUser me = requireBooster();
        Withdraw withdraw = withdrawService.apply(me.getBoosterId(), req.getAmount());
        return Result.ok(toWithdrawItem(withdraw));
    }

    @Operation(summary = "我的提现记录")
    @GetMapping("/withdrawals")
    public Result<PageResult<BoosterDto.WithdrawItem>> listWithdrawals(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        LoginUser me = requireBooster();
        int limit = clamp(pageSize);
        IPage<Withdraw> withdrawPage = withdrawService.listByBooster(me.getBoosterId(), page, limit);
        List<Withdraw> list = withdrawPage.getRecords();
        List<BoosterDto.WithdrawItem> items = new ArrayList<>(list.size());
        for (Withdraw w : list) {
            items.add(toWithdrawItem(w));
        }
        return Result.ok(PageResult.from(withdrawPage, items));
    }

    @Operation(summary = "提现详情")
    @GetMapping("/withdrawals/{id}")
    public Result<BoosterDto.WithdrawItem> withdrawDetail(@PathVariable Long id) {
        LoginUser me = requireBooster();
        Withdraw withdraw = withdrawService.requireById(id);
        // 归属校验：只能看自己的提现单，防 IDOR
        if (!me.getBoosterId().equals(withdraw.getBoosterId())) {
            throw BizException.of(ErrorCode.FORBIDDEN, "无权查看该提现单");
        }
        return Result.ok(toWithdrawItem(withdraw));
    }

    private static BoosterDto.WithdrawItem toWithdrawItem(Withdraw w) {
        return BoosterDto.WithdrawItem.builder()
                .id(w.getId())
                .withdrawNo(w.getWithdrawNo())
                .amount(w.getAmount())
                .status(w.getStatus())
                .createdAt(w.getCreatedAt())
                .paidAt(w.getPaidAt())
                .failReason(w.getFailReason())
                .build();
    }

    private static LoginUser requireBooster() {
        LoginUser me = SecurityContexts.requireBooster();
        if (me.getBoosterId() == null) {
            throw BizException.of(ErrorCode.ACCOUNT_RESTRICTED, "打手资料未初始化，请联系客服");
        }
        return me;
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }

    private static int clamp(int pageSize) {
        return Math.max(1, Math.min(pageSize, 200));
    }
}
