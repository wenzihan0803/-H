package com.pw.gold.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pw.gold.entity.CustomerGoldTxn;
import org.apache.ibatis.annotations.Mapper;

/**
 * 客户金砖流水 Mapper。
 *
 * <p>本表<b>只提供 insert 与 select</b>，不提供 update/delete——
 * 流水是不可变审计记录（05 §12、01 §17）。</p>
 */
@Mapper
public interface CustomerGoldTxnMapper extends BaseMapper<CustomerGoldTxn> {
}
