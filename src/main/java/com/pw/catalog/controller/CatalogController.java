package com.pw.catalog.controller;

import com.pw.catalog.dto.CatalogDto;
import com.pw.catalog.service.CatalogService;
import com.pw.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 小程序端类目 / SKU 读取接口（公开，仅返回已上架数据）。
 *
 * <p>对应技术规范 04 §3.3 类目与 SKU；管理端配置后小程序即时生效。</p>
 */
@Tag(name = "03-类目与SKU（小程序端）", description = "类目列表、类目下已上架 SKU")
@RestController
@RequestMapping("/categories")
@RequiredArgsConstructor
public class CatalogController {

    private final CatalogService catalogService;

    @Operation(summary = "类目列表", description = "仅返回已启用的类目，按 sort 升序")
    @GetMapping
    public Result<List<CatalogDto.CategoryVO>> list() {
        return Result.ok(catalogService.listCategories(false));
    }

    @Operation(summary = "类目下的 SKU", description = "仅返回已上架 SKU，含地图 / 介绍 / 单价")
    @GetMapping("/{id}/skus")
    public Result<List<CatalogDto.SkuVO>> skus(@PathVariable Long id) {
        return Result.ok(catalogService.listEnabledSkus(id));
    }
}
