package com.pw.common.idempotent;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 幂等记录。对应表 {@code idempotency_record}（技术规范/05 §7.7）。
 *
 * <p>唯一索引 {@code uk_idem (scene, user_id, idem_key)} 是幂等的最终保证：
 * 并发插入时只有一个成功，其余抛 DuplicateKeyException，由切面转为「返回首次结果」。</p>
 */
@Data
@TableName("idempotency_record")
public class IdempotencyRecord {

    @TableId
    private Long id;

    /** 业务场景，如 CREATE_ORDER */
    private String scene;

    private Long userId;

    /** 客户端传入的幂等键 */
    private String idemKey;

    /** 请求体 SHA-256，用于识别「同键不同参」的误用 */
    private String requestHash;

    /** 首次成功响应的 JSON，重复请求直接回传 */
    private String responseJson;

    /** PROCESSING / DONE */
    private String status;

    @com.baomidou.mybatisplus.annotation.TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
