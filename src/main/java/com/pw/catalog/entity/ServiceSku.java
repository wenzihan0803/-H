package com.pw.catalog.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 服务 SKU。对应 {@code service_sku} 表（05 §4.2）。
 *
 * <p>金额字段 {@code price} 为整数分（README §0.1）；{@code commissionRate} 为百分比整数，
 * 默认 15，即平台抽佣 15%。</p>
 */
@Data
@TableName("service_sku")
public class ServiceSku implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId
    private Long id;

    private Long categoryId;

    /** SKU 名，如「一局·星耀以上」 */
    private String name;

    /** 地图名（展示用，如「零号大坝」），管理端可配置 */
    private String mapName;

    /** 服务介绍（展示用），管理端可配置 */
    private String description;

    /** 计量单位：ROUND/HOUR/LESSON/TIME */
    private String unit;

    /** 单价（分） */
    private Long price;

    /** 平台佣金比例（百分比整数），默认 15 */
    private Integer commissionRate;

    /** 单次下单最小数量 */
    private Integer minQty;

    /** 单次下单最大数量 */
    private Integer maxQty;

    /** 是否上架：1 上架，0 下架 */
    private Integer enabled;

    private Integer sort;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
