package com.pw.order.state;

import com.pw.common.enums.OrderAction;
import com.pw.common.enums.OrderStatus;
import com.pw.common.exception.BizException;
import com.pw.common.exception.ErrorCode;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 订单状态转移表。
 *
 * <p>口径来源（唯一）：技术规范/README.md §1、01 §3 状态机图、04 §1.5。</p>
 *
 * <pre>
 * PENDING_ACCEPT → ACCEPTED → IN_SERVICE → PENDING_CONFIRM → COMPLETED
 * PENDING_ACCEPT / ACCEPTED --客户自助全额退--> REFUNDED
 * IN_SERVICE / PENDING_CONFIRM --客服争议退款--> DISPUTING
 * DISPUTING --主管驳回--> 原状态
 * DISPUTING --100%--> REFUNDED ／ --1%-99%--> PARTIAL_REFUNDED
 * </pre>
 *
 * <p>本类是<b>纯声明</b>，不含任何数据库操作。真正的迁移执行在
 * {@link OrderStateMachine}，它把这里查到的目标状态翻译成带条件的 UPDATE。</p>
 */
public final class OrderTransitionTable {

    /** (from, action) → to */
    private static final Map<OrderStatus, Map<OrderAction, OrderStatus>> TABLE;

    static {
        Map<OrderStatus, Map<OrderAction, OrderStatus>> table = new EnumMap<>(OrderStatus.class);

        // ---- PENDING_ACCEPT：可接单、可改派、可客户自助全额退 ----
        table.put(OrderStatus.PENDING_ACCEPT, transitions(
                OrderAction.ACCEPT, OrderStatus.ACCEPTED,
                OrderAction.REASSIGN, OrderStatus.PENDING_ACCEPT,
                OrderAction.CUSTOMER_REFUND, OrderStatus.REFUNDED,
                OrderAction.URGE, OrderStatus.PENDING_ACCEPT));

        // ---- ACCEPTED：可开始服务、仍可客户自助全额退 ----
        table.put(OrderStatus.ACCEPTED, transitions(
                OrderAction.START_SERVICE, OrderStatus.IN_SERVICE,
                OrderAction.REASSIGN, OrderStatus.ACCEPTED,
                OrderAction.CUSTOMER_REFUND, OrderStatus.REFUNDED,
                OrderAction.URGE, OrderStatus.ACCEPTED));

        // ---- IN_SERVICE：可申请完单、可被客服发起争议退款 ----
        table.put(OrderStatus.IN_SERVICE, transitions(
                OrderAction.REQUEST_COMPLETE, OrderStatus.PENDING_CONFIRM,
                OrderAction.DISPUTE_SUBMIT, OrderStatus.DISPUTING,
                OrderAction.REASSIGN, OrderStatus.IN_SERVICE,
                OrderAction.URGE, OrderStatus.IN_SERVICE));

        // ---- PENDING_CONFIRM：可客户确认、可自动确认、可被客服发起争议退款 ----
        table.put(OrderStatus.PENDING_CONFIRM, transitions(
                OrderAction.CONFIRM, OrderStatus.COMPLETED,
                OrderAction.AUTO_CONFIRM, OrderStatus.COMPLETED,
                OrderAction.DISPUTE_SUBMIT, OrderStatus.DISPUTING,
                OrderAction.URGE, OrderStatus.PENDING_CONFIRM));

        // ---- DISPUTING：只能由主管审批决定去向（目标状态与退款比例有关，见下） ----
        // 驳回的目标状态不是固定值，而是 refund.previousOrderStatus，
        // 因此这里不登记 DISPUTE_REJECT，由 OrderStateMachine 特殊处理。
        table.put(OrderStatus.DISPUTING, transitions(
                OrderAction.DISPUTE_APPROVE, null));

        // ---- 终态：不允许任何迁移 ----
        table.put(OrderStatus.COMPLETED, Collections.emptyMap());
        table.put(OrderStatus.REFUNDED, Collections.emptyMap());
        table.put(OrderStatus.PARTIAL_REFUNDED, Collections.emptyMap());

        TABLE = Collections.unmodifiableMap(table);
    }

    private OrderTransitionTable() {
    }

    /**
     * 构造单个状态的转移表。参数为 (action, toStatus) 交替排列。
     *
     * <p>{@code toStatus} 允许为 null，表示「目标状态需在运行时决定」
     * （如争议审批，取决于退款比例）。</p>
     */
    private static Map<OrderAction, OrderStatus> transitions(Object... actionToPairs) {
        if (actionToPairs.length % 2 != 0) {
            throw new IllegalArgumentException("transitions() 参数必须成对");
        }
        Map<OrderAction, OrderStatus> map = new EnumMap<>(OrderAction.class);
        for (int i = 0; i < actionToPairs.length; i += 2) {
            map.put((OrderAction) actionToPairs[i], (OrderStatus) actionToPairs[i + 1]);
        }
        return Collections.unmodifiableMap(map);
    }

    /**
     * 查询迁移目标状态。
     *
     * @return 目标状态；null 表示该动作合法但目标状态需运行时决定
     * @throws BizException 状态机不允许该迁移时抛 {@code 40901}（01 §3 第 5 步：影响行数 0 → 40901）
     */
    public static OrderStatus target(OrderStatus from, OrderAction action) {
        Map<OrderAction, OrderStatus> allowed = TABLE.get(from);
        if (allowed == null || !allowed.containsKey(action)) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "当前订单状态「" + from.label() + "」不允许执行「" + action.label() + "」");
        }
        return allowed.get(action);
    }

    /**
     * 判断某状态下某动作是否被允许（不抛异常，用于详情页计算 canXxx 标志位）。
     */
    public static boolean can(OrderStatus from, OrderAction action) {
        Map<OrderAction, OrderStatus> allowed = TABLE.get(from);
        return allowed != null && allowed.containsKey(action);
    }

    /**
     * 某状态下所有可执行动作，用于详情页与调试。
     */
    public static Set<OrderAction> allowedActions(OrderStatus from) {
        Map<OrderAction, OrderStatus> allowed = TABLE.get(from);
        return allowed == null ? EnumSet.noneOf(OrderAction.class) : EnumSet.copyOf(allowed.keySet());
    }

    /**
     * 争议审批的目标状态：按退款比例决定（01 §3 状态机图、§6.2）。
     *
     * @param refundRate 1-100
     * @return 100 → REFUNDED；1-99 → PARTIAL_REFUNDED
     */
    public static OrderStatus disputeTarget(int refundRate) {
        if (refundRate < 1 || refundRate > 100) {
            throw new BizException(ErrorCode.REFUND_RATE_INVALID, "退款比例必须在 1-100 之间");
        }
        return refundRate == 100 ? OrderStatus.REFUNDED : OrderStatus.PARTIAL_REFUNDED;
    }
}
