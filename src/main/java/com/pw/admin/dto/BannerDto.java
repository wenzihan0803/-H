package com.pw.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** 首页 Banner 管理。 */
public final class BannerDto {

    private BannerDto() {
    }

    @Data
    @Builder
    @Schema(description = "Banner")
    public static class Row {
        private Long id;
        private String title;
        private String imageObjectKey;
        private String linkType;
        private String linkValue;
        private Integer sort;
        private String status;
        private LocalDateTime startAt;
        private LocalDateTime endAt;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    @Data
    @Schema(description = "新增/编辑 Banner")
    public static class SaveReq {
        @NotBlank(message = "请填写标题")
        @Size(max = 64, message = "标题不能超过 64 字")
        private String title;
        @NotBlank(message = "请上传图片")
        @Size(max = 512, message = "图片 key 过长")
        private String imageObjectKey;
        private String linkType;
        private String linkValue;
        private Integer sort;
        /** ON / OFF */
        private String status;
        private LocalDateTime startAt;
        private LocalDateTime endAt;
    }
}
