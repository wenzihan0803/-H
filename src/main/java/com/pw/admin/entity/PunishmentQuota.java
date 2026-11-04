package com.pw.admin.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 处置配额（客服每日 / 管理员每月）。对应 {@code punishment_quota}。 */
@Data
@TableName("punishment_quota")
public class PunishmentQuota {

    @TableId
    private Long id;

    /** CS / ADMIN */
    private String role;

    private Integer dailyLimit;

    private Integer monthlyLimit;

    private Integer usedToday;

    private Integer usedMonth;

    private LocalDateTime resetAt;
}
