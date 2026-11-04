package com.pw.admin.controller;

import com.pw.admin.dto.BannerDto;
import com.pw.admin.service.AdminBannerService;
import com.pw.common.enums.Role;
import com.pw.common.result.Result;
import com.pw.common.security.RequireRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 首页 Banner 管理。 */
@Tag(name = "19-Banner（管理端）", description = "首页 Banner 增删改查")
@RestController
@RequestMapping("/admin/banners")
@RequiredArgsConstructor
@RequireRole({Role.ADMIN, Role.SUPER_ADMIN})
public class AdminBannerController {

    private final AdminBannerService bannerService;

    @Operation(summary = "Banner 列表")
    @GetMapping
    public Result<List<BannerDto.Row>> list(@RequestParam(required = false) String status) {
        return Result.ok(bannerService.list(status));
    }

    @Operation(summary = "新增 Banner")
    @PostMapping
    public Result<Long> create(@Valid @RequestBody BannerDto.SaveReq req) {
        return Result.ok(bannerService.create(req));
    }

    @Operation(summary = "编辑 Banner")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody BannerDto.SaveReq req) {
        bannerService.update(id, req);
        return Result.ok();
    }

    @Operation(summary = "删除 Banner")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        bannerService.delete(id);
        return Result.ok();
    }
}
