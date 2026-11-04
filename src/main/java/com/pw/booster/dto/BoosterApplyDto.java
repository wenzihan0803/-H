package com.pw.booster.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** 入驻申请视图（管理端与打手端共用，字段做脱敏裁剪由控制器决定）。 */
public final class BoosterApplyDto {

    private BoosterApplyDto() {
    }

    @Data
    @Builder
    @Schema(description = "入驻申请")
    public static class View {
        private Long id;
        private Long userId;
        private String status;
        private String rejectReason;
        private LocalDateTime submittedAt;
        private LocalDateTime reviewedAt;
        private String nickname;
        private String phone;
        private Integer age;
        private String gender;
        private String gameKey;
        private String gameAccount;
        private String rankText;
        private String wechat;
        private String intro;
        private LocalDateTime createdAt;
    }

    @Data
    @Builder
    @Schema(description = "考核/审核记录")
    public static class Trial {
        private Long id;
        private String result;
        private String remark;
        private Long operatorId;
        private LocalDateTime finishedAt;
        private LocalDateTime createdAt;
    }

    @Data
    @Builder
    @Schema(description = "打手资料")
    public static class ProfileView {
        private Long id;
        private String nickname;
        private Integer age;
        private String gender;
        private String status;
        private LocalDateTime nicknameUpdatedAt;
        /** 下次可改昵称时间；为空表示现在就能改 */
        private LocalDateTime nicknameChangeableAt;
    }
}
