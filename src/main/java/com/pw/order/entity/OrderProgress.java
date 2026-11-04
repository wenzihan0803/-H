package com.pw.order.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 订单进度轨迹。对应 {@code order_progress} 表（05 §4.6）。
 *
 * <p>只追加、不修改。每次状态变更写一条（01 §3 状态机第 6 步），
 * 客户端时间线与管理端流水页都读这张表。</p>
 */
@Data
@TableName("order_progress")
public class OrderProgress implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId
    private Long id;

    private Long orderId;

    /** 变更前状态；创建订单时为 null */
    private String fromStatus;

    /** 变更后状态 */
    private String toStatus;

    /** 动作，对应 OrderAction 枚举名 */
    private String action;

    /** 操作方：CUSTOMER/BOOSTER/CS/ADMIN/SYSTEM */
    private String operatorType;

    private Long operatorId;

    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
