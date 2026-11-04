package com.pw.admin.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/** 经营报表。 */
public final class ReportDto {

    private ReportDto() {
    }

    @Data
    @Builder
    @Schema(description = "汇总")
    public static class Summary {
        private Long orderCount;
        private Long completedCount;
        private String completeRate;
        private Long gmv;
        private Long platformAmount;
        private Long boosterAmount;
        private Long refundAmount;
        private Long newUsers;
        private Long newBoosters;
    }

    @Data
    @Builder
    @Schema(description = "按日")
    public static class Daily {
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        private LocalDate date;
        private Long orderCount;
        private Long gmv;
        private Long platformAmount;
    }

    @Data
    @Builder
    @Schema(description = "报表")
    public static class View {
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        private LocalDate from;
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        private LocalDate to;
        private Summary summary;
        private List<Daily> daily;
    }
}
