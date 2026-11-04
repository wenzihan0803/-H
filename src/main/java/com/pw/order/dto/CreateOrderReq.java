package com.pw.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 创建订单请求。对应 04 §2.4 {@code CreateOrderReq}。
 *
 * <p><b>没有支付相关字段</b>：下单即扣金砖，微信只用于充值（01 §5、04 §3.4）。</p>
 */
@Data
@Schema(description = "创建订单请求")
public class CreateOrderReq {

    @NotNull(message = "请选择服务类目")
    @Schema(description = "类目 ID", example = "1001")
    private Long categoryId;

    @NotNull(message = "请选择服务规格")
    @Schema(description = "SKU ID", example = "2001")
    private Long skuId;

    @NotNull(message = "请选择下单模式")
    @Schema(description = "DESIGNATED=指定打手，HALL=大厅抢单", example = "DESIGNATED")
    private String mode;

    @Schema(description = "指定模式必填：打手 ID", example = "5001")
    private Long boosterId;

    @NotNull(message = "请填写数量")
    @Min(value = 1, message = "数量至少为 1")
    @Max(value = 10, message = "单笔最多 10 份")
    @Schema(description = "数量 1-10", example = "2")
    private Integer quantity;

    @NotNull(message = "请选择预约时间")
    @Schema(description = "预约时间，now+30min ~ 7d", example = "2026-10-29T21:00:00.000+08:00")
    private LocalDateTime appointAt;

    @Schema(description = "客户游戏资料 ID（v1 可选）", example = "4001")
    private Long gameProfileId;

    @Size(max = 64, message = "游戏 ID 过长")
    @Schema(description = "老板游戏 ID，加密存储，打手端仅在交接后可见", example = "12345678")
    private String bossGameId;

    @Size(max = 200, message = "需求备注不能超过 200 字")
    @Schema(description = "需求备注 ≤200 字，打手端不可见", example = "稳一点，优先撤离")
    private String requirement;
}
