package com.pw.booster.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 打手入驻与资料修改的请求体。 */
public final class BoosterApplyReq {

    private BoosterApplyReq() {
    }

    @Data
    @Schema(description = "提交入驻申请")
    public static class Apply {
        @NotBlank(message = "请填写打手昵称")
        @Size(max = 20, message = "昵称不能超过 20 字")
        private String nickname;

        @NotNull(message = "请填写年龄")
        @Min(value = 18, message = "年龄需满 18 岁")
        @Max(value = 60, message = "年龄不能超过 60 岁")
        private Integer age;

        @NotBlank(message = "请选择性别")
        @Pattern(regexp = "MALE|FEMALE", message = "性别只能是男或女")
        private String gender;

        @NotBlank(message = "请选择游戏")
        @Size(max = 32, message = "游戏标识过长")
        private String gameKey;

        @NotBlank(message = "请填写游戏内 ID")
        @Size(max = 64, message = "游戏 ID 过长")
        private String gameAccount;

        @Size(max = 32, message = "段位过长")
        private String rankText;

        @Size(max = 64, message = "微信号过长")
        private String wechat;

        @Size(max = 500, message = "介绍不能超过 500 字")
        private String intro;
    }

    @Data
    @Schema(description = "审核拒绝")
    public static class Reject {
        @NotBlank(message = "请填写拒绝理由")
        @Size(max = 200, message = "理由不能超过 200 字")
        private String reason;
    }

    @Data
    @Schema(description = "打手资料修改")
    public static class Profile {
        @Size(max = 20, message = "昵称不能超过 20 字")
        private String nickname;

        @Min(value = 18, message = "年龄需满 18 岁")
        @Max(value = 60, message = "年龄不能超过 60 岁")
        private Integer age;

        @Pattern(regexp = "MALE|FEMALE", message = "性别只能是男或女")
        private String gender;
    }
}
