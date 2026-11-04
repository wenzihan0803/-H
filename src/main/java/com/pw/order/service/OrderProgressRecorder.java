package com.pw.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pw.common.enums.OrderAction;
import com.pw.common.enums.OrderStatus;
import com.pw.common.util.IdGenerator;
import com.pw.order.entity.OrderProgress;
import com.pw.order.mapper.OrderProgressMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单进度轨迹记录器。写入 {@code order_progress}（05 §4.6）。
 *
 * <p>只追加、不修改。状态机每次迁移都必须写一条（01 §3 第 6 步），
 * 客户端时间线 {@code GET /orders/{id}/timeline} 与管理端流水页都读这张表。</p>
 *
 * <p>{@code operatorType} 取值：CUSTOMER / BOOSTER / CS / ADMIN / SYSTEM（04 §2.4）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderProgressRecorder {

    private final OrderProgressMapper progressMapper;
    private final IdGenerator idGenerator;

    /** 操作方类型常量。 */
    public static final String OP_CUSTOMER = "CUSTOMER";
    public static final String OP_BOOSTER = "BOOSTER";
    public static final String OP_CS = "CS";
    public static final String OP_ADMIN = "ADMIN";
    public static final String OP_SYSTEM = "SYSTEM";

    /**
     * 记录一次状态迁移。
     *
     * @param orderId      订单 ID
     * @param from         迁移前状态；创建订单时传 null
     * @param to           迁移后状态
     * @param action       触发动作
     * @param operatorType 操作方类型，见本类常量
     * @param operatorId   操作人 ID；系统操作传 null
     * @param remark       备注，可为 null
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void record(Long orderId, OrderStatus from, OrderStatus to, OrderAction action,
                       String operatorType, Long operatorId, String remark) {
        OrderProgress progress = new OrderProgress();
        progress.setId(idGenerator.nextId());
        progress.setOrderId(orderId);
        progress.setFromStatus(from == null ? null : from.name());
        progress.setToStatus(to.name());
        progress.setAction(action.name());
        progress.setOperatorType(operatorType);
        progress.setOperatorId(operatorId);
        progress.setRemark(truncate(remark));
        progress.setCreatedAt(LocalDateTime.now());
        progressMapper.insert(progress);
        log.debug("订单进度 orderId={} {} -> {} action={} operator={}",
                orderId, from, to, action, operatorType);
    }

    /**
     * 记录一次不改状态的动作（如催单 URGE）。
     *
     * <p>{@code from} 与 {@code to} 相同，仅留痕。</p>
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void recordNoTransition(Long orderId, OrderStatus current, OrderAction action,
                                   String operatorType, Long operatorId, String remark) {
        record(orderId, current, current, action, operatorType, operatorId, remark);
    }

    /** 查询订单全部轨迹，按时间升序（客户端时间线用）。 */
    public List<OrderProgress> listByOrderId(Long orderId) {
        return progressMapper.selectList(new LambdaQueryWrapper<OrderProgress>()
                .eq(OrderProgress::getOrderId, orderId)
                .orderByAsc(OrderProgress::getCreatedAt)
                .orderByAsc(OrderProgress::getId));
    }

    /** 备注列上限 255，超长截断以免插入失败。 */
    private static String truncate(String remark) {
        if (remark == null) {
            return null;
        }
        return remark.length() <= 255 ? remark : remark.substring(0, 255);
    }
}
