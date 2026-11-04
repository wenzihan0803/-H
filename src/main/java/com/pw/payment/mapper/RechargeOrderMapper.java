package com.pw.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pw.payment.entity.RechargeOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 充值单 Mapper。
 *
 * <p>状态推进一律用<b>条件更新</b>（{@code WHERE status = 原状态}），
 * 保证并发回调下只有一次生效（01 §4 幂等）。</p>
 */
@Mapper
public interface RechargeOrderMapper extends BaseMapper<RechargeOrder> {

    /**
     * PENDING → SUCCESS 并记录微信支付单号与到账时间。
     *
     * <p>{@code transaction_id IS NULL} 条件防止两笔不同回调抢同一充值单。</p>
     *
     * @return 影响行数，1=本次推进成功，0=已被其他线程处理（幂等命中）
     */
    @Update("""
            UPDATE recharge_order
               SET status = 'SUCCESS', transaction_id = #{transactionId}, paid_at = #{paidAt},
                   credited_at = #{creditedAt}, raw_json = #{rawJson}
             WHERE id = #{id} AND status = 'PENDING' AND transaction_id IS NULL
            """)
    int markSuccess(@Param("id") Long id,
                    @Param("transactionId") String transactionId,
                    @Param("paidAt") java.time.LocalDateTime paidAt,
                    @Param("creditedAt") java.time.LocalDateTime creditedAt,
                    @Param("rawJson") String rawJson);

    /**
     * PENDING → FAILED。
     *
     * @return 影响行数
     */
    @Update("""
            UPDATE recharge_order
               SET status = 'FAILED', raw_json = #{rawJson}
             WHERE id = #{id} AND status = 'PENDING'
            """)
    int markFailed(@Param("id") Long id, @Param("rawJson") String rawJson);

    /**
     * 回写微信预支付 ID（JSAPI 下单后）。
     *
     * @return 影响行数
     */
    @Update("""
            UPDATE recharge_order
               SET prepay_id = #{prepayId}
             WHERE id = #{id} AND status = 'PENDING'
            """)
    int updatePrepayId(@Param("id") Long id, @Param("prepayId") String prepayId);

    /**
     * PENDING → CLOSED，用于超时关单（01 §4 兜底任务）。
     *
     * @return 影响行数
     */
    @Update("""
            UPDATE recharge_order
               SET status = 'CLOSED'
             WHERE id = #{id} AND status = 'PENDING'
            """)
    int markClosed(@Param("id") Long id);
}
