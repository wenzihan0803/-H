package com.pw.catalog.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Data;

/**
 * 类目 / SKU 接口模型（管理端配置 + 小程序端读取共用）。
 *
 * <p>金额字段 {@code price} 统一为整数分，序列化成字符串，避免 JS 精度问题；
 * id 同样序列化成字符串（雪花 id）。</p>
 */
public final class CatalogDto {

    private CatalogDto() {
    }

    @Data
    @Builder
    @Schema(description = "服务类目")
    public static class CategoryVO {
        @Schema(description = "类目 ID")
        private String id;
        @Schema(description = "类目名")
        private String name;
        @Schema(description = "计价单位：HOUR / VALUE")
        private String unit;
        @Schema(description = "核验方式")
        private String verifyType;
        @Schema(description = "是否启用")
        private Boolean enabled;
        @Schema(description = "排序")
        private Integer sort;
    }

    @Data
    @Builder
    @Schema(description = "服务 SKU")
    public static class SkuVO {
        @Schema(description = "SKU ID")
        private String id;
        @Schema(description = "所属类目 ID")
        private String categoryId;
        @Schema(description = "名称")
        private String name;
        @Schema(description = "地图")
        private String mapName;
        @Schema(description = "介绍")
        private String description;
        @Schema(description = "计价单位")
        private String unit;
        @Schema(description = "单价（分）")
        private String price;
        @Schema(description = "佣金比例（%）")
        private Integer commissionRate;
        @Schema(description = "最小数量")
        private Integer minQty;
        @Schema(description = "最大数量")
        private Integer maxQty;
        @Schema(description = "是否上架")
        private Boolean enabled;
        @Schema(description = "排序")
        private Integer sort;
    }

    /**
     * 新增 / 编辑 SKU 请求（管理端）。
     *
     * <p>unit 不在这里传：由所属类目决定（陪玩/教学=HOUR、护航=VALUE），
     * 避免同一个类目下出现计价单位不一致的数据。</p>
     */
    @Data
    @Schema(description = "SKU 新增/编辑请求")
    public static class SkuUpsertReq {
        @NotNull(message = "请选择类目")
        private Long categoryId;

        @NotBlank(message = "请填写服务名称")
        @Size(max = 64, message = "名称不能超过 64 字")
        private String name;

        @Size(max = 32, message = "地图不能超过 32 字")
        private String mapName;

        @Size(max = 255, message = "介绍不能超过 255 字")
        private String description;

        @NotNull(message = "请填写价格")
        @Min(value = 0, message = "价格不能为负数")
        private Long price;

        @NotNull(message = "请填写最小数量")
        @Min(value = 1, message = "最小数量至少为 1")
        private Integer minQty;

        @NotNull(message = "请填写最大数量")
        @Min(value = 1, message = "最大数量至少为 1")
        private Integer maxQty;

        private Boolean enabled;

        private Integer sort;
    }
}
