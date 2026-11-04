package com.pw.booster.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pw.booster.dto.BoosterApplyDto;
import com.pw.booster.dto.BoosterApplyReq;
import com.pw.booster.entity.Booster;
import com.pw.booster.entity.BoosterApply;
import com.pw.booster.entity.BoosterApplyProfile;
import com.pw.booster.entity.BoosterEarningAccount;
import com.pw.booster.entity.BoosterGameAccount;
import com.pw.booster.entity.BoosterTrial;
import com.pw.booster.mapper.BoosterApplyMapper;
import com.pw.booster.mapper.BoosterApplyProfileMapper;
import com.pw.booster.mapper.BoosterEarningAccountMapper;
import com.pw.booster.mapper.BoosterGameAccountMapper;
import com.pw.booster.mapper.BoosterMapper;
import com.pw.booster.mapper.BoosterTrialMapper;
import com.pw.common.enums.Role;
import com.pw.common.exception.BizException;
import com.pw.common.exception.ErrorCode;
import com.pw.common.util.CryptoUtil;
import com.pw.common.util.IdGenerator;
import com.pw.user.entity.User;
import com.pw.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 打手入驻：申请 → 审核（通过自动建打手档案/收益账户/首个游戏账号）→ 资料维护。
 *
 * <p>本期没有排班，也没有多步审核；一次提交资料，管理员通过或拒绝。
 * 每次审核动作写一条 {@code booster_trial}，就是“考核记录”。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BoosterApplyService {

    private static final int NICKNAME_INTERVAL_DAYS = 30;

    private final BoosterApplyMapper applyMapper;
    private final BoosterApplyProfileMapper profileMapper;
    private final BoosterTrialMapper trialMapper;
    private final BoosterMapper boosterMapper;
    private final BoosterEarningAccountMapper accountMapper;
    private final BoosterGameAccountMapper gameAccountMapper;
    private final UserMapper userMapper;
    private final CryptoUtil cryptoUtil;
    private final IdGenerator idGenerator;

    // ------------------------------------------------------------------
    // 打手端
    // ------------------------------------------------------------------

    @Transactional(rollbackFor = Exception.class)
    public BoosterApplyDto.View apply(Long userId, BoosterApplyReq.Apply req) {
        BoosterApply apply = applyMapper.selectByUserId(userId);
        LocalDateTime now = LocalDateTime.now();
        if (boosterMapper.selectCount(new LambdaQueryWrapper<Booster>()
                .eq(Booster::getUserId, userId)) > 0) {
            throw BizException.of(ErrorCode.STATE_CONFLICT, "你已经是打手，无需重复申请");
        }
        if (apply != null && BoosterApply.STATUS_APPROVED.equals(apply.getStatus())) {
            throw BizException.of(ErrorCode.STATE_CONFLICT, "你已经是打手，无需重复申请");
        }
        if (apply != null && BoosterApply.STATUS_PENDING.equals(apply.getStatus())) {
            throw BizException.of(ErrorCode.STATE_CONFLICT, "申请审核中，请耐心等待");
        }
        if (apply == null) {
            apply = new BoosterApply();
            apply.setId(idGenerator.nextId());
            apply.setUserId(userId);
            apply.setStep(1);
            apply.setStatus(BoosterApply.STATUS_PENDING);
            apply.setSubmittedAt(now);
            apply.setCreatedAt(now);
            apply.setUpdatedAt(now);
            applyMapper.insert(apply);
        } else {
            apply.setStatus(BoosterApply.STATUS_PENDING);
            apply.setRejectReason(null);
            apply.setSubmittedAt(now);
            apply.setReviewedBy(null);
            apply.setReviewedAt(null);
            apply.setUpdatedAt(now);
            applyMapper.updateById(apply);
        }

        BoosterApplyProfile profile = profileMapper.selectByApplyId(apply.getId());
        boolean insert = profile == null;
        if (insert) {
            profile = new BoosterApplyProfile();
            profile.setId(idGenerator.nextId());
            profile.setApplyId(apply.getId());
            profile.setCreatedAt(now);
        }
        profile.setNickname(req.getNickname().trim());
        profile.setAge(req.getAge());
        profile.setGender(req.getGender());
        profile.setGameKey(req.getGameKey().trim());
        profile.setGameAccount(req.getGameAccount().trim());
        profile.setRankText(trimToNull(req.getRankText()));
        profile.setWechat(trimToNull(req.getWechat()));
        profile.setIntro(trimToNull(req.getIntro()));
        profile.setUpdatedAt(now);
        if (insert) {
            profileMapper.insert(profile);
        } else {
            profileMapper.updateById(profile);
        }
        log.info("打手入驻申请已提交 userId={} applyId={}", userId, apply.getId());
        return toView(apply, profile, userMapper.selectById(userId), false);
    }

    /** 我的申请状态；没申请过返回 null。 */
    public BoosterApplyDto.View my(Long userId) {
        BoosterApply apply = applyMapper.selectByUserId(userId);
        if (apply == null) {
            return null;
        }
        return toView(apply, profileMapper.selectByApplyId(apply.getId()),
                userMapper.selectById(userId), false);
    }

    // ------------------------------------------------------------------
    // 管理端
    // ------------------------------------------------------------------

    public List<BoosterApplyDto.View> list(String status, String keyword) {
        LambdaQueryWrapper<BoosterApply> wrapper = new LambdaQueryWrapper<BoosterApply>()
                .orderByDesc(BoosterApply::getSubmittedAt)
                .orderByDesc(BoosterApply::getId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(BoosterApply::getStatus, status);
        }
        String kw = keyword == null ? null : keyword.trim();
        List<BoosterApplyDto.View> result = new ArrayList<>();
        for (BoosterApply apply : applyMapper.selectList(wrapper)) {
            User user = userMapper.selectById(apply.getUserId());
            BoosterApplyProfile profile = profileMapper.selectByApplyId(apply.getId());
            BoosterApplyDto.View view = toView(apply, profile, user, true);
            if (kw != null && !kw.isEmpty()) {
                boolean hit = (view.getNickname() != null && view.getNickname().contains(kw))
                        || kw.equals(view.getPhone());
                if (!hit) {
                    continue;
                }
            }
            result.add(view);
        }
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public void approve(Long applyId, Long operatorId) {
        BoosterApply apply = applyMapper.selectForUpdate(applyId);
        if (apply == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "申请不存在");
        }
        if (!BoosterApply.STATUS_PENDING.equals(apply.getStatus())) {
            throw BizException.of(ErrorCode.STATE_CONFLICT, "该申请已处理，请刷新列表");
        }
        BoosterApplyProfile profile = profileMapper.selectByApplyId(applyId);
        if (profile == null) {
            throw BizException.of(ErrorCode.STATE_CONFLICT, "申请资料缺失，无法通过");
        }
        User user = userMapper.selectById(apply.getUserId());
        if (user == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "申请人不存在");
        }

        LocalDateTime now = LocalDateTime.now();
        Booster booster = boosterMapper.selectOne(new LambdaQueryWrapper<Booster>()
                .eq(Booster::getUserId, user.getId()).last("LIMIT 1"));
        if (booster == null) {
            booster = new Booster();
            booster.setId(idGenerator.nextId());
            booster.setUserId(user.getId());
            booster.setLevel(1);
            booster.setServiceScore(java.math.BigDecimal.valueOf(5.00));
            booster.setCompleteRate(java.math.BigDecimal.valueOf(100.00));
            booster.setCreatedAt(now);
            booster.setUpdatedAt(now);
            booster.setActivatedAt(now);
            booster.setNicknameUpdatedAt(now);
            booster.setNickname(profile.getNickname());
            booster.setAge(profile.getAge());
            booster.setGender(profile.getGender());
            booster.setStatus(Booster.STATUS_ACTIVE);
            boosterMapper.insert(booster);
        } else {
            booster.setNickname(profile.getNickname());
            booster.setAge(profile.getAge());
            booster.setGender(profile.getGender());
            booster.setStatus(Booster.STATUS_ACTIVE);
            if (booster.getActivatedAt() == null) {
                booster.setActivatedAt(now);
            }
            booster.setUpdatedAt(now);
            boosterMapper.updateById(booster);
        }

        if (accountMapper.selectById(booster.getId()) == null) {
            BoosterEarningAccount account = new BoosterEarningAccount();
            account.setBoosterId(booster.getId());
            account.setFrozen(0L);
            account.setAvailable(0L);
            account.setTotal(0L);
            account.setVersion(0);
            try {
                accountMapper.insert(account);
            } catch (DuplicateKeyException ignore) {
                // 并发通过时另一线程已建，幂等
            }
        }
        createFirstGameAccount(booster.getId(), profile, now);

        apply.setStatus(BoosterApply.STATUS_APPROVED);
        apply.setReviewedBy(operatorId);
        apply.setReviewedAt(now);
        apply.setUpdatedAt(now);
        applyMapper.updateById(apply);
        recordTrial(applyId, "APPROVED", "审核通过", operatorId, now);
        log.info("入驻审核通过 applyId={} boosterId={} operatorId={}", applyId, booster.getId(), operatorId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void reject(Long applyId, Long operatorId, String reason) {
        BoosterApply apply = applyMapper.selectForUpdate(applyId);
        if (apply == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "申请不存在");
        }
        if (!BoosterApply.STATUS_PENDING.equals(apply.getStatus())) {
            throw BizException.of(ErrorCode.STATE_CONFLICT, "该申请已处理，请刷新列表");
        }
        LocalDateTime now = LocalDateTime.now();
        apply.setStatus(BoosterApply.STATUS_REJECTED);
        apply.setRejectReason(reason);
        apply.setReviewedBy(operatorId);
        apply.setReviewedAt(now);
        apply.setUpdatedAt(now);
        applyMapper.updateById(apply);
        recordTrial(applyId, "REJECTED", reason, operatorId, now);
        log.info("入驻审核拒绝 applyId={} operatorId={} reason={}", applyId, operatorId, reason);
    }

    public List<BoosterApplyDto.Trial> trials(Long applyId) {
        List<BoosterTrial> list = trialMapper.selectByApplyId(applyId);
        List<BoosterApplyDto.Trial> result = new ArrayList<>(list.size());
        for (BoosterTrial t : list) {
            result.add(BoosterApplyDto.Trial.builder()
                    .id(t.getId())
                    .result(t.getResult())
                    .remark(t.getRemark())
                    .operatorId(t.getOperatorId())
                    .finishedAt(t.getFinishedAt())
                    .createdAt(t.getCreatedAt())
                    .build());
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 打手资料
    // ------------------------------------------------------------------

    public BoosterApplyDto.ProfileView profile(Long boosterId) {
        Booster booster = requireBooster(boosterId);
        return toProfileView(booster);
    }

    @Transactional(rollbackFor = Exception.class)
    public BoosterApplyDto.ProfileView updateProfile(Long boosterId, BoosterApplyReq.Profile req) {
        Booster booster = requireBooster(boosterId);
        LocalDateTime now = LocalDateTime.now();
        boolean changed = false;

        String nickname = trimToNull(req.getNickname());
        if (nickname != null && !nickname.equals(booster.getNickname())) {
            if (booster.getNicknameUpdatedAt() != null
                    && booster.getNicknameUpdatedAt().plusDays(NICKNAME_INTERVAL_DAYS).isAfter(now)) {
                LocalDateTime next = booster.getNicknameUpdatedAt().plusDays(NICKNAME_INTERVAL_DAYS);
                throw BizException.of(ErrorCode.TOO_MANY_REQUESTS,
                        "昵称每月只能改一次，下次可改时间：" + next.toLocalDate());
            }
            booster.setNickname(nickname);
            booster.setNicknameUpdatedAt(now);
            changed = true;
        }
        if (req.getAge() != null) {
            if (booster.getAge() != null && !booster.getAge().equals(req.getAge())) {
                throw BizException.of(ErrorCode.STATE_CONFLICT, "年龄已提交，如需修改请联系客服");
            }
            if (booster.getAge() == null) {
                booster.setAge(req.getAge());
                changed = true;
            }
        }
        if (req.getGender() != null) {
            if (booster.getGender() != null && !booster.getGender().equals(req.getGender())) {
                throw BizException.of(ErrorCode.STATE_CONFLICT, "性别已提交，如需修改请联系客服");
            }
            if (booster.getGender() == null) {
                booster.setGender(req.getGender());
                changed = true;
            }
        }
        if (!changed) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "没有需要更新的内容");
        }
        booster.setUpdatedAt(now);
        boosterMapper.updateById(booster);
        return toProfileView(booster);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private void createFirstGameAccount(Long boosterId, BoosterApplyProfile profile, LocalDateTime now) {
        if (profile.getGameKey() == null || profile.getGameAccount() == null) {
            return;
        }
        Long count = gameAccountMapper.selectCount(new LambdaQueryWrapper<BoosterGameAccount>()
                .eq(BoosterGameAccount::getBoosterId, boosterId)
                .eq(BoosterGameAccount::getGameId, profile.getGameKey())
                .eq(BoosterGameAccount::getAccountId, profile.getGameAccount()));
        if (count != null && count > 0) {
            return;
        }
        BoosterGameAccount account = new BoosterGameAccount();
        account.setId(idGenerator.nextId());
        account.setBoosterId(boosterId);
        account.setGameId(profile.getGameKey());
        account.setAccountId(profile.getGameAccount());
        account.setStatus(BoosterGameAccount.STATUS_ACTIVE);
        account.setCreatedAt(now);
        try {
            gameAccountMapper.insert(account);
        } catch (DuplicateKeyException ignore) {
            // uk_account 命中即已存在
        }
    }

    private void recordTrial(Long applyId, String result, String remark, Long operatorId, LocalDateTime now) {
        BoosterTrial trial = new BoosterTrial();
        trial.setId(idGenerator.nextId());
        trial.setApplyId(applyId);
        trial.setResult(result);
        trial.setRemark(remark);
        trial.setOperatorId(operatorId);
        trial.setFinishedAt(now);
        trial.setCreatedAt(now);
        trialMapper.insert(trial);
    }

    private Booster requireBooster(Long boosterId) {
        Booster booster = boosterMapper.selectById(boosterId);
        if (booster == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "打手不存在");
        }
        return booster;
    }

    private BoosterApplyDto.ProfileView toProfileView(Booster booster) {
        LocalDateTime changeableAt = booster.getNicknameUpdatedAt() == null
                ? null
                : booster.getNicknameUpdatedAt().plusDays(NICKNAME_INTERVAL_DAYS);
        return BoosterApplyDto.ProfileView.builder()
                .id(booster.getId())
                .nickname(booster.getNickname())
                .age(booster.getAge())
                .gender(booster.getGender())
                .status(booster.getStatus())
                .nicknameUpdatedAt(booster.getNicknameUpdatedAt())
                .nicknameChangeableAt(changeableAt)
                .build();
    }

    private BoosterApplyDto.View toView(BoosterApply apply, BoosterApplyProfile profile, User user,
                                        boolean withPhone) {
        return BoosterApplyDto.View.builder()
                .id(apply.getId())
                .userId(apply.getUserId())
                .status(apply.getStatus())
                .rejectReason(apply.getRejectReason())
                .submittedAt(apply.getSubmittedAt())
                .reviewedAt(apply.getReviewedAt())
                .nickname(profile == null ? (user == null ? null : user.getNickname()) : profile.getNickname())
                .phone(withPhone && user != null ? cryptoUtil.decrypt(user.getPhoneCipher()) : null)
                .age(profile == null ? null : profile.getAge())
                .gender(profile == null ? null : profile.getGender())
                .gameKey(profile == null ? null : profile.getGameKey())
                .gameAccount(profile == null ? null : profile.getGameAccount())
                .rankText(profile == null ? null : profile.getRankText())
                .wechat(profile == null ? null : profile.getWechat())
                .intro(profile == null ? null : profile.getIntro())
                .createdAt(apply.getCreatedAt())
                .build();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
