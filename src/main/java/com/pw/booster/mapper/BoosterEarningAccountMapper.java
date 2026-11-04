package com.pw.booster.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pw.booster.entity.BoosterEarningAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 打手收益账户 Mapper。
 *
 * <p>资金表操作规范（05 §1.2、01 §14）：<b>先 FOR UPDATE 锁行，再带条件 UPDATE</b>。
 * 所有变更都必须同时写 {@code booster_earning_txn}，且靠 {@code (biz_type,biz_no)} 唯一防重。</p>
 */
@Mapper
public interface BoosterEarningAccountMapper extends BaseMapper<BoosterEarningAccount> {

    /** 行锁读取。必须在事务内调用。 */
    @Select("SELECT * FROM booster_earning_account WHERE booster_id = #{boosterId} FOR UPDATE")
    BoosterEarningAccount selectForUpdate(@Param("boosterId") Long boosterId);

    /**
     * 结算入账：available += amount, total += amount（README §0.1 第 2 点，确认即到账）。
     *
     * @return 影响行数
     */
    @Update("""
            UPDATE booster_earning_account
               SET available = available + #{amount}, total = total + #{amount}, version = version + 1
             WHERE booster_id = #{boosterId}
            """)
    int settle(@Param("boosterId") Long boosterId, @Param("amount") long amount);

    /**
     * 提现冻结：available → frozen（01 §7.3-A）。
     *
     * <p>{@code available >= amount} 作为条件，并发下只有一个成功。</p>
     *
     * @return 影响行数，0 表示余额不足或并发冲突
     */
    @Update("""
            UPDATE booster_earning_account
               SET available = available - #{amount}, frozen = frozen + #{amount}, version = version + 1
             WHERE booster_id = #{boosterId} AND available >= #{amount}
            """)
    int freezeForWithdraw(@Param("boosterId") Long boosterId, @Param("amount") long amount);

    /**
     * 提现成功：frozen 清零该笔（钱离开平台）。
     *
     * @return 影响行数，0 表示 frozen 不足（数据异常，需告警对账）
     */
    @Update("""
            UPDATE booster_earning_account
               SET frozen = frozen - #{amount}, version = version + 1
             WHERE booster_id = #{boosterId} AND frozen >= #{amount}
            """)
    int deductFrozen(@Param("boosterId") Long boosterId, @Param("amount") long amount);

    /**
     * 提现失败/拒绝：frozen 退回 available（01 §7.3-A）。
     *
     * @return 影响行数
     */
    @Update("""
            UPDATE booster_earning_account
               SET frozen = frozen - #{amount}, available = available + #{amount}, version = version + 1
             WHERE booster_id = #{boosterId} AND frozen >= #{amount}
            """)
    int unfreezeToAvailable(@Param("boosterId") Long boosterId, @Param("amount") long amount);

    /**
     * 封号冻结：全部 available 转 frozen（01 §7.3-B、README §6.2）。
     *
     * <p>封号优先级高于提现，冻结后进行中提现自动拒绝。</p>
     *
     * @return 影响行数
     */
    @Update("""
            UPDATE booster_earning_account
               SET frozen = frozen + available, available = 0, version = version + 1
             WHERE booster_id = #{boosterId}
            """)
    int freezeAllForPunish(@Param("boosterId") Long boosterId);

    /**
     * 解封：封号冻结部分退回 available（01 §7.3-B）。
     *
     * <p><b>注意</b>：被封时自动拒绝的提现不自动恢复，打手需重新申请。
     * 因此解冻金额应由调用方按封号时的 PUNISH_FREEZE 流水金额传入，
     * 而不是无脑把 frozen 清零——frozen 中可能还有别的提现在途。</p>
     *
     * @return 影响行数
     */
    @Update("""
            UPDATE booster_earning_account
               SET frozen = frozen - #{amount}, available = available + #{amount}, version = version + 1
             WHERE booster_id = #{boosterId} AND frozen >= #{amount}
            """)
    int unfreezeFromPunish(@Param("boosterId") Long boosterId, @Param("amount") long amount);
}
