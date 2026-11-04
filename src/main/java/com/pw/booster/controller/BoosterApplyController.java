package com.pw.booster.controller;

import com.pw.booster.dto.BoosterApplyDto;
import com.pw.booster.dto.BoosterApplyReq;
import com.pw.booster.service.BoosterApplyService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 打手入驻与资料。申请接口对已登录的任意身份开放（手机号注册的客户先申请，通过后才成为打手）。
 */
@Tag(name = "12-入驻与资料（打手）", description = "提交入驻、查看申请、维护昵称/年龄/性别")
@RestController
@RequestMapping("/booster")
@RequiredArgsConstructor
public class BoosterApplyController {

    private final BoosterApplyService applyService;

    @Operation(summary = "我的入驻申请", description = "没申请过返回 null")
    @GetMapping("/apply")
    public Result<BoosterApplyDto.View> my() {
        return Result.ok(applyService.my(SecurityContexts.require().getSubjectId()));
    }

    @Operation(summary = "提交入驻申请", description = "一次提交资料，等待管理员审核")
    @PostMapping("/apply")
    public Result<BoosterApplyDto.View> apply(@Valid @RequestBody BoosterApplyReq.Apply req) {
        LoginUser me = SecurityContexts.require();
        return Result.ok(applyService.apply(me.getSubjectId(), req));
    }

    @Operation(summary = "我的打手资料")
    @GetMapping("/profile")
    @RequireRole(com.pw.common.enums.Role.BOOSTER)
    public Result<BoosterApplyDto.ProfileView> profile() {
        return Result.ok(applyService.profile(requireBoosterId()));
    }

    @Operation(summary = "修改打手资料", description = "昵称 30 天限改一次；年龄/性别为空时可补填一次")
    @PutMapping("/profile")
    @RequireRole(com.pw.common.enums.Role.BOOSTER)
    public Result<BoosterApplyDto.ProfileView> updateProfile(
            @Valid @RequestBody BoosterApplyReq.Profile req) {
        return Result.ok(applyService.updateProfile(requireBoosterId(), req));
    }

    private static Long requireBoosterId() {
        LoginUser me = SecurityContexts.requireBooster();
        if (me.getBoosterId() == null) {
            throw com.pw.common.exception.BizException.of(
                    com.pw.common.exception.ErrorCode.ACCOUNT_RESTRICTED,
                    "打手资料未初始化，请重新登录");
        }
        return me.getBoosterId();
    }
}
