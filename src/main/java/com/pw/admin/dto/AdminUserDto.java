package com.pw.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** 管理端用户与操作日志。 */
public final class AdminUserDto {

    private AdminUserDto() {
    }

    @Data
    @Builder
    @Schema(description = "用户")
    public static class UserRow {
        private Long id;
        private String nickname;
        private String phone;
        private String status;
        private Integer verified;
        private String memberLevel;
        private Long goldBalance;
        private Long boosterId;
        private String boosterStatus;
        private LocalDateTime createdAt;
    }

    @Data
    @Builder
    @Schema(description = "操作日志")
    public static class LogRow {
        private Long id;
        private Long adminId;
        private String adminName;
        private String module;
        private String action;
        private String ip;
        private LocalDateTime createdAt;
    }
}
