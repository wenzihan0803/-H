package com.pw.admin.controller;

import com.pw.admin.dto.AdminUserDto;
import com.pw.admin.service.AdminUserQueryService;
import com.pw.common.enums.Role;
import com.pw.common.result.PageResult;
import com.pw.common.result.Result;
import com.pw.common.security.SecurityContexts;
import com.pw.common.security.RequireRole;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pw.user.entity.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "17-用户与日志（管理端）", description = "用户列表、操作日志")
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
@RequireRole({Role.CS, Role.ADMIN, Role.SUPER_ADMIN})
public class AdminUserController {

    private final AdminUserQueryService queryService;

    @Operation(summary = "用户列表", description = "昵称模糊 / 手机号精确；含会员、金砖、打手状态")
    @GetMapping("/users")
    public Result<PageResult<AdminUserDto.UserRow>> users(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        IPage<AdminUserDto.UserRow> p = queryService.users(keyword, status, page,
                Math.min(pageSize, 100), new Page<>(Math.max(1, page), Math.min(pageSize, 100)));
        return Result.ok(PageResult.from(p, p.getRecords()));
    }

    @Operation(summary = "操作日志", description = "仅管理员/超管可见")
    @GetMapping("/logs")
    @RequireRole({Role.ADMIN, Role.SUPER_ADMIN})
    public Result<PageResult<AdminUserDto.LogRow>> logs(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        // 显式兜底：方法级注解在 CGLIB 代理下解析不可靠，这里再校验一次（客服无 log:read）
        SecurityContexts.requireRole(Role.ADMIN, Role.SUPER_ADMIN);
        IPage<AdminUserDto.LogRow> p = queryService.logs(keyword, page, Math.min(pageSize, 100));
        return Result.ok(PageResult.from(p, p.getRecords()));
    }
}
