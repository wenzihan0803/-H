package com.pw.booster.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增游戏账号请求。对应 04 §3.6 {@code POST /booster/game-accounts}。
 */
@Data
@Schema(description = "新增游戏账号请求")
public class GameAccountReq {

    @NotBlank(message = "请填写游戏")
    @Size(max = 64, message = "游戏名过长")
    @Schema(description = "游戏标识/名称", example = "三角洲行动")
    private String gameId;

    @NotBlank(message = "请填写游戏账号 ID")
    @Size(max = 64, message = "账号 ID 过长")
    @Schema(description = "游戏账号 ID", example = "acc_10086")
    private String accountId;

    @Size(max = 64, message = "区服名过长")
    @Schema(description = "区服，可选", example = "QQ 区")
    private String server;
}
