package com.pw.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pw.order.entity.HandoverTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface HandoverTaskMapper extends BaseMapper<HandoverTask> {

    /**
     * 按订单查交接任务。{@code uk_order} 唯一，最多一条。
     */
    @Select("SELECT * FROM handover_task WHERE order_id = #{orderId}")
    HandoverTask selectByOrderId(@Param("orderId") Long orderId);

    /**
     * 扫描已超时且尚未 ACK 的交接任务（01 §13「交接超时」，每分钟）。
     *
     * <p>只取 {@code PENDING}（客服还没发）与 {@code SENT}（发了但打手没确认）：
     * 已 ACK 的无需再处理，{@code TIMEOUT} 已终态化避免重复提醒。</p>
     *
     * <p>按 {@code due_at} 升序，最紧急的先处理。</p>
     */
    @Select("""
            SELECT * FROM handover_task
             WHERE status IN ('PENDING','SENT')
               AND due_at <= #{now}
             ORDER BY due_at ASC
             LIMIT #{limit}
            """)
    List<HandoverTask> selectOverdue(@Param("now") LocalDateTime now, @Param("limit") int limit);
}
