package com.pw.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pw.common.idempotent.IdempotencyRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 幂等记录 Mapper。
 *
 * <p>放在 {@code com.pw.common.mapper} 下以匹配启动类
 * {@code @MapperScan("com.pw.**.mapper")} 的扫描规则；
 * 实体 {@link IdempotencyRecord} 仍在 {@code com.pw.common.idempotent} 包，
 * 与切面、注解放在一起更符合内聚原则。</p>
 */
@Mapper
public interface IdempotencyRecordMapper extends BaseMapper<IdempotencyRecord> {
}
