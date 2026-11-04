package com.pw.withdraw.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pw.withdraw.entity.Withdraw;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 提现单 Mapper。
 */
@Mapper
public interface WithdrawMapper extends BaseMapper<Withdraw> {

    /**
     * 查询打手当日已提交的提现笔数，用于「每日 1 次」限制（01 §8）。
     *
     * <p>2026-10-29 调整：被拒绝（REJECTED）与打款失败（FAILED）的单不计入——
     * 资金已退回 available，打手修正问题后当天应当可以重新申请；
     * 在途（APPLIED/APPROVED/PAYING）与已打款（PAID）计入。</p>
     */
    @Select("""
            SELECT COUNT(1) FROM withdraw
            WHERE booster_id = #{boosterId}
              AND created_at >= #{dayStart}
              AND status NOT IN ('REJECTED', 'FAILED')
            """)
    long countToday(@Param("boosterId") Long boosterId, @Param("dayStart") java.time.LocalDateTime dayStart);

    /**
     * 查询打手所有进行中的提现单（APPLIED/APPROVED/PAYING）。
     *
     * <p>封号时需自动拒绝这些提现（01 §7.3-B、04 §3.7 封号冻结说明）。</p>
     */
    @Select("SELECT * FROM withdraw WHERE booster_id = #{boosterId} AND status IN ('APPLIED','APPROVED','PAYING')")
    List<Withdraw> selectInProgressByBooster(@Param("boosterId") Long boosterId);

    /**
     * 悲观锁读取提现单。必须在事务内调用。
     *
     * <p>审批、打款、拒绝前都要先锁单，防止管理员重复点击导致双重打款。</p>
     */
    @Select("SELECT * FROM withdraw WHERE id = #{id} FOR UPDATE")
    Withdraw selectForUpdate(@Param("id") Long id);

    /**
     * 状态流转（带条件更新）。
     *
     * <p>{@code WHERE status=#{expectStatus}} 是并发防线：两个管理员同时处理同一提现单，
     * 只有一个影响行数为 1。fail_reason 仅拒绝/失败场景传入。</p>
     *
     * @return 影响行数；0 表示状态已被其他请求改变
     */
    @Update("""
            <script>
            UPDATE withdraw
               SET status = #{targetStatus}, updated_at = #{now}
                   <if test="approverId != null">, approver_id = #{approverId}, approved_at = #{now}</if>
                   <if test="failReason != null">, fail_reason = #{failReason}</if>
                   <if test='"PAID".equals(targetStatus)'>, paid_at = #{now}</if>
             WHERE id = #{id} AND status = #{expectStatus}
            </script>
            """)
    int transit(@Param("id") Long id,
                @Param("expectStatus") String expectStatus,
                @Param("targetStatus") String targetStatus,
                @Param("approverId") Long approverId,
                @Param("now") java.time.LocalDateTime now,
                @Param("failReason") String failReason);
}
