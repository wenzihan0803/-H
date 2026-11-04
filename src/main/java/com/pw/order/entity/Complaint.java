package com.pw.order.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 仲裁工单。对应 {@code complaint}。
 *
 * <p>客户在确认收货前发起；订单转 DISPUTING，钱继续托管。
 * 管理员处理：扣款比例 0 驳回（回原状态），1–100 退客户、剩余全给打手。</p>
 */
@Data
@TableName("complaint")
public class Complaint {

    @TableId
    private Long id;

    /** ARBITRATION */
    private String type;

    /** NORMAL / HIGH */
    private String priority;

    private Long orderId;

    private Long customerId;

    private Long boosterId;

    private String description;

    private String evidenceJson;

    private String handleNote;

    private Long ownerId;

    /** OPEN / PROCESSING / RESOLVED / CLOSED */
    private String status;

    /** 仲裁前订单状态 */
    private String previousStatus;

    /** 仲裁通过后生成的退款单 */
    private Long refundId;

    private LocalDateTime resolvedAt;

    private LocalDateTime slaDue;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_RESOLVED = "RESOLVED";
    public static final String STATUS_CLOSED = "CLOSED";
}
