package com.pw.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** 管理端会员视图（只读）。 */
public final class MemberDto {

    private MemberDto() {
    }

    @Data
    @Builder
    @Schema(description = "会员")
    public static class Member {
        private Long userId;
        private String nickname;
        private String phone;
        /** 累计充值（分） */
        private Long totalRecharged;
        /** NORMAL / MEMBER */
        private String level;
        private Integer permanent;
        private LocalDateTime memberSince;
    }
}
