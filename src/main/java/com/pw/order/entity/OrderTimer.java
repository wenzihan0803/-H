package com.pw.order.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 订单服务计时器。对应 {@code order_timer} 表（05 §4.8）。
 *
 * <p>记录服务实际时长，供对账与争议判定参考。{@code uk_order} 保证一单一条。</p>
 */
@Data
@TableName("order_timer")
public class OrderTimer implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId
    private Long id;

    private Long orderId;

    /** 计时开始时间 */
    private LocalDateTime startAt;

    /** 累计暂停秒数 */
    private Integer pausedSeconds;

    /** 最近一次心跳时间 */
    private LocalDateTime heartbeatAt;

    /** 累计服务秒数 */
    private Integer totalSeconds;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
