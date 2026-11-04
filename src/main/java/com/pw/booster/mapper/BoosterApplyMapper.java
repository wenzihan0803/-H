package com.pw.booster.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pw.booster.entity.BoosterApply;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface BoosterApplyMapper extends BaseMapper<BoosterApply> {

    @Select("SELECT * FROM booster_apply WHERE user_id = #{userId} LIMIT 1")
    BoosterApply selectByUserId(@Param("userId") Long userId);

    @Select("SELECT * FROM booster_apply WHERE id = #{id} FOR UPDATE")
    BoosterApply selectForUpdate(@Param("id") Long id);
}
