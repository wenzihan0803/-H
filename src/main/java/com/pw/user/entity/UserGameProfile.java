package com.pw.user.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 客户游戏资料。对应 05 §2.3。
 *
 * <p>下单时通过 {@code gameProfileId} 引用（04 §2.4 CreateOrderReq）。
 * {@code uk_user_account} 防止同一账号重复录入。</p>
 *
 * <p><b>越权约束</b>：区服、昵称等资料对打手不可见，打手端只拿客服发送的
 * {@code bossGameId} + {@code guide}（README §5、04 §3.6 注）。</p>
 */
@Data
@TableName("user_game_profile")
public class UserGameProfile {

    @TableId
    private Long id;

    private Long userId;

    private String gameId;

    /** 客户本人游戏账号 ID */
    private String accountId;

    private String server;

    private String nickname;

    /** 是否默认（0=否 1=是） */
    private Integer isDefault;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
