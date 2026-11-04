package com.pw.booster.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 打手排班/在线状态。对应 05 §3.8。
 *
 * <p>{@code booster_id} 为主键，一人一条。首页「在线打手」与大厅派单依赖本表 status。</p>
 */
@Data
@TableName("booster_schedule")
public class BoosterSchedule {

    @TableId
    private Long boosterId;

    /** ONLINE / BUSY / OFF */
    private String status;

    /** 可服务时段 JSON */
    private String slotsJson;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public static final String STATUS_ONLINE = "ONLINE";
    public static final String STATUS_BUSY = "BUSY";
    public static final String STATUS_OFF = "OFF";

    public boolean isOnline() {
        return STATUS_ONLINE.equals(status);
    }
}
