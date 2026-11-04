package com.pw.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pw.order.entity.OrderEvidence;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OrderEvidenceMapper extends BaseMapper<OrderEvidence> {

    /**
     * 按订单与类型查凭证，用于「开局照/结束照」guard 校验（01 §3）。
     */
    @Select("SELECT * FROM order_evidence WHERE order_id = #{orderId} AND type = #{type} LIMIT 1")
    OrderEvidence selectByOrderAndType(@Param("orderId") Long orderId, @Param("type") String type);
}
