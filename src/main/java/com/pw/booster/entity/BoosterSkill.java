package com.pw.booster.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 打手技能（可服务类目 + 报价）。对应 05 §3.7。
 *
 * <p>{@code uk_booster_category} 唯一：一个打手对一个类目只有一条报价。</p>
 *
 * <p>接单守卫（01 §3）：「打手 ACTIVE、<b>类目匹配</b>」即查本表。</p>
 */
@Data
@TableName("booster_skill")
public class BoosterSkill {

    @TableId
    private Long id;

    private Long boosterId;

    private Long categoryId;

    /** 该打手在该类目的报价（分） */
    private Long price;

    /** 排班 JSON */
    private String scheduleJson;

    /** 是否启用（0/1） */
    private Integer enabled;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /**
     * 该技能是否上架。
     *
     * <p><b>命名注意</b>：不能叫 {@code isEnabled()}。字段 {@code enabled} 是 {@code Integer}，
     * Lombok {@code @Data} 会生成 {@code getEnabled()}；再手写返回 {@code boolean} 的
     * {@code isEnabled()} 会让 MyBatis 反射判定属性存在「类型二义的重载 getter」并抛
     * {@code ReflectionException}，导致 booster_skill 表读写失败。故此处用 {@code enabledFlag()}。</p>
     */
    public boolean enabledFlag() {
        return enabled != null && enabled == 1;
    }
}
