package com.pw.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 处置中心。 */
public final class PunishmentDto {

    private PunishmentDto() {
    }

    @Data
    @Builder
    @Schema(description = "处置记录")
    public static class Row {
        private Long id;
        private String targetType;
        private Long targetId;
        private String targetName;
        private String type;
        private String reason;
        private Long operatorId;
        private String operatorName;
        private LocalDateTime startAt;
        private LocalDateTime endAt;
        private String status;
        private LocalDateTime createdAt;
    }

    @Data
    @Schema(description = "新建处置")
    public static class CreateReq {
        /** BOOSTER / USER */
        @NotBlank(message = "请选择处置对象类型")
        private String targetType;
        @NotNull(message = "请选择处置对象")
        private Long targetId;
        /** WARNING / TEMP_BAN / BAN */
        @NotBlank(message = "请选择处置类型")
        private String type;
        @NotBlank(message = "请填写处置原因")
        @Size(max = 200, message = "原因不能超过 200 字")
        private String reason;
        /** 临时封禁时长（小时），TEMP_BAN 必填 */
        private Integer durationHours;
        /** 证据 objectKey 数组 */
        private List<String> evidenceKeys;
    }

    @Data
    @Builder
    @Schema(description = "处罚申诉")
    public static class AppealRow {
        private Long id;
        private Long punishmentId;
        private String reason;
        private String result;
        private Long reviewerId;
        private LocalDateTime createdAt;
    }
}
