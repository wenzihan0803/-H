package com.pw.admin.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.pw.admin.dto.PunishmentDto;
import com.pw.admin.service.AdminPunishmentService;
import com.pw.common.enums.Role;
import com.pw.common.result.PageResult;
import com.pw.common.result.Result;
import com.pw.common.security.LoginUser;
import com.pw.common.security.RequireRole;
import com.pw.common.security.SecurityContexts;
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

import java.util.List;

/** 处置中心：警告/封禁/解封/申诉查看。 */
@Tag(name = "18-处置中心（管理端）", description = "警告、临时封禁、长期封禁、解封")
@RestController
@RequestMapping("/admin/punishments")
@RequiredArgsConstructor
@RequireRole({Role.CS, Role.ADMIN, Role.SUPER_ADMIN})
public class AdminPunishmentController {

    private final AdminPunishmentService punishmentService;

    @Operation(summary = "处置列表")
    @GetMapping
    public Result<PageResult<PunishmentDto.Row>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String targetType,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        IPage<PunishmentDto.Row> p = punishmentService.list(status, targetType, page, pageSize);
        return Result.ok(PageResult.from(p, p.getRecords()));
    }

    @Operation(summary = "新建处置", description = "打手封禁会冻结收益并拒绝在途提现；客服每日 3 次、管理员每月 10 次")
    @PostMapping
    public Result<Long> create(@Valid @RequestBody PunishmentDto.CreateReq req) {
        LoginUser me = SecurityContexts.requireAdmin();
        return Result.ok(punishmentService.create(req, me));
    }

    @Operation(summary = "解除处置", description = "解封打手并退回封号冻结的收益")
    @PostMapping("/{id}/lift")
    public Result<Void> lift(@PathVariable Long id) {
        LoginUser me = SecurityContexts.requireRole(Role.ADMIN, Role.SUPER_ADMIN);
        punishmentService.lift(id, me);
        return Result.ok();
    }

    @Operation(summary = "处罚申诉列表")
    @GetMapping("/{id}/appeals")
    public Result<List<PunishmentDto.AppealRow>> appeals(@PathVariable Long id) {
        return Result.ok(punishmentService.appeals(id));
    }
}
