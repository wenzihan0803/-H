package com.pw.booster.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 打手入驻申请。对应 {@code booster_apply}。
 *
 * <p>一个用户最多一条申请；被拒后可重新提交（复用同一条，状态回 PENDING）。</p>
 */
@Data
@TableName("booster_apply")
public class BoosterApply {

    @TableId
    private Long id;

    private Long userId;

    /** 1 = 资料提交（本期不做多步） */
    private Integer step;

    /** PENDING / APPROVED / REJECTED */
    private String status;

    private String rejectReason;

    private LocalDateTime submittedAt;

    private Long reviewedBy;

    private LocalDateTime reviewedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
}
