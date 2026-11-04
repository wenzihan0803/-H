package com.pw.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pw.user.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户 Mapper。
 *
 * <p>约束（01 §1）：跨模块只调 Service，不直接调 Mapper。
 * 本 Mapper 仅允许 {@code com.pw.user} 包内使用。</p>
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
