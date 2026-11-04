package com.pw.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pw.admin.dto.BannerDto;
import com.pw.admin.entity.Banner;
import com.pw.admin.mapper.BannerMapper;
import com.pw.common.exception.BizException;
import com.pw.common.exception.ErrorCode;
import com.pw.common.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/** 首页 Banner 管理。 */
@Service
@RequiredArgsConstructor
public class AdminBannerService {

    private final BannerMapper bannerMapper;
    private final IdGenerator idGenerator;

    public List<BannerDto.Row> list(String status) {
        LambdaQueryWrapper<Banner> wrapper = new LambdaQueryWrapper<Banner>()
                .orderByAsc(Banner::getSort)
                .orderByDesc(Banner::getId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(Banner::getStatus, status);
        }
        List<BannerDto.Row> rows = new ArrayList<>();
        for (Banner banner : bannerMapper.selectList(wrapper)) {
            rows.add(toRow(banner));
        }
        return rows;
    }

    @Transactional(rollbackFor = Exception.class)
    public Long create(BannerDto.SaveReq req) {
        Banner banner = new Banner();
        banner.setId(idGenerator.nextId());
        apply(banner, req);
        bannerMapper.insert(banner);
        return banner.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, BannerDto.SaveReq req) {
        Banner banner = bannerMapper.selectById(id);
        if (banner == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "Banner 不存在");
        }
        apply(banner, req);
        bannerMapper.updateById(banner);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        if (bannerMapper.deleteById(id) == 0) {
            throw BizException.of(ErrorCode.NOT_FOUND, "Banner 不存在");
        }
    }

    private void apply(Banner banner, BannerDto.SaveReq req) {
        banner.setTitle(req.getTitle().trim());
        banner.setImageObjectKey(req.getImageObjectKey());
        banner.setLinkType(req.getLinkType() == null ? "NONE" : req.getLinkType().toUpperCase());
        banner.setLinkValue(req.getLinkValue());
        banner.setSort(req.getSort() == null ? 0 : req.getSort());
        banner.setStatus(req.getStatus() == null ? Banner.STATUS_ON : req.getStatus().toUpperCase());
        banner.setStartAt(req.getStartAt());
        banner.setEndAt(req.getEndAt());
    }

    private BannerDto.Row toRow(Banner b) {
        return BannerDto.Row.builder()
                .id(b.getId())
                .title(b.getTitle())
                .imageObjectKey(b.getImageObjectKey())
                .linkType(b.getLinkType())
                .linkValue(b.getLinkValue())
                .sort(b.getSort())
                .status(b.getStatus())
                .startAt(b.getStartAt())
                .endAt(b.getEndAt())
                .createdAt(b.getCreatedAt())
                .updatedAt(b.getUpdatedAt())
                .build();
    }
}
