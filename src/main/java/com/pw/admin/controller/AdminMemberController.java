package com.pw.admin.controller;

import com.pw.admin.dto.MemberDto;
import com.pw.admin.service.AdminMemberService;
import com.pw.common.enums.Role;
import com.pw.common.result.Result;
import com.pw.common.security.RequireRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 管理端会员列表（只读）。会员由单笔充值满 2000 元自动开通。 */
@Tag(name = "16-会员（管理端）", description = "会员列表")
@RestController
@RequestMapping("/admin/members")
@RequiredArgsConstructor
@RequireRole({Role.CS, Role.ADMIN, Role.SUPER_ADMIN})
public class AdminMemberController {

    private final AdminMemberService memberService;

    @Operation(summary = "会员列表", description = "一个用户一行；支持昵称/手机号筛选")
    @GetMapping
    public Result<List<MemberDto.Member>> list(@RequestParam(required = false) String keyword) {
        return Result.ok(memberService.list(keyword));
    }
}
