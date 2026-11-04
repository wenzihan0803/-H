package com.pw.catalog.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 服务类目。对应 {@code service_category} 表（05 §4.1）。
 *
 * <p>类目由超管维护，已下单订单不受后续修改影响（04 §3.7「改价只影响新订单」）。</p>
 */
@Data
@TableName("service_category")
public class ServiceCategory implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId
    private Long id;

    /** 类目名，如「陪玩开黑」 */
    private String name;

    /** 计量单位：ROUND/HOUR/LESSON/TIME */
    private String unit;

    /** 实名要求类型 */
    private String verifyType;

    /** 是否上架：1 上架，0 下架 */
    private Integer enabled;

    /** 排序，升序 */
    private Integer sort;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
