package com.pw.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pw.order.entity.OrderFundHold;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 订单托管金 Mapper。
 *
 * <p>资金表操作规范（05 §1.2、01 §14、§16）：<b>先 FOR UPDATE 锁行，再带条件 UPDATE</b>。
 * 这是「退款并发不重复加金砖」的核心防线：托管金行锁 + 状态条件更新，
 * 并发下只有一个请求能把 {@code HELD} 改掉。</p>
 *
 * <p>不变式：{@code amount = remaining_amount + refunded_amount}。</p>
 */
@Mapper
public interface OrderFundHoldMapper extends BaseMapper<OrderFundHold> {

    /**
     * 悲观锁读取托管金。必须在事务内调用。
     *
     * <p>退款（01 §6.1/§6.2）与结算（§7.2）前都要先锁这一行。</p>
     */
    @Select("SELECT * FROM order_fund_hold WHERE order_id = #{orderId} FOR UPDATE")
    OrderFundHold selectForUpdateByOrderId(@Param("orderId") Long orderId);

    /**
     * 按订单查托管金（不加锁，用于详情展示）。{@code uk_order} 唯一。
     */
    @Select("SELECT * FROM order_fund_hold WHERE order_id = #{orderId}")
    OrderFundHold selectByOrderId(@Param("orderId") Long orderId);

    /**
     * 全额退款：剩余清零、已退置为总额、状态置 REFUNDED。
     *
     * <p>{@code WHERE status='HELD' AND remaining_amount=#{expectRemaining}} 双重条件，
     * 并发下只有一个成功；影响行数 0 表示已被其他退款处理，调用方须抛 40901。</p>
     *
     * @return 影响行数
     */
    @Update("""
            UPDATE order_fund_hold
               SET remaining_amount = 0,
                   refunded_amount = refunded_amount + #{refundAmount},
                   status = 'REFUNDED',
                   refunded_at = #{now}
             WHERE order_id = #{orderId}
               AND status = 'HELD'
               AND remaining_amount = #{expectRemaining}
            """)
    int refundAll(@Param("orderId") Long orderId,
                  @Param("refundAmount") long refundAmount,
                  @Param("expectRemaining") long expectRemaining,
                  @Param("now") LocalDateTime now);

    /**
     * 部分退款：扣减剩余、累加已退、状态置 PARTIAL_REFUNDED。
     *
     * <p>部分退款后订单终态（01 §6「退款后订单终态，不能继续履约」），
     * 因此剩余金额一次性清零，留存部分走结算给打手。</p>
     *
     * @return 影响行数
     */
    @Update("""
            UPDATE order_fund_hold
               SET remaining_amount = 0,
                   refunded_amount = refunded_amount + #{refundAmount},
                   status = 'PARTIAL_REFUNDED',
                   refunded_at = #{now}
             WHERE order_id = #{orderId}
               AND status = 'HELD'
               AND remaining_amount = #{expectRemaining}
            """)
    int refundPartial(@Param("orderId") Long orderId,
                      @Param("refundAmount") long refundAmount,
                      @Param("expectRemaining") long expectRemaining,
                      @Param("now") LocalDateTime now);

    /**
     * 正常结算：剩余清零（钱全部结给打手+平台），状态保持 HELD 不变。
     *
     * <p>结算不改变 {@code refunded_amount}，只把 {@code remaining_amount} 归零，
     * 表示这笔托管金已完成分配（01 §7.2）。</p>
     *
     * @return 影响行数
     */
    @Update("""
            UPDATE order_fund_hold
               SET remaining_amount = 0
             WHERE order_id = #{orderId}
               AND status = 'HELD'
               AND remaining_amount = #{expectRemaining}
            """)
    int settleRemaining(@Param("orderId") Long orderId,
                        @Param("expectRemaining") long expectRemaining);
}
