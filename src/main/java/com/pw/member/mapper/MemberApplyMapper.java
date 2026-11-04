package com.pw.member.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pw.member.entity.MemberApply;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MemberApplyMapper extends BaseMapper<MemberApply> {

    @Select("SELECT * FROM member_apply WHERE id = #{id} FOR UPDATE")
    MemberApply selectForUpdate(@Param("id") Long id);
}
