package com.pw.catalog.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pw.catalog.dto.CatalogDto;
import com.pw.catalog.entity.ServiceCategory;
import com.pw.catalog.entity.ServiceSku;
import com.pw.catalog.mapper.ServiceCategoryMapper;
import com.pw.catalog.mapper.ServiceSkuMapper;
import com.pw.common.exception.BizException;
import com.pw.common.exception.ErrorCode;
import com.pw.common.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 类目 / SKU 配置服务（管理端维护，小程序端读取）。
 *
 * <p>单价只影响新订单：下单时订单金额会落快照，改价不回溯历史订单（04 §3.7）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CatalogService {

    private static final int DEFAULT_COMMISSION_RATE = 15;

    private final ServiceCategoryMapper categoryMapper;
    private final ServiceSkuMapper skuMapper;
    private final IdGenerator idGenerator;

    /** 类目列表；管理端传 includeDisabled=true 查看全部。 */
    public List<CatalogDto.CategoryVO> listCategories(boolean includeDisabled) {
        LambdaQueryWrapper<ServiceCategory> q = new LambdaQueryWrapper<ServiceCategory>()
                .orderByAsc(ServiceCategory::getSort)
                .orderByAsc(ServiceCategory::getId);
        if (!includeDisabled) {
            q.eq(ServiceCategory::getEnabled, 1);
        }
        return categoryMapper.selectList(q).stream().map(CatalogService::toCategoryVO).toList();
    }

    /** SKU 列表；管理端可按类目 / 关键字 / 上下架筛选，小程序端只看上架。 */
    public List<CatalogDto.SkuVO> listSkus(Long categoryId, String keyword, Boolean enabledOnly) {
        LambdaQueryWrapper<ServiceSku> q = new LambdaQueryWrapper<ServiceSku>()
                .eq(categoryId != null, ServiceSku::getCategoryId, categoryId)
                .eq(Boolean.TRUE.equals(enabledOnly), ServiceSku::getEnabled, 1)
                .orderByAsc(ServiceSku::getSort)
                .orderByAsc(ServiceSku::getId);
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            q.and(w -> w.like(ServiceSku::getName, kw)
                    .or().like(ServiceSku::getMapName, kw)
                    .or().like(ServiceSku::getDescription, kw));
        }
        return skuMapper.selectList(q).stream().map(CatalogService::toSkuVO).toList();
    }

    /** 小程序端：某类目下已上架的 SKU。 */
    public List<CatalogDto.SkuVO> listEnabledSkus(Long categoryId) {
        requireCategory(categoryId);
        return listSkus(categoryId, null, true);
    }

    @Transactional(rollbackFor = Exception.class)
    public CatalogDto.SkuVO createSku(CatalogDto.SkuUpsertReq req) {
        ServiceCategory category = requireCategory(req.getCategoryId());
        validateQty(req);

        ServiceSku sku = new ServiceSku();
        sku.setId(idGenerator.nextId());
        sku.setCategoryId(category.getId());
        sku.setUnit(category.getUnit());
        applyFields(sku, req);
        sku.setCommissionRate(DEFAULT_COMMISSION_RATE);
        sku.setEnabled(req.getEnabled() == null || req.getEnabled() ? 1 : 0);
        sku.setSort(req.getSort() == null ? 0 : req.getSort());
        skuMapper.insert(sku);
        log.info("新增服务 SKU id={} categoryId={} name={}", sku.getId(), sku.getCategoryId(), sku.getName());
        return toSkuVO(sku);
    }

    @Transactional(rollbackFor = Exception.class)
    public CatalogDto.SkuVO updateSku(Long id, CatalogDto.SkuUpsertReq req) {
        ServiceSku sku = skuMapper.selectById(id);
        if (sku == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "服务不存在或已删除");
        }
        ServiceCategory category = requireCategory(req.getCategoryId());
        validateQty(req);

        sku.setCategoryId(category.getId());
        sku.setUnit(category.getUnit());
        applyFields(sku, req);
        if (req.getEnabled() != null) {
            sku.setEnabled(req.getEnabled() ? 1 : 0);
        }
        if (req.getSort() != null) {
            sku.setSort(req.getSort());
        }
        skuMapper.updateById(sku);
        log.info("更新服务 SKU id={} name={} enabled={}", sku.getId(), sku.getName(), sku.getEnabled());
        return toSkuVO(sku);
    }

    private void applyFields(ServiceSku sku, CatalogDto.SkuUpsertReq req) {
        sku.setName(req.getName().trim());
        sku.setMapName(trimToNull(req.getMapName()));
        sku.setDescription(trimToNull(req.getDescription()));
        sku.setPrice(req.getPrice());
        sku.setMinQty(req.getMinQty());
        sku.setMaxQty(req.getMaxQty());
    }

    private void validateQty(CatalogDto.SkuUpsertReq req) {
        if (req.getMaxQty() < req.getMinQty()) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "最大数量不能小于最小数量");
        }
    }

    private ServiceCategory requireCategory(Long categoryId) {
        ServiceCategory category = categoryMapper.selectById(categoryId);
        if (category == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "类目不存在");
        }
        return category;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static CatalogDto.CategoryVO toCategoryVO(ServiceCategory c) {
        return CatalogDto.CategoryVO.builder()
                .id(String.valueOf(c.getId()))
                .name(c.getName())
                .unit(c.getUnit())
                .verifyType(c.getVerifyType())
                .enabled(c.getEnabled() != null && c.getEnabled() == 1)
                .sort(c.getSort())
                .build();
    }

    public static CatalogDto.SkuVO toSkuVO(ServiceSku s) {
        return CatalogDto.SkuVO.builder()
                .id(String.valueOf(s.getId()))
                .categoryId(String.valueOf(s.getCategoryId()))
                .name(s.getName())
                .mapName(s.getMapName())
                .description(s.getDescription())
                .unit(s.getUnit())
                .price(String.valueOf(s.getPrice()))
                .commissionRate(s.getCommissionRate())
                .minQty(s.getMinQty())
                .maxQty(s.getMaxQty())
                .enabled(s.getEnabled() != null && s.getEnabled() == 1)
                .sort(s.getSort())
                .build();
    }
}
