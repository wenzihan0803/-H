package com.pw.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pw.order.entity.BizOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单 Mapper。
 *
 * <p>资金表操作规范（05 §1.2、01 §14）：<b>先 FOR UPDATE 锁行，再带 status 条件 UPDATE</b>。
 * 条件更新影响行数为 0 即表示并发冲突或状态已变，抛 {@code 40901}（01 §3 状态机第 5 步）。</p>
 */
@Mapper
public interface BizOrderMapper extends BaseMapper<BizOrder> {

    /**
     * 悲观锁读取订单。必须在事务内调用。
     *
     * <p>结算、退款、确认等资金动作前都要先锁单，防止并发重复入账（01 §16）。</p>
     */
    @Select("SELECT * FROM biz_order WHERE id = #{id} FOR UPDATE")
    BizOrder selectForUpdate(@Param("id") Long id);

    /**
     * 按订单号查询。{@code uk_order_no} 唯一。
     */
    @Select("SELECT * FROM biz_order WHERE order_no = #{orderNo}")
    BizOrder selectByOrderNo(@Param("orderNo") String orderNo);

    /**
     * 状态机核心：条件更新状态。
     *
     * <p>{@code WHERE id=? AND status=#{expectStatus}} 是乐观并发控制的关键——
     * 两个请求同时抢同一订单，只有一个能把状态从 expectStatus 改掉。</p>
     *
     * @return 影响行数；0 表示状态已被其他请求改变，调用方须抛 40901
     */
    @Update("""
            UPDATE biz_order
               SET status = #{targetStatus}, updated_at = #{now}
             WHERE id = #{id} AND status = #{expectStatus}
            """)
    int transitStatus(@Param("id") Long id,
                      @Param("expectStatus") String expectStatus,
                      @Param("targetStatus") String targetStatus,
                      @Param("now") LocalDateTime now);

    /**
     * 查询打手当前进行中的订单数，用于「接单时无进行中订单」guard（01 §3）。
     */
    @Select("""
            SELECT COUNT(1) FROM biz_order
             WHERE booster_id = #{boosterId}
               AND status IN ('ACCEPTED','IN_SERVICE','PENDING_CONFIRM','DISPUTING')
            """)
    long countInProgressByBooster(@Param("boosterId") Long boosterId);

    /** 打手历史完成单数（正常完成或部分退款终态）。 */
    @Select("""
            SELECT COUNT(1) FROM biz_order
             WHERE booster_id = #{boosterId}
               AND status IN ('COMPLETED','PARTIAL_REFUNDED')
            """)
    long countCompletedByBooster(@Param("boosterId") Long boosterId);

