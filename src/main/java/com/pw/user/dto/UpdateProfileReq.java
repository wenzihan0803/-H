package com.pw.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 更新资料请求。对应 04 §3.1 {@code PUT /users/me/profile}，两个字段均可选。
 */
@Data
@Schema(description = "更新用户资料请求")
public class UpdateProfileReq {

    @Size(max = 64, message = "昵称不能超过 64 个字符")
    @Schema(description = "昵称", example = "稳如老狗")
    private String nickname;

    @Size(max = 512, message = "头像地址过长")
    @Schema(description = "头像 URL 或文件 objectKey")
    private String avatar;

    /** 至少填一项，避免空更新。 */
    public boolean isEmpty() {
        return (nickname == null || nickname.isBlank()) && (avatar == null || avatar.isBlank());
    }
}
