package com.pw.booster.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pw.booster.entity.BoosterApplyProfile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface BoosterApplyProfileMapper extends BaseMapper<BoosterApplyProfile> {

    @Select("SELECT * FROM booster_apply_profile WHERE apply_id = #{applyId} LIMIT 1")
    BoosterApplyProfile selectByApplyId(@Param("applyId") Long applyId);
}
