package com.pw.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pw.admin.dto.MemberDto;
import com.pw.common.util.CryptoUtil;
import com.pw.gold.entity.CustomerGoldAccount;
import com.pw.gold.mapper.CustomerGoldAccountMapper;
import com.pw.member.entity.MemberAccount;
import com.pw.member.mapper.MemberAccountMapper;
import com.pw.user.entity.User;
import com.pw.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 管理端会员列表（只读）。
 *
 * <p>2026-10-31 口径：单笔充值满 2000 元由 {@code RechargeCreditService} 自动开通会员，
 * 不需要人工审批。本类只查 {@code member_account}，一个用户一行。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminMemberService {

    private final MemberAccountMapper accountMapper;
    private final CustomerGoldAccountMapper goldAccountMapper;
    private final UserMapper userMapper;
    private final CryptoUtil cryptoUtil;

    public List<MemberDto.Member> list(String keyword) {
        List<MemberAccount> accounts = accountMapper.selectList(new LambdaQueryWrapper<MemberAccount>()
                .eq(MemberAccount::getLevel, MemberAccount.LEVEL_MEMBER)
                .orderByDesc(MemberAccount::getApprovedAt));
        String kw = keyword == null ? null : keyword.trim();
        List<MemberDto.Member> result = new ArrayList<>(accounts.size());
        for (MemberAccount account : accounts) {
            User user = userMapper.selectById(account.getUserId());
            String nickname = user == null ? null : user.getNickname();
            String phone = user == null ? null : cryptoUtil.decrypt(user.getPhoneCipher());
            if (kw != null && !kw.isEmpty()
                    && (nickname == null || !nickname.contains(kw)) && !kw.equals(phone)) {
                continue;
            }
            CustomerGoldAccount gold = goldAccountMapper.selectById(account.getUserId());
            result.add(MemberDto.Member.builder()
                    .userId(account.getUserId())
                    .nickname(nickname)
                    .phone(phone)
                    .totalRecharged(gold == null ? 0L : gold.getTotalRecharged())
                    .level(account.getLevelOrDefault())
                    .permanent(account.getIsPermanent())
                    .memberSince(account.getApprovedAt())
                    .build());
        }
        return result;
    }
}
