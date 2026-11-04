package com.pw.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pw.admin.dto.AdminUserDto;
import com.pw.admin.entity.AdminLog;
import com.pw.admin.entity.AdminUser;
import com.pw.admin.mapper.AdminLogMapper;
import com.pw.admin.mapper.AdminUserMapper;
import com.pw.booster.entity.Booster;
import com.pw.booster.mapper.BoosterMapper;
import com.pw.common.util.CryptoUtil;
import com.pw.gold.entity.CustomerGoldAccount;
import com.pw.gold.mapper.CustomerGoldAccountMapper;
import com.pw.member.entity.MemberAccount;
import com.pw.member.mapper.MemberAccountMapper;
import com.pw.user.entity.User;
import com.pw.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** 管理端用户列表与操作日志（只读）。 */
@Service
@RequiredArgsConstructor
public class AdminUserQueryService {

    private final UserMapper userMapper;
    private final MemberAccountMapper memberAccountMapper;
    private final CustomerGoldAccountMapper goldAccountMapper;
    private final BoosterMapper boosterMapper;
    private final AdminLogMapper adminLogMapper;
    private final AdminUserMapper adminUserMapper;
    private final CryptoUtil cryptoUtil;

    public IPage<AdminUserDto.UserRow> users(String keyword, String status, int page, int pageSize, Page<User> pager) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>()
                .orderByDesc(User::getCreatedAt);
        if (status != null && !status.isBlank()) {
            wrapper.eq(User::getStatus, status);
        }
        String kw = keyword == null ? null : keyword.trim();
        if (kw != null && !kw.isEmpty()) {
            if (kw.matches("1[3-9]\\d{9}")) {
                wrapper.eq(User::getPhoneHash, cryptoUtil.sha256(kw));
            } else {
                wrapper.like(User::getNickname, kw);
            }
        }
        IPage<User> users = userMapper.selectPage(pager, wrapper);
        List<AdminUserDto.UserRow> rows = new ArrayList<>(users.getRecords().size());
        for (User user : users.getRecords()) {
            MemberAccount member = memberAccountMapper.selectById(user.getId());
            CustomerGoldAccount gold = goldAccountMapper.selectById(user.getId());
            Booster booster = boosterMapper.selectOne(new LambdaQueryWrapper<Booster>()
                    .eq(Booster::getUserId, user.getId()).last("LIMIT 1"));
            rows.add(AdminUserDto.UserRow.builder()
                    .id(user.getId())
                    .nickname(user.getNickname())
                    .phone(cryptoUtil.decrypt(user.getPhoneCipher()))
                    .status(user.getStatus())
                    .verified(user.getVerified())
                    .memberLevel(member == null ? MemberAccount.LEVEL_NORMAL : member.getLevelOrDefault())
                    .goldBalance(gold == null ? 0L : gold.getBalance())
                    .boosterId(booster == null ? null : booster.getId())
                    .boosterStatus(booster == null ? null : booster.getStatus())
                    .createdAt(user.getCreatedAt())
                    .build());
        }
        return new Page<AdminUserDto.UserRow>(users.getCurrent(), users.getSize(), users.getTotal())
                .setRecords(rows);
    }

    public IPage<AdminUserDto.LogRow> logs(String keyword, int page, int pageSize) {
        LambdaQueryWrapper<AdminLog> wrapper = new LambdaQueryWrapper<AdminLog>()
                .orderByDesc(AdminLog::getCreatedAt)
                .orderByDesc(AdminLog::getId);
        String kw = keyword == null ? null : keyword.trim();
        if (kw != null && !kw.isEmpty()) {
            wrapper.and(w -> w.like(AdminLog::getModule, kw).or().like(AdminLog::getAction, kw));
        }
        IPage<AdminLog> logs = adminLogMapper.selectPage(Page.of(Math.max(1, page), Math.max(1, pageSize)), wrapper);
        List<AdminUserDto.LogRow> rows = new ArrayList<>(logs.getRecords().size());
        for (AdminLog log : logs.getRecords()) {
            AdminUser admin = adminUserMapper.selectById(log.getAdminId());
            rows.add(AdminUserDto.LogRow.builder()
                    .id(log.getId())
                    .adminId(log.getAdminId())
                    .adminName(admin == null ? null : admin.getUsername())
                    .module(log.getModule())
                    .action(log.getAction())
                    .ip(log.getIp())
                    .createdAt(log.getCreatedAt())
                    .build());
        }
        return new Page<AdminUserDto.LogRow>(logs.getCurrent(), logs.getSize(), logs.getTotal())
                .setRecords(rows);
    }
}
