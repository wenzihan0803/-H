package com.pw.booster.controller;

import com.pw.booster.dto.BoosterDto;
import com.pw.booster.dto.GameAccountReq;
import com.pw.booster.entity.BoosterGameAccount;
import com.pw.booster.service.BoosterGameAccountService;
import com.pw.common.enums.Role;
import com.pw.common.exception.BizException;
import com.pw.common.exception.ErrorCode;
import com.pw.common.result.Result;
import com.pw.common.security.LoginUser;
import com.pw.common.security.RequireRole;
import com.pw.common.security.SecurityContexts;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 打手游戏账号接口。对应 04 §3.6 {@code /booster/game-accounts}。
 *
 * <p>开始服务与申请完单都需要指定游戏账号（01 §3 guard），
 * 账号只能属于当前登录打手。</p>
 */
@Tag(name = "09-游戏账号（打手）", description = "打手游戏账号维护")
@RestController
@RequestMapping("/booster/game-accounts")
@RequiredArgsConstructor
@RequireRole(Role.BOOSTER)
public class BoosterGameAccountController {

    private final BoosterGameAccountService gameAccountService;

    @Operation(summary = "我的游戏账号列表")
    @GetMapping
    public Result<List<BoosterDto.GameAccount>> list() {
        LoginUser me = requireBooster();
        List<BoosterGameAccount> accounts = gameAccountService.listByBooster(me.getBoosterId());
        List<BoosterDto.GameAccount> items = new ArrayList<>(accounts.size());
        for (BoosterGameAccount a : accounts) {
            items.add(BoosterDto.GameAccount.builder()
                    .id(a.getId())
                    .gameId(a.getGameId())
                    .accountId(a.getAccountId())
                    .server(a.getServer())
                    .status(a.getStatus())
                    .createdAt(a.getCreatedAt())
                    .build());
        }
        return Result.ok(items);
    }

    @Operation(summary = "新增游戏账号", description = "同一 (游戏, 账号) 不可重复添加")
    @PostMapping
    public Result<BoosterDto.GameAccount> create(@Valid @RequestBody GameAccountReq req) {
        LoginUser me = requireBooster();
        BoosterGameAccount a = gameAccountService.create(
                me.getBoosterId(), req.getGameId(), req.getAccountId(), req.getServer());
        return Result.ok(BoosterDto.GameAccount.builder()
                .id(a.getId())
                .gameId(a.getGameId())
                .accountId(a.getAccountId())
                .server(a.getServer())
                .status(a.getStatus())
                .createdAt(a.getCreatedAt())
                .build());
    }

    private static LoginUser requireBooster() {
        LoginUser me = SecurityContexts.requireBooster();
        if (me.getBoosterId() == null) {
            throw BizException.of(ErrorCode.ACCOUNT_RESTRICTED, "打手资料未初始化，请联系客服");
        }
        return me;
    }
}
