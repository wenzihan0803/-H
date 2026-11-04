package com.pw.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pw.order.entity.HandoverLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface HandoverLogMapper extends BaseMapper<HandoverLog> {

    /**
     * 取订单最近一条交接日志（按时间倒序）。
     *
     * <p>用于详情装配：上号指引 {@code guide} 与老板游戏 ID 都存在日志里，
     * {@code handover_task} 只有状态。最近一条 SENT 即当前生效的交接内容。</p>
     */
    @Select("""
            SELECT * FROM handover_log
             WHERE order_id = #{orderId}
             ORDER BY created_at DESC, id DESC
             LIMIT 1
            """)
    HandoverLog selectLatestByOrderId(@Param("orderId") Long orderId);

    /**
     * 订单全部交接日志（管理端流水页 OrderLedger.handoverLogs，04 §2.4）。
     */
    @Select("SELECT * FROM handover_log WHERE order_id = #{orderId} ORDER BY created_at ASC, id ASC")
    List<HandoverLog> listByOrderId(@Param("orderId") Long orderId);
}
