package com.pw.booster.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pw.booster.entity.BoosterGameAccount;
import com.pw.booster.mapper.BoosterGameAccountMapper;
import com.pw.common.exception.BizException;
import com.pw.common.exception.ErrorCode;
import com.pw.common.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 打手游戏账号服务。对应 04 §3.6 {@code /booster/game-accounts}。
 *
 * <p>「开始服务」「申请完单」都要求指定用于本单的游戏账号（01 §3 guard），
 * 账号必须属于该打手且状态 ACTIVE。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BoosterGameAccountService {

    /** 单个打手游戏账号数量上限，防止无限堆积。 */
    private static final int MAX_ACCOUNTS_PER_BOOSTER = 10;

    private final BoosterGameAccountMapper gameAccountMapper;
    private final IdGenerator idGenerator;

    /** 查询打手全部游戏账号（含停用，便于前端展示状态）。 */
    public List<BoosterGameAccount> listByBooster(Long boosterId) {
        return gameAccountMapper.selectList(new LambdaQueryWrapper<BoosterGameAccount>()
                .eq(BoosterGameAccount::getBoosterId, boosterId)
                .orderByAsc(BoosterGameAccount::getId));
    }

    /**
     * 新增游戏账号。
     *
     * <p>同一打手同一 (gameId, accountId) 不可重复添加。</p>
     *
     * @param server 区服，可为 null
     */
    @Transactional(rollbackFor = Exception.class)
    public BoosterGameAccount create(Long boosterId, String gameId, String accountId, String server) {
        if (gameId == null || gameId.isBlank()) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "请填写游戏");
        }
        if (accountId == null || accountId.isBlank()) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "请填写游戏账号 ID");
        }
        Long existing = gameAccountMapper.selectCount(new LambdaQueryWrapper<BoosterGameAccount>()
                .eq(BoosterGameAccount::getBoosterId, boosterId)
                .eq(BoosterGameAccount::getGameId, gameId.trim())
                .eq(BoosterGameAccount::getAccountId, accountId.trim()));
        if (existing != null && existing > 0) {
            throw BizException.of(ErrorCode.DUPLICATE_SUBMIT, "该游戏账号已添加，请勿重复提交");
        }
        Long total = gameAccountMapper.selectCount(new LambdaQueryWrapper<BoosterGameAccount>()
                .eq(BoosterGameAccount::getBoosterId, boosterId));
        if (total != null && total >= MAX_ACCOUNTS_PER_BOOSTER) {
            throw BizException.of(ErrorCode.PARAM_ERROR,
                    "最多添加 " + MAX_ACCOUNTS_PER_BOOSTER + " 个游戏账号");
        }

        LocalDateTime now = LocalDateTime.now();
        BoosterGameAccount account = new BoosterGameAccount();
        account.setId(idGenerator.nextId());
        account.setBoosterId(boosterId);
        account.setGameId(gameId.trim());
        account.setAccountId(accountId.trim());
        account.setServer(server == null || server.isBlank() ? null : server.trim());
        account.setStatus(BoosterGameAccount.STATUS_ACTIVE);
        account.setCreatedAt(now);
        gameAccountMapper.insert(account);

        log.info("打手新增游戏账号 boosterId={} gameId={} accountId={}", boosterId, gameId, accountId);
        return account;
    }
}
