package com.pw.order.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 客服交接任务。对应 {@code handover_task} 表（05 §4.11）。
 *
 * <p>接单后由客服向打手发送老板游戏 ID 与上号指引；打手 ACK 后才能开始服务。</p>
 */
@Data
@TableName("handover_task")
public class HandoverTask implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId
    private Long id;

    private Long orderId;

    private Long csId;

    /** PENDING / SENT / ACK / TIMEOUT */
    private String status;

    /** 交接截止时间 */
    private LocalDateTime dueAt;

    private LocalDateTime sentAt;

    private LocalDateTime ackAt;

    /** 超时升级次数 */
    private Integer escalateLevel;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
