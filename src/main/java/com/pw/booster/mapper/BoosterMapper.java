package com.pw.booster.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pw.booster.entity.Booster;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface BoosterMapper extends BaseMapper<Booster> {

    /**
     * 按 user_id 查打手记录。登录时用于预解析 boosterId 写入 JWT claim。
     */
    @Select("SELECT * FROM booster WHERE user_id = #{userId} LIMIT 1")
    Booster selectByUserId(@Param("userId") Long userId);
}
