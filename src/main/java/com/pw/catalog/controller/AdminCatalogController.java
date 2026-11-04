package com.pw.catalog.controller;

import com.pw.catalog.dto.CatalogDto;
import com.pw.catalog.service.CatalogService;
import com.pw.common.enums.Role;
import com.pw.common.result.Result;
import com.pw.common.security.RequireRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端：服务（SKU）配置。
 *
 * <p>产品口径（2026-10）：陪玩 / 护航 / 战术教学 的具体单子由管理端配置，
 * 可配置项为「地图 + 名称 + 介绍 + 价格 + 上下架」；类目本身由超管维护。</p>
 *
 * <p>权限：管理员 / 超管；客服只读权限不在此开放（避免误改价格）。</p>
 */
@Tag(name = "08-服务配置（管理端）", description = "类目 / SKU 的商品配置：地图、名称、介绍、价格、上下架")
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
@RequireRole({Role.ADMIN, Role.SUPER_ADMIN})
public class AdminCatalogController {

    private final CatalogService catalogService;

    @Operation(summary = "类目列表（含停用）")
    @GetMapping("/categories")
    public Result<List<CatalogDto.CategoryVO>> categories() {
        return Result.ok(catalogService.listCategories(true));
    }

    @Operation(summary = "SKU 列表", description = "可按类目、关键字（名称/地图/介绍）筛选；含已下架")
    @GetMapping("/skus")
    public Result<List<CatalogDto.SkuVO>> skus(@RequestParam(required = false) Long categoryId,
                                               @RequestParam(required = false) String keyword) {
        return Result.ok(catalogService.listSkus(categoryId, keyword, false));
    }

    @Operation(summary = "新增服务", description = "单价单位为分；计价单位随类目（陪伴/教学=小时，护航=带出价值）")
    @PostMapping("/skus")
    public Result<CatalogDto.SkuVO> create(@Valid @RequestBody CatalogDto.SkuUpsertReq req) {
        return Result.ok(catalogService.createSku(req));
    }

    @Operation(summary = "编辑服务", description = "改价只影响新订单，历史订单金额不变")
    @PutMapping("/skus/{id}")
    public Result<CatalogDto.SkuVO> update(@PathVariable Long id,
                                           @Valid @RequestBody CatalogDto.SkuUpsertReq req) {
        return Result.ok(catalogService.updateSku(id, req));
    }
}
