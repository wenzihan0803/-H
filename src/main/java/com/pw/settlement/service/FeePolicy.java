package com.pw.settlement.service;

import com.pw.common.config.BusinessProperties;
import com.pw.common.exception.BizException;
import com.pw.common.exception.ErrorCode;
import com.pw.common.util.MoneyUtil;
import com.pw.member.entity.MemberAccount;
import com.pw.member.mapper.MemberAccountMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 抽成口径的唯一实现。所有涉及「平台拿多少、打手拿多少」的地方都走这里，避免各写一套。
 *
 * <p>2026-10-31 产品确认：</p>
 * <ul>
 *   <li>平台费 = <b>订单原价</b> × 15%；会员单 × 10%（平台少抽 5 个点）；</li>
 *   <li>打手应得 = 客户实付 − 平台费；</li>
 *   <li>仲裁/争议退款发生在确认前：平台费 0，剩余全归打手（不走本类）。</li>
 * </ul>
 *
 * <p>例：原价 100、非会员实付 100 → 平台 15、打手 85；
 * 原价 100、会员实付 90 → 平台 10、打手 80。</p>
 */
@Service
@RequiredArgsConstructor
public class FeePolicy {

    private final MemberAccountMapper memberAccountMapper;
    private final BusinessProperties business;

    /** 客户当前是否会员。 */
    public boolean isMember(Long customerId) {
        if (customerId == null) {
            return false;
        }
        MemberAccount account = memberAccountMapper.selectById(customerId);
        return account != null && account.isMember();
    }

    /** 平台费率：会员 10，非会员 15。 */
    public int platformRate(Long customerId) {
        return isMember(customerId)
                ? business.getMemberPlatformCommissionRate()
                : business.getPlatformCommissionRate();
    }

    /** 平台费（分）= 原价 × 费率。 */
    public long platformFee(long originalAmount, int rate) {
        return MoneyUtil.percent(originalAmount, rate);
    }

    /** 打手应得（分）= 实付 − 平台费；为负说明配置或数据异常，必须拦住。 */
    public long boosterAmount(long payAmount, long platformFee) {
        long amount = payAmount - platformFee;
        if (amount < 0) {
            throw BizException.of(ErrorCode.SYSTEM_ERROR,
                    "结算金额异常：实付 " + MoneyUtil.fenToYuan(payAmount)
                            + " 元低于平台费 " + MoneyUtil.fenToYuan(platformFee) + " 元，请检查费率配置");
        }
        return amount;
    }

    /** 打手端展示的预估应得（大厅/下单页）。 */
    public long estimateBoosterAmount(long originalAmount, long payAmount, Long customerId) {
        return boosterAmount(payAmount, platformFee(originalAmount, platformRate(customerId)));
    }
}
