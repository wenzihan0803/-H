package com.pw.refund.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pw.refund.entity.Refund;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 退款单 Mapper。
 */
@Mapper
public interface RefundMapper extends BaseMapper<Refund> {

    /**
     * 悲观锁读取退款单，用于审批时的并发保护（01 §16「退款并发」）。
     *
     * <p>必须在事务内调用。</p>
     */
    @Select("SELECT * FROM refund WHERE id = #{id} FOR UPDATE")
    Refund selectForUpdate(@Param("id") Long id);

    /**
     * 查询订单当前是否存在待审批的退款单。
     *
     * <p>数据库层面已由 {@code uk_order_active} 保证同一订单最多一条
     * PENDING_APPROVAL/SUCCESS，此查询用于业务侧给出更友好的错误提示。</p>
     */
    @Select("SELECT COUNT(1) FROM refund WHERE order_id = #{orderId} AND status = 'PENDING_APPROVAL'")
    long countPendingByOrderId(@Param("orderId") Long orderId);

    /**
     * 主管审批通过：PENDING_APPROVAL → SUCCESS，写入审批人与最终佣金/打手收益。
     *
     * <p>{@code WHERE status='PENDING_APPROVAL'} 是并发审批的防线：
     * 两个主管同时点通过，只有一个影响行数为 1（01 §16）。</p>
     *
     * <p>{@code active_flag} 是数据库生成列，状态变为 SUCCESS 后仍为 1，
     * 因此「同一订单最多一条活跃退款单」的约束在审批后依然生效。</p>
     *
     * @return 影响行数；0 表示已被其他审批人处理
     */
    @Update("""
            UPDATE refund
               SET status = 'SUCCESS', approver_id = #{approverId}, approved_at = #{now},
                   completed_at = #{now}, commission_amount = #{commissionAmount},
                   booster_amount = #{boosterAmount}, settlement_id = #{settlementId}
             WHERE id = #{id} AND status = #{expectStatus}
            """)
    int approve(@Param("id") Long id,
                @Param("expectStatus") String expectStatus,
                @Param("approverId") Long approverId,
                @Param("commissionAmount") long commissionAmount,
                @Param("boosterAmount") long boosterAmount,
                @Param("settlementId") Long settlementId,
                @Param("now") java.time.LocalDateTime now);

    /**
     * 主管驳回：PENDING_APPROVAL → REJECTED，写入驳回理由。
     *
     * <p>状态变为 REJECTED 后 {@code active_flag} 生成列自动变为 NULL，
     * 释放 {@code uk_order_active} 占位，该订单可再次发起退款申请。</p>
     *
     * @return 影响行数；0 表示已被其他审批人处理
     */
    @Update("""
            UPDATE refund
               SET status = 'REJECTED', approver_id = #{approverId},
                   completed_at = #{now}, reason = CONCAT(reason, ' | 驳回理由：', #{rejectReason})
             WHERE id = #{id} AND status = #{expectStatus}
            """)
    int reject(@Param("id") Long id,
               @Param("expectStatus") String expectStatus,
               @Param("approverId") Long approverId,
               @Param("rejectReason") String rejectReason,
               @Param("now") java.time.LocalDateTime now);
}
