package com.pw.withdraw.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pw.booster.entity.Booster;
import com.pw.booster.entity.BoosterEarningAccount;
import com.pw.booster.mapper.BoosterEarningAccountMapper;
import com.pw.booster.mapper.BoosterMapper;
import com.pw.booster.service.BoosterEarningService;
import com.pw.common.config.BusinessProperties;
import com.pw.common.enums.WithdrawStatus;
import com.pw.common.exception.BizException;
import com.pw.common.exception.ErrorCode;
import com.pw.common.util.IdGenerator;
import com.pw.common.util.MoneyUtil;
import com.pw.withdraw.entity.Withdraw;
import com.pw.withdraw.mapper.WithdrawMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 提现服务。实现 01 §8 的完整提现流程。
 *
 * <p>口径来源（唯一）：技术规范/01 §7.3-A、§8；README §0.1、§6；04 §2.5、§3.6。</p>
 *
 * <p><b>状态流</b>：</p>
 * <pre>
 * 打手申请 → APPLIED（available 转 frozen）
 *   ├─ 管理员确认 → APPROVED → 商家转账中 PAYING → 成功 PAID（扣 frozen）
 *   ├─ 管理员拒绝 → REJECTED（frozen 退回 available）
 *   └─ 打款失败 → FAILED（frozen 退回 available）
 * </pre>
 *
 * <p><b>限制</b>（01 §8 第 1 步）：可提现 ≥ 1 元、每日 1 次、单笔上限、封禁/停用拒绝。</p>
 *
 * <p><b>封号优先于提现</b>（01 §7.3-B）：打手被封号时，进行中的提现自动拒绝，
 * 理由固定为「打手封号，提现暂缓」，不等管理员手动处理。</p>
 *
 * <p><b>无 T+7</b>：结算收益直接进 available，可立即提现（README §0.1）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawService {

    private final WithdrawMapper withdrawMapper;
    private final BoosterMapper boosterMapper;
    private final BoosterEarningAccountMapper earningAccountMapper;
    private final BoosterEarningService earningService;
    private final BusinessProperties business;
    private final IdGenerator idGenerator;

    /**
     * 打手申请提现：available → frozen，建提现单 APPLIED（01 §8 第 2 步）。
     *
     * <p>校验链：打手 ACTIVE 且未封禁 → 金额在 [1 元, 单笔上限] → 今日未提现过 →
     * available 充足。任一不满足直接拒绝，不动资金。</p>
     *
     * @param boosterId 打手 ID（从登录态取）
     * @param amount    提现金额（分）
     * @return 提现单
     */
    @Transactional(rollbackFor = Exception.class)
    public Withdraw apply(Long boosterId, long amount) {
        Booster booster = boosterMapper.selectById(boosterId);
        if (booster == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "打手信息不存在，请联系客服");
        }
        // 封禁/停用不可提现（01 §8）
        if (!Booster.STATUS_ACTIVE.equals(booster.getStatus())) {
            throw BizException.of(ErrorCode.WITHDRAW_REJECTED_BANNED,
                    "账号状态为 " + booster.getStatus() + "，无法提现，请联系客服");
        }
        if (booster.getBanUntil() != null && booster.getBanUntil().isAfter(LocalDateTime.now())) {
            throw BizException.of(ErrorCode.WITHDRAW_REJECTED_BANNED,
                    "账号封禁中无法提现，解封时间 " + booster.getBanUntil());
        }

        // 金额区间校验
        if (amount < business.getWithdrawMinAmount()) {
            throw BizException.of(ErrorCode.PARAM_ERROR,
                    "最低提现 " + MoneyUtil.fenToYuan(business.getWithdrawMinAmount()) + " 元");
        }
        if (amount > business.getWithdrawMaxAmount()) {
            throw BizException.of(ErrorCode.PARAM_ERROR,
                    "单笔提现上限 " + MoneyUtil.fenToYuan(business.getWithdrawMaxAmount()) + " 元");
        }

        // 每日次数限制（01 §8：每日 1 次）
        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        long todayCount = withdrawMapper.countToday(boosterId, dayStart);
        if (todayCount >= business.getWithdrawDailyLimit()) {
            throw BizException.of(ErrorCode.TOO_MANY_REQUESTS,
                    "每日仅可申请 " + business.getWithdrawDailyLimit() + " 次提现，请明天再试");
        }

        // 余额校验（服务内还会再锁行校验一次，此处先给友好提示）
        BoosterEarningAccount account = earningAccountMapper.selectById(boosterId);
        if (account == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "收益账户不存在，请联系客服");
        }
        long available = account.getAvailable() == null ? 0L : account.getAvailable();
        if (available < amount) {
            throw BizException.of(ErrorCode.STATE_CONFLICT,
                    "可提现余额不足，当前 " + MoneyUtil.fenToYuan(available) + " 元");
        }

        LocalDateTime now = LocalDateTime.now();
        Withdraw withdraw = new Withdraw();
        withdraw.setId(idGenerator.nextId());
        withdraw.setWithdrawNo(idGenerator.nextWithdrawNo());
        withdraw.setBoosterId(boosterId);
        withdraw.setAmount(amount);
        withdraw.setStatus(WithdrawStatus.APPLIED.name());
        withdraw.setCreatedAt(now);
        withdraw.setUpdatedAt(now);
        withdrawMapper.insert(withdraw);

        // 冻结（幂等键 = 提现单号）
        earningService.freezeForWithdraw(boosterId, amount, withdraw.getWithdrawNo());

        log.info("提现申请成功 boosterId={} withdrawNo={} amount={}分 frozenFrom=available",
                boosterId, withdraw.getWithdrawNo(), amount);
        return withdraw;
    }

    /**
     * 管理员确认提现：APPLIED → APPROVED（01 §8 第 3 步）。
     *
     * <p>只做状态流转，不动资金——钱在申请时已冻结。</p>
     *
     * @param withdrawId 提现单 ID
     * @param approverId 管理员 ID
     * @return 确认后的提现单
     */
    @Transactional(rollbackFor = Exception.class)
    public Withdraw approve(Long withdrawId, Long approverId) {
        Withdraw withdraw = lockWithdraw(withdrawId);
        requireStatus(withdraw, WithdrawStatus.APPLIED, "只有待审核的提现可以确认");

        LocalDateTime now = LocalDateTime.now();
        int rows = withdrawMapper.transit(withdrawId, WithdrawStatus.APPLIED.name(),
                WithdrawStatus.APPROVED.name(), approverId, now, null);
        if (rows == 0) {
            throw BizException.of(ErrorCode.STATE_CONFLICT, "提现单状态已变更，请刷新列表");
        }
        withdraw.setStatus(WithdrawStatus.APPROVED.name());
        withdraw.setApproverId(approverId);
        withdraw.setApprovedAt(now);

        log.info("提现已确认 withdrawNo={} amount={}分 approverId={}",
                withdraw.getWithdrawNo(), withdraw.getAmount(), approverId);
        return withdraw;
    }

    /**
     * 管理员拒绝提现：APPLIED/APPROVED → REJECTED，frozen 退回 available（01 §8 第 5 步）。
     *
     * @param reason 拒绝理由，必填
     */
    @Transactional(rollbackFor = Exception.class)
    public Withdraw reject(Long withdrawId, Long approverId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "请填写拒绝理由");
        }
        return rejectInternal(withdrawId, approverId, reason, WithdrawStatus.REJECTED);
    }

    /**
     * 标记打款中：APPROVED → PAYING（调微信商家转账前）。
     *
     * <p>dev/模拟模式下由 {@code /dev} 接口或管理员直接推进；
     * 生产环境应在发起商家转账请求成功后置为此状态。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public Withdraw markPaying(Long withdrawId, Long operatorId) {
        Withdraw withdraw = lockWithdraw(withdrawId);
        requireStatus(withdraw, WithdrawStatus.APPROVED, "只有已确认的提现可以发起打款");

        LocalDateTime now = LocalDateTime.now();
        int rows = withdrawMapper.transit(withdrawId, WithdrawStatus.APPROVED.name(),
                WithdrawStatus.PAYING.name(), operatorId, now, null);
        if (rows == 0) {
            throw BizException.of(ErrorCode.STATE_CONFLICT, "提现单状态已变更，请刷新列表");
        }
        withdraw.setStatus(WithdrawStatus.PAYING.name());
        log.info("提现进入打款中 withdrawNo={}", withdraw.getWithdrawNo());
        return withdraw;
    }

    /**
     * 打款成功：PAYING/APPROVED → PAID，扣减 frozen（01 §8 第 5 步）。
     *
     * <p>钱在此刻真正离开平台，{@code total} 不变（历史累计到手已含这笔）。</p>
     *
     * @param operatorId 操作人；微信回调场景传 null
     */
    @Transactional(rollbackFor = Exception.class)
    public Withdraw markPaid(Long withdrawId, Long operatorId) {
        Withdraw withdraw = lockWithdraw(withdrawId);
        WithdrawStatus current = WithdrawStatus.valueOf(withdraw.getStatus());
        if (current == WithdrawStatus.PAID) {
            log.info("提现已打款成功，幂等返回 withdrawNo={}", withdraw.getWithdrawNo());
            return withdraw;
        }
        if (current != WithdrawStatus.PAYING && current != WithdrawStatus.APPROVED) {
            throw BizException.of(ErrorCode.STATE_CONFLICT,
                    "当前状态「" + current.label() + "」不能标记为已打款");
        }

        LocalDateTime now = LocalDateTime.now();
        int rows = withdrawMapper.transit(withdrawId, current.name(),
                WithdrawStatus.PAID.name(), operatorId, now, null);
        if (rows == 0) {
            throw BizException.of(ErrorCode.STATE_CONFLICT, "提现单状态已变更，请刷新列表");
        }
        // 扣减 frozen（幂等键 = 提现单号）
        earningService.withdrawSuccess(withdraw.getBoosterId(), withdraw.getAmount(),
                withdraw.getWithdrawNo());

        withdraw.setStatus(WithdrawStatus.PAID.name());
        withdraw.setPaidAt(now);
        log.info("提现打款成功 withdrawNo={} amount={}分 boosterId={}",
                withdraw.getWithdrawNo(), withdraw.getAmount(), withdraw.getBoosterId());
        return withdraw;
    }

    /**
     * 打款失败：PAYING → FAILED，frozen 退回 available（01 §8 第 5 步）。
     *
     * @param reason 失败原因
     */
    @Transactional(rollbackFor = Exception.class)
    public Withdraw markFailed(Long withdrawId, Long operatorId, String reason) {
        if (reason == null || reason.isBlank()) {
            reason = "商家转账失败";
        }
        return rejectInternal(withdrawId, operatorId, reason, WithdrawStatus.FAILED);
    }

    /**
     * 封号时自动拒绝该打手所有进行中的提现（01 §7.3-B、04 §3.7）。
     *
     * <p><b>理由固定为「打手封号，提现暂缓」</b>，不再等管理员手动处理。</p>
     *
     * <p><b>资金处理特殊</b>：封号场景下 frozen <b>不退回 available</b>——
     * 因为封号同时会把全部 available 转入 frozen（见
     * {@link BoosterEarningService#freezeAllForPunish}），若这里再退回就等于把封号冻结
     * 解开。解封后打手需重新申请提现。</p>
     *
     * @return 被自动拒绝的提现单数量
     */
    @Transactional(rollbackFor = Exception.class)
    public int rejectAllInProgressForPunish(Long boosterId) {
        List<Withdraw> inProgress = withdrawMapper.selectInProgressByBooster(boosterId);
        if (inProgress.isEmpty()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        int rejected = 0;
        for (Withdraw withdraw : inProgress) {
            int rows = withdrawMapper.transit(withdraw.getId(), withdraw.getStatus(),
                    WithdrawStatus.REJECTED.name(), null, now, "打手封号，提现暂缓");
            if (rows > 0) {
                rejected++;
                log.warn("封号自动拒绝提现 boosterId={} withdrawNo={} amount={}分 from={}",
                        boosterId, withdraw.getWithdrawNo(), withdraw.getAmount(), withdraw.getStatus());
            }
        }
        return rejected;
    }

    /** 查询提现单（不存在抛 404）。 */
    public Withdraw requireById(Long withdrawId) {
        Withdraw withdraw = withdrawMapper.selectById(withdrawId);
        if (withdraw == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "提现单不存在，请刷新列表");
        }
        return withdraw;
    }

    /** 按打手分页查提现记录。 */
    public IPage<Withdraw> listByBooster(Long boosterId, int page, int pageSize) {
        return withdrawMapper.selectPage(Page.of(Math.max(1, page), Math.max(1, pageSize)),
                new LambdaQueryWrapper<Withdraw>()
                        .eq(Withdraw::getBoosterId, boosterId)
                        .orderByDesc(Withdraw::getCreatedAt)
                        .orderByDesc(Withdraw::getId));
    }

    /**
     * 管理端提现列表，按状态过滤（04 §3.7 {@code GET /admin/withdrawals}）。
     *
     * <p>待审核（APPLIED）优先排前，便于管理员按 24h 时限处理（01 §8）。</p>
     */
    public IPage<Withdraw> listForAdmin(String status, int page, int pageSize) {
        LambdaQueryWrapper<Withdraw> wrapper = new LambdaQueryWrapper<Withdraw>()
                .orderByAsc(Withdraw::getCreatedAt)
                .orderByAsc(Withdraw::getId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(Withdraw::getStatus, status);
        }
        return withdrawMapper.selectPage(Page.of(Math.max(1, page), Math.max(1, pageSize)), wrapper);
    }

    /**
     * 拒绝/失败的统一实现：状态置终态 + frozen 退回 available。
     */
    private Withdraw rejectInternal(Long withdrawId, Long operatorId, String reason,
                                    WithdrawStatus targetStatus) {
        Withdraw withdraw = lockWithdraw(withdrawId);
        WithdrawStatus current = WithdrawStatus.valueOf(withdraw.getStatus());
        if (current == targetStatus) {
            log.info("提现单已是 {}，幂等返回 withdrawNo={}", targetStatus, withdraw.getWithdrawNo());
            return withdraw;
        }
        if (current.isTerminal() && current != WithdrawStatus.PAYING) {
            throw BizException.of(ErrorCode.STATE_CONFLICT,
                    "提现单已结束（" + current.name() + "），不可再变更");
        }

        LocalDateTime now = LocalDateTime.now();
        int rows = withdrawMapper.transit(withdrawId, current.name(), targetStatus.name(),
                operatorId, now, truncate(reason));
        if (rows == 0) {
            throw BizException.of(ErrorCode.STATE_CONFLICT, "提现单状态已变更，请刷新列表");
        }
        // frozen 退回 available（幂等键 = 提现单号 + 目标状态，避免重复退回）
        earningService.withdrawFail(withdraw.getBoosterId(), withdraw.getAmount(),
                withdraw.getWithdrawNo() + ":" + targetStatus.name(), reason);

        withdraw.setStatus(targetStatus.name());
        withdraw.setFailReason(truncate(reason));
        log.info("提现{} withdrawNo={} amount={}分 boosterId={} reason={}",
                targetStatus == WithdrawStatus.REJECTED ? "已拒绝" : "打款失败",
                withdraw.getWithdrawNo(), withdraw.getAmount(), withdraw.getBoosterId(), reason);
        return withdraw;
    }

    private Withdraw lockWithdraw(Long withdrawId) {
        Withdraw withdraw = withdrawMapper.selectForUpdate(withdrawId);
        if (withdraw == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "提现单不存在，请刷新列表");
        }
        return withdraw;
    }

    private static void requireStatus(Withdraw withdraw, WithdrawStatus expected, String message) {
        if (!expected.name().equals(withdraw.getStatus())) {
            throw BizException.of(ErrorCode.STATE_CONFLICT,
                    message + "（当前状态 " + withdraw.getStatus() + "）");
        }
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 255 ? value : value.substring(0, 255);
    }
}
