package com.pw.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 客户游戏资料。既是请求体也是响应体，对应 04 §2.1 {@code interface GameProfile}。
 *
 * <p>{@code accountId} 是客户本人游戏 ID。<b>该字段对打手不可见</b>——
 * 打手端只拿客服发送的 {@code bossGameId}（README §5、04 §3.6 注）。</p>
 */
@Data
@Schema(description = "客户游戏资料")
public class GameProfileReq {

    @Schema(description = "资料 ID，创建时无需传；响应必带", example = "7001")
    private Long id;

    @NotBlank(message = "请填写游戏 ID")
    @Size(max = 32, message = "游戏 ID 过长")
    @Schema(description = "游戏标识", requiredMode = Schema.RequiredMode.REQUIRED, example = "DELTA")
    private String gameId;

    @NotBlank(message = "请填写游戏账号 ID")
    @Size(max = 64, message = "游戏账号 ID 过长")
    @Schema(description = "客户本人游戏账号 ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "12345678")
    private String accountId;

    @Size(max = 64, message = "区服名称过长")
    @Schema(description = "区服", example = "安卓QQ区")
    private String server;

    @Size(max = 64, message = "游戏昵称过长")
    @Schema(description = "游戏内昵称", example = "三角洲老司机")
    private String nickname;

    @Schema(description = "是否设为默认", example = "true")
    private Boolean isDefault;
}
