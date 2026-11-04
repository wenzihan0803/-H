package com.pw.admin.controller;

import com.pw.admin.dto.AdminDto;
import com.pw.admin.service.AdminBoosterService;
import com.pw.booster.dto.BoosterApplyDto;
import com.pw.booster.dto.BoosterApplyReq;
import com.pw.booster.service.BoosterApplyService;
import com.pw.common.enums.Role;
import com.pw.common.result.Result;
import com.pw.common.security.LoginUser;
import com.pw.common.security.RequireRole;
import com.pw.common.security.SecurityContexts;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/**
 * 管理端打手：已入驻列表、入驻审核（只有通过/拒绝）、考核记录。
 */
@Tag(name = "11-打手（管理端）", description = "打手列表、入驻审核、考核记录")
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
@RequireRole({Role.CS, Role.ADMIN, Role.SUPER_ADMIN})
public class AdminBoosterController {

    private final AdminBoosterService boosterService;
    private final BoosterApplyService applyService;

    @Operation(summary = "打手列表", description = "昵称模糊 / 手机号精确；含年龄、性别、完成单数、累计收益")
    @GetMapping("/boosters")
    public Result<List<AdminDto.AdminBooster>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        return Result.ok(boosterService.list(keyword, status));
    }

    @Operation(summary = "入驻申请列表")
    @GetMapping("/boosters/applies")
    public Result<List<BoosterApplyDto.View>> applies(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        return Result.ok(applyService.list(status, keyword));
    }

    @Operation(summary = "通过入驻申请", description = "自动建打手档案、收益账户与首个游戏账号")
    @PostMapping("/boosters/applies/{id}/approve")
    public Result<Void> approve(@PathVariable Long id) {
        LoginUser me = SecurityContexts.requireRole(Role.ADMIN, Role.SUPER_ADMIN);
        applyService.approve(id, me.getSubjectId());
        return Result.ok();
    }

    @Operation(summary = "拒绝入驻申请", description = "理由必填")
    @PostMapping("/boosters/applies/{id}/reject")
    public Result<Void> reject(@PathVariable Long id, @Valid @RequestBody BoosterApplyReq.Reject req) {
        LoginUser me = SecurityContexts.requireRole(Role.ADMIN, Role.SUPER_ADMIN);
        applyService.reject(id, me.getSubjectId(), req.getReason());
        return Result.ok();
    }

    @Operation(summary = "考核记录", description = "该申请单的全部审核动作")
    @GetMapping("/boosters/applies/{id}/trials")
    public Result<List<BoosterApplyDto.Trial>> trials(@PathVariable Long id) {
        return Result.ok(applyService.trials(id));
    }

    @Operation(summary = "导出打手流水 CSV", description = "默认本月由前端传参；columns 逗号分隔，不传导出全部列")
    @GetMapping("/boosters/{id}/earnings/export")
    public ResponseEntity<byte[]> exportEarnings(
            @PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String columns) {
        List<String> cols = columns == null || columns.isBlank()
                ? List.of()
                : List.of(columns.split(","));
        byte[] csv = boosterService.exportEarnings(id, from, to, cols);
        String filename = "打手流水_" + id + "_" + (from == null ? "全部" : from) + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(filename, StandardCharsets.UTF_8)
                                .build().toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv);
    }
}
