package com.pw.settlement.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pw.settlement.entity.Settlement;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 结算单 Mapper。
 */
@Mapper
public interface SettlementMapper extends BaseMapper<Settlement> {

    /**
     * 按订单查结算单。{@code uk_order} 唯一，最多一条。
     */
    @Select("SELECT * FROM settlement WHERE order_id = #{orderId}")
    Settlement selectByOrderId(@Param("orderId") Long orderId);
}
