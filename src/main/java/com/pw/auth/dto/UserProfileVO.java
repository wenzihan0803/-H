package com.pw.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

/**
 * 用户资料。对应 04 §2.1 {@code interface UserProfile}，字段必须与之完全一致。
 *
 * <p><b>ID 序列化为字符串</b>：由 {@code JacksonConfig} 的 Long→String 全局策略处理，
 * 这里保持 Long 类型，避免业务层反复转换（04 §1「ID 字符串雪花 ID，避免 JS 精度丢失」）。</p>
 *
 * <p>{@code goldBalance} 仅客户侧有意义；打手侧固定为 0（04 §2.1 注「客户侧展示」）。</p>
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "用户资料")
public class UserProfileVO {

    @Schema(description = "用户 ID（雪花，字符串）", example = "3001")
    private Long id;

    @Schema(description = "昵称", example = "测试老板A")
    private String nickname;

    @Schema(description = "头像 URL")
    private String avatar;

    @Schema(description = "脱敏手机号，形如 138****0000", example = "138****0000")
    private String phoneMasked;

    @Schema(description = "是否已实名", example = "true")
    private Boolean verified;

    @Schema(description = "会员等级", allowableValues = {"NORMAL", "MEMBER"}, example = "NORMAL")
    private String memberLevel;

    @Schema(description = "金砖余额（分）。仅客户侧展示，1 金砖 = 1 元", example = "100000")
    private Long goldBalance;
}
