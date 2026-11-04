package com.pw.order.service;

import com.pw.common.config.BusinessProperties;
import com.pw.common.exception.BizException;
import com.pw.common.exception.ErrorCode;
import com.pw.common.util.MoneyUtil;
import com.pw.member.entity.MemberAccount;
import com.pw.member.mapper.MemberAccountMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 订单金额计算器。下单时算出「原价 / 会员折扣 / 实付」，并固化进订单。
 *
 * <p>口径来源：README §4.3、01 §5、§9；2026-10-29 用户拍板调整（见
 * 源代码/后端/后端开发总览.md §资金口径）：</p>
 * <ol>
 *   <li>原价 = SKU 单价 × 数量；</li>
 *   <li>会员 9 折：实付 = 原价 × 90%，即 {@code memberDiscount = 原价 × 10%}；</li>
 *   <li><b>邀请功能已删除</b>（2026-10-29 用户决策）：下单不再支持邀请抵扣，
 *       {@code biz_order.invite_discount} 列保留但恒为 0（V7 迁移已删除 invite 相关表）；</li>
 *   <li>实付 = 原价 − 会员折扣，且<b>实付即托管金额</b>（01 §5）；</li>
 *   <li>平台费只在「确认收货（COMPLETED）」时收取；未确认就结束的订单（退款）
 *       不扣平台费，退款按下单实付金额的比例或全款退回（见 SettlementService）。</li>
 * </ol>
 *
 * <p><b>关键</b>：折扣只影响客户实付与平台收入，<b>不影响打手收益</b>——
 * 打手收益永远按原价 85% 算（README §4.4「打手仍 85，平台 5」），
 * 这部分在 {@link com.pw.settlement.service.SettlementService} 里实现。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderAmountCalculator {

    private final BusinessProperties business;
    private final MemberAccountMapper memberAccountMapper;

    /**
     * 计算订单金额。
     *
     * @param customerId 客户 ID，用于查会员等级
     * @param skuPrice   SKU 单价（分）
     * @param quantity   数量
     * @return 金额明细
     */
    public Amounts calculate(Long customerId, long skuPrice, int quantity) {
        MoneyUtil.requirePositive(skuPrice, "SKU 单价");
        if (quantity <= 0) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "购买数量必须大于 0");
        }

        long originalAmount = skuPrice * quantity;

        // ---- 会员折扣：9 折，折扣额 = 原价 × 10% ----
        long memberDiscount = 0L;
        boolean isMember = false;
        MemberAccount member = memberAccountMapper.selectById(customerId);
        if (member != null && MemberAccount.LEVEL_MEMBER.equals(member.getLevel())) {
            isMember = true;
            memberDiscount = originalAmount - MoneyUtil.percent(originalAmount, business.getMemberDiscountRate());
        }

        long payAmount = originalAmount - memberDiscount;
        if (payAmount <= 0) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "订单金额计算异常，请联系客服");
        }

        return new Amounts(originalAmount, memberDiscount, 0L, payAmount, isMember);
    }

    /**
     * 订单金额明细。
     *
     * @param originalAmount 原价/下单总金额（分）
     * @param memberDiscount 会员折扣（分）
     * @param inviteDiscount 邀请抵扣（分）——功能已删除，恒为 0，保留字段兼容旧数据
     * @param payAmount      实付（分）= 托管金额
     * @param member         下单时是否会员（留痕用，便于事后核对折扣是否合理）
     */
    public record Amounts(long originalAmount, long memberDiscount, long inviteDiscount,
                          long payAmount, boolean member) {
    }
}
