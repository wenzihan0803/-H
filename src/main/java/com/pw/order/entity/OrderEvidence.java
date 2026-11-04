package com.pw.order.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 订单履约凭证。对应 {@code order_evidence} 表（05 §4.7）。
 *
 * <p>开局照（START）与结束照（END）是「开始服务」「申请完单」的强制 guard（01 §3）。</p>
 */
@Data
@TableName("order_evidence")
public class OrderEvidence implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId
    private Long id;

    private Long orderId;

    /** START / END / SUPPLEMENT */
    private String type;

    private String objectKey;

    /** 文件 SHA-256，用于防重复提交与篡改检测 */
    private String hash;

    private Integer size;

    private LocalDateTime takenAt;

    private Long uploaderId;

    /** 是否补交 */
    private Integer supplement;

    private Long verifiedBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