    /**
     * 扫描到期待自动确认的订单（01 §11 定时任务：每 5 分钟）。
     *
     * <p>只取 {@code PENDING_CONFIRM} 且已过 {@code confirm_deadline}；
     * {@code DISPUTING} 天然不在结果中，满足「争议中跳过自动确认」。</p>
     */
    @Select("""
            SELECT * FROM biz_order
             WHERE status = 'PENDING_CONFIRM'
               AND confirm_deadline IS NOT NULL
               AND confirm_deadline <= #{now}
             ORDER BY confirm_deadline ASC
             LIMIT #{limit}
            """)
    List<BizOrder> selectAutoConfirmDue(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /**
     * 打手接单：PENDING_ACCEPT → ACCEPTED，同时写入 boosterId。
     *
     * <p>大厅单在此刻才确定打手归属；{@code WHERE status='PENDING_ACCEPT'} 是抢单并发的
     * 唯一防线——两个打手同时抢，只有一个影响行数为 1（01 §16「抢单并发」）。</p>
     *
     * <p>指定单的 {@code booster_id} 下单时已写入，这里重复赋同一值，无副作用。</p>
     *
     * @return 影响行数；0 表示订单已被抢走或状态已变
     */
    @Update("""
            UPDATE biz_order
               SET status = #{targetStatus}, booster_id = #{boosterId}, updated_at = #{now}
             WHERE id = #{id} AND status = #{expectStatus}
            """)
    int acceptOrder(@Param("id") Long id,
                    @Param("expectStatus") String expectStatus,
                    @Param("targetStatus") String targetStatus,
                    @Param("boosterId") Long boosterId,
                    @Param("now") LocalDateTime now);

    /**
     * 开始服务：ACCEPTED → IN_SERVICE，记录使用的打手游戏账号。
     *
     * @return 影响行数；0 表示状态已变
     */
    @Update("""
            UPDATE biz_order
               SET status = #{targetStatus}, booster_game_account_id = #{gameAccountId}, updated_at = #{now}
             WHERE id = #{id} AND status = #{expectStatus}
            """)
    int startService(@Param("id") Long id,
                     @Param("expectStatus") String expectStatus,
                     @Param("targetStatus") String targetStatus,
                     @Param("gameAccountId") Long gameAccountId,
                     @Param("now") LocalDateTime now);

    /**
     * 申请完单：IN_SERVICE → PENDING_CONFIRM，写入完单时间与自动确认截止时间。
     *
     * <p>{@code confirm_deadline = service_completed_at + 7d}（01 §7.1），
     * 定时任务据此扫描自动确认。</p>
     *
     * @return 影响行数；0 表示状态已变
     */
    @Update("""
            UPDATE biz_order
               SET status = #{targetStatus}, service_completed_at = #{completedAt},
                   confirm_deadline = #{confirmDeadline}, updated_at = #{completedAt}
             WHERE id = #{id} AND status = #{expectStatus}
            """)
    int requestComplete(@Param("id") Long id,
                        @Param("expectStatus") String expectStatus,
                        @Param("targetStatus") String targetStatus,
                        @Param("completedAt") LocalDateTime completedAt,
                        @Param("confirmDeadline") LocalDateTime confirmDeadline);

    /**
     * 确认收货并结算：PENDING_CONFIRM → COMPLETED，写入确认时间。
     *
     * <p>结算金额（booster_amount / platform_amount）由调用方在结算完成后用
     * {@code updateById} 回写，避免这条语句承担过多职责。</p>
     *
     * @return 影响行数；0 表示状态已变（并发确认或已进入争议）
     */
    @Update("""
            UPDATE biz_order
               SET status = #{targetStatus}, confirmed_at = #{now}, updated_at = #{now}
             WHERE id = #{id} AND status = #{expectStatus}
            """)
    int confirmAndSettle(@Param("id") Long id,
                         @Param("expectStatus") String expectStatus,
                         @Param("targetStatus") String targetStatus,
                         @Param("now") LocalDateTime now);

    /**
     * 退款完结订单：置终态并记录退款时间与原因。
     *
     * <p>用于两条链路（01 §6）：
     * <ul>
     *   <li>客户自助全额退：PENDING_ACCEPT/ACCEPTED → REFUNDED；</li>
     *   <li>争议退款审批通过：DISPUTING → REFUNDED（100%）或 PARTIAL_REFUNDED（1–99%）。</li>
     * </ul>
     * {@code WHERE status=#{expectStatus}} 保证并发下只有一个请求能完结订单。</p>
     *
     * @param refundedAmount 本次退款金额（分），仅用于日志与对账，不改托管金
     * @return 影响行数；0 表示状态已变
     */
    @Update("""
            UPDATE biz_order
               SET status = #{targetStatus}, refunded_at = #{now}, refund_reason = #{refundReason},
                   updated_at = #{now}
             WHERE id = #{id} AND status = #{expectStatus}
            """)
    int refundOrder(@Param("id") Long id,
                    @Param("expectStatus") String expectStatus,
                    @Param("targetStatus") String targetStatus,
                    @Param("refundedAmount") long refundedAmount,
                    @Param("refundReason") String refundReason,
                    @Param("now") LocalDateTime now);

    /**
     * 打手拒单释放：ACCEPTED → PENDING_ACCEPT，清空 booster_id 并回到「大厅单」。
     *
     * <p>订单退回大厅重新可抢（04 §3.6 {@code POST /booster/orders/{id}/reject}）。
     * 不动资金——托管金仍是 HELD，客户金砖不退回。</p>
     *
     * <p><b>必须同时把 mode 置回 HALL</b>：指定单（DESIGNATED）被拒后若只清 booster_id，
     * 会留下「DESIGNATED + boosterId=null」的组合——接单校验要求指定单 boosterId 匹配，
     * 结果任何人都接不了，订单永久卡在 PENDING_ACCEPT。</p>
     *
     * @return 影响行数；0 表示状态已变
     */
    @Update("""
            UPDATE biz_order
               SET status = #{targetStatus}, booster_id = NULL, mode = 'HALL', updated_at = #{now}
             WHERE id = #{id} AND status = #{expectStatus} AND booster_id = #{boosterId}
            """)
    int releaseByReject(@Param("id") Long id,
                        @Param("expectStatus") String expectStatus,
                        @Param("targetStatus") String targetStatus,
                        @Param("boosterId") Long boosterId,
                        @Param("now") LocalDateTime now);
}
