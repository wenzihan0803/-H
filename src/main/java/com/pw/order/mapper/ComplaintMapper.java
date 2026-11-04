package com.pw.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pw.order.entity.Complaint;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ComplaintMapper extends BaseMapper<Complaint> {

    @Select("SELECT * FROM complaint WHERE order_id = #{orderId} AND status IN ('OPEN','PROCESSING') LIMIT 1")
    Complaint selectActiveByOrderId(@Param("orderId") Long orderId);

    @Select("SELECT * FROM complaint WHERE id = #{id} FOR UPDATE")
    Complaint selectForUpdate(@Param("id") Long id);
}
