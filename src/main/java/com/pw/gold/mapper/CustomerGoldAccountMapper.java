package com.pw.gold.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pw.gold.entity.CustomerGoldAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 客户金砖账户 Mapper。
 *
 * <p>资金表操作规范（05 §1.2 硬约束 3、01 §14）：
 * <b>先 {@code FOR UPDATE} 锁行，再用带条件的 UPDATE</b>，禁止「读出来算好再写回」的无锁更新。</p>
 */
@Mapper
public interface CustomerGoldAccountMapper extends BaseMapper<CustomerGoldAccount> {

    /**
     * 行锁读取账户。所有余额变更的第一步。
     *
     * <p>必须在事务内调用，否则锁会立即释放、失去意义。</p>
     */
    @Select("SELECT * FROM customer_gold_account WHERE user_id = #{userId} FOR UPDATE")
    CustomerGoldAccount selectForUpdate(@Param("userId") Long userId);

    /**
     * 条件扣减余额：{@code balance >= amount} 作为 WHERE 条件。
     *
     * <p>并发下只有一个事务能扣成功（影响行数 1），其余为 0；
     * 调用方必须判断返回值，0 表示余额不足或并发冲突，不得静默忽略。</p>
     *
     * @return 影响行数，1 成功 / 0 失败
     */
    @Update("""
            UPDATE customer_gold_account
               SET balance = balance - #{amount}, version = version + 1
             WHERE user_id = #{userId} AND balance >= #{amount}
            """)
    int deductIfEnough(@Param("userId") Long userId, @Param("amount") long amount);

    /**
     * 增加余额（充值到账 / 退款回金砖）。
     *
     * @return 影响行数
     */
    @Update("""
            UPDATE customer_gold_account
               SET balance = balance + #{amount}, total_recharged = total_recharged + #{rechargedDelta},
                   version = version + 1
             WHERE user_id = #{userId}
            """)
    int increase(@Param("userId") Long userId, @Param("amount") long amount,
                 @Param("rechargedDelta") long rechargedDelta);

    /**
     * 人工调账（管理员线下特批）。rechargedDelta 固定传 0，调账不计入累计充值。
     *
     * @return 影响行数，0 表示扣减后余额为负被拒绝
     */
    @Update("""
            UPDATE customer_gold_account
               SET balance = balance + #{delta}, version = version + 1
             WHERE user_id = #{userId} AND balance + #{delta} >= 0
            """)
    int adjust(@Param("userId") Long userId, @Param("delta") long delta);
}
