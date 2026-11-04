package com.pw.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 提交实名认证请求。对应 04 §3.1 {@code POST /users/me/verify}。
 *
 * <p>{@code faceToken} 为人脸活体凭证，本期不做（09 §1），可省略。</p>
 */
@Data
@Schema(description = "实名认证请求")
public class VerifyReq {

    @NotBlank(message = "请填写真实姓名")
    @Size(max = 64, message = "姓名过长，请检查后重新填写")
    @Schema(description = "真实姓名", requiredMode = Schema.RequiredMode.REQUIRED, example = "张三")
    private String realName;

    @NotBlank(message = "请填写身份证号")
    @Schema(description = "身份证号，18 位", requiredMode = Schema.RequiredMode.REQUIRED, example = "110101199003074258")
    private String idCard;

    @Schema(description = "人脸活体凭证，本期可省略")
    private String faceToken;
}
