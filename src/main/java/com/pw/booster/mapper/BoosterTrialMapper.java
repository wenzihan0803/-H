package com.pw.booster.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pw.booster.entity.BoosterTrial;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface BoosterTrialMapper extends BaseMapper<BoosterTrial> {

    @Select("SELECT * FROM booster_trial WHERE apply_id = #{applyId} ORDER BY created_at DESC, id DESC")
    List<BoosterTrial> selectByApplyId(@Param("applyId") Long applyId);
}
