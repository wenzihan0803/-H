package com.pw.catalog.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pw.admin.entity.Banner;
import com.pw.admin.mapper.BannerMapper;
import com.pw.catalog.service.CatalogService;
import com.pw.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 客户端首页与公开 Banner（无需登录）。 */
@Tag(name = "21-客户端首页", description = "Banner + 类目")
@RestController
@RequiredArgsConstructor
public class HomeController {

    private final BannerMapper bannerMapper;
    private final CatalogService catalogService;

    @Operation(summary = "首页聚合", description = "启用中的 Banner + 已启用类目；热门打手暂不返回")
    @GetMapping("/home")
    public Result<Map<String, Object>> home() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("banners", banners());
        data.put("categories", catalogService.listCategories(false));
        data.put("hotBoosters", List.of());
        return Result.ok(data);
    }

    @Operation(summary = "公开 Banner 列表")
    @GetMapping("/banners")
    public Result<List<Map<String, Object>>> bannersApi() {
        return Result.ok(banners());
    }

    private List<Map<String, Object>> banners() {
        LocalDateTime now = LocalDateTime.now();
        List<Banner> list = bannerMapper.selectList(new LambdaQueryWrapper<Banner>()
                .eq(Banner::getStatus, Banner.STATUS_ON)
                .and(w -> w.isNull(Banner::getStartAt).or().le(Banner::getStartAt, now))
                .and(w -> w.isNull(Banner::getEndAt).or().ge(Banner::getEndAt, now))
                .orderByAsc(Banner::getSort)
                .orderByDesc(Banner::getId));
        List<Map<String, Object>> result = new ArrayList<>(list.size());
        for (Banner b : list) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", b.getId());
            row.put("title", b.getTitle());
            row.put("imageObjectKey", b.getImageObjectKey());
            row.put("imageUrl", ServletUriComponentsBuilder.fromCurrentContextPath()
                    .path("/files/download/").path(b.getImageObjectKey()).toUriString());
            row.put("linkType", b.getLinkType());
            row.put("linkValue", b.getLinkValue());
            result.add(row);
        }
        return result;
    }
}
