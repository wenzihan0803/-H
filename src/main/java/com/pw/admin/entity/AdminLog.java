package com.pw.admin.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 管理端操作日志。对应 {@code admin_log}。 */
@Data
@TableName("admin_log")
public class AdminLog {

    @TableId
    private Long id;

    private Long adminId;

    /** 模块，如 ORDER / REFUND */
    private String module;

    private String action;

    private String beforeJson;

    private String afterJson;

    private String ip;

    private LocalDateTime createdAt;
}
