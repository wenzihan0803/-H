package com.pw.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pw.order.entity.OrderTimer;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OrderTimerMapper extends BaseMapper<OrderTimer> {

    /**
     * 统计订单是否已有计时器。{@code uk_order} 唯一，结果只会是 0 或 1。
     *
     * <p>用于「开始服务」的幂等重试：已建过就不再插入，避免撞唯一索引。</p>
     */
    @Select("SELECT COUNT(1) FROM order_timer WHERE order_id = #{orderId}")
    Long countByOrderId(@Param("orderId") Long orderId);
}
