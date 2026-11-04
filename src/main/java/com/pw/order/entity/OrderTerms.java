package com.pw.order.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 订单服务条款快照。对应 {@code order_terms} 表（05 §4.5）。
 *
 * <p>下单时把当时的条款内容固化进 {@code contentJson}，后续条款变更不影响已下单订单。
 * {@code uk_order} 保证一单一条。</p>
 */
@Data
@TableName("order_terms")
public class OrderTerms implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId
    private Long id;

    private Long orderId;

    private Long templateId;

    /** 条款版本号 */
    private String version;

    /** 条款内容快照 JSON */
    private String contentJson;

    /** 客户确认时间 */
    private LocalDateTime confirmedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
