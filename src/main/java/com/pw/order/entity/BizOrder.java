package com.pw.order.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 订单主表。对应 {@code biz_order} 表（05 §4.3）。
 *
 * <p><b>状态机</b>（01 §3、README §1）：8 个状态
 * {@code PENDING_ACCEPT → ACCEPTED → IN_SERVICE → PENDING_CONFIRM → COMPLETED}，
 * 另有 {@code DISPUTING / REFUNDED / PARTIAL_REFUNDED}。
 * 没有 PENDING_PAY / AFTER_SALE / REFUNDING / CANCELLED / SETTLED。</p>
 *
 * <p><b>金额口径</b>（README §0.1）：全部整数分。
 * {@code payAmount = originalAmount - memberDiscount}，且等于托管金额（邀请功能已删除）。
 * {@code boosterAmount} / {@code platformAmount} 仅在结算后写入。</p>
 *
 * <p><b>敏感字段</b>：{@code bossGameIdCipher} 存密文（01 §17），打手端不可见客户区服与备注。</p>
 */
@Data
@TableName("biz_order")
public class BizOrder implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId
    private Long id;

    /** 订单号，形如 PW202610280001 */
    private String orderNo;

    private Long customerId;

    /** 打手 ID；大厅模式在接单前为 null */
    private Long boosterId;

    private Long categoryId;

    private Long skuId;

    /** DESIGNATED（指定）/ HALL（大厅抢单） */
    private String mode;

    /** 数量，1-10 */
    private Integer quantity;

    /** 原价（分） */
    private Long originalAmount;

    /** 会员折扣（分），会员 9 折时为原价 10% */
    private Long memberDiscount;

    /** 实付金额（分）= 托管金额 */
    private Long payAmount;

    /** 结算后写入：打手收益（分） */
    private Long boosterAmount;

    /** 结算后写入：平台收入（分） */
    private Long platformAmount;

    /** 订单状态，见类注释 */
    private String status;

    /** 交接状态：PENDING/SENT/ACK/TIMEOUT */
    private String handoverStatus;

    /** 开始服务时使用的打手游戏账号 ID */
    private Long boosterGameAccountId;

    /** 老板游戏 ID 密文 */
    private String bossGameIdCipher;

    /** 预约时间 */
    private LocalDateTime appointAt;

    /** 打手申请完单时间 */
    private LocalDateTime serviceCompletedAt;

    /** 自动确认截止时间 = serviceCompletedAt + 7d */
    private LocalDateTime confirmDeadline;

    /** 客户确认或自动确认时间 */
    private LocalDateTime confirmedAt;

    /** 退款完成时间 */
    private LocalDateTime refundedAt;

    /** 退款原因 */
    private String refundReason;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
