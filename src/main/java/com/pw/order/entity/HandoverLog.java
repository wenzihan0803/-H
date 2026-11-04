package com.pw.order.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 交接操作日志。对应 {@code handover_log} 表（05 §4.12）。
 *
 * <p>{@code bossIdCipher} 存加密后的老板游戏 ID（01 §17 要求敏感字段加密），
 * 打手端只能看到客服下发的 {@code guide} 与解密后的 {@code bossGameId}。</p>
 */
@Data
@TableName("handover_log")
public class HandoverLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId
    private Long id;

    private Long orderId;

    private Long taskId;

    /** SENT / ACK / TIMEOUT / ESCALATE */
    private String action;

    /** IM / WECHAT / PHONE */
    private String channel;

    /** 老板游戏 ID 密文 */
    private String bossIdCipher;

    private String guide;

    private Long operatorId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
