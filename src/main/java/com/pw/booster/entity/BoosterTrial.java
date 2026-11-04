package com.pw.booster.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 入驻考核/审核记录。对应 {@code booster_trial}。 */
@Data
@TableName("booster_trial")
public class BoosterTrial {

    @TableId
    private Long id;

    private Long applyId;

    /** APPROVED / REJECTED */
    private String result;

    private String remark;

    private Long operatorId;

    private LocalDateTime finishedAt;

    private LocalDateTime createdAt;
}
