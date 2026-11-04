package com.pw.member.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会员账户。对应 05 §2.6。
 *
 * <p>口径（README §4）：</p>
 * <ul>
 *   <li>单笔充值满 2,000 元自动建 {@code member_apply(PENDING)}，管理员通过后 level=MEMBER；</li>
 *   <li><b>永久有效</b>，is_permanent=1；</li>
 *   <li>9 折：客户实付 90、托管 90；打手仍拿原价 85，平台拿 5（折扣由平台承担）；</li>
 *   <li>退款不降级；</li>
 *   <li><b>禁止</b>把「充值 2,000」做成独立余额或储值卡——它只是金砖余额 + 会员资格。</li>
 * </ul>
 */
@Data
@TableName("member_account")
public class MemberAccount {

    /** 主键即 user_id */
    @TableId
    private Long userId;

    /** NORMAL / MEMBER */
    private String level;

    /** 触发会员资格的充值单 id */
    private Long qualifiedRechargeId;

    /** 是否永久（0/1） */
    private Integer isPermanent;

    private Long approvedBy;

    private LocalDateTime approvedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public static final String LEVEL_NORMAL = "NORMAL";
    public static final String LEVEL_MEMBER = "MEMBER";

    public boolean isMember() {
        return LEVEL_MEMBER.equals(level);
    }

    public String getLevelOrDefault() {
        return level == null ? LEVEL_NORMAL : level;
    }
}
