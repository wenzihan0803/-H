package com.pw.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pw.admin.dto.AdminDto;
import com.pw.booster.entity.Booster;
import com.pw.booster.entity.BoosterEarningAccount;
import com.pw.booster.mapper.BoosterEarningAccountMapper;
import com.pw.booster.mapper.BoosterEarningTxnMapper;
import com.pw.booster.mapper.BoosterMapper;
import com.pw.common.util.CryptoUtil;
import com.pw.order.mapper.BizOrderMapper;
import com.pw.user.entity.User;
import com.pw.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 管理端打手列表 / 派单候选。
 *
 * <p>本期没有排班：可派单条件 = ACTIVE + 未封禁 + 无进行中订单。
 * 手机号解密后只返回给管理端（库里是 AES 密文）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminBoosterService {

    private final BoosterMapper boosterMapper;
    private final BoosterEarningAccountMapper accountMapper;
    private final UserMapper userMapper;
    private final BizOrderMapper orderMapper;
    private final CryptoUtil cryptoUtil;
    private final BoosterEarningTxnMapper txnMapper;

    /**
     * @param keyword 昵称模糊；输入 11 位手机号时按手机号精确
     * @param status  可选，ACTIVE / PENDING_REVIEW 等
     */
    public List<AdminDto.AdminBooster> list(String keyword, String status) {
        String kw = keyword == null ? null : keyword.trim();
        LambdaQueryWrapper<Booster> wrapper = new LambdaQueryWrapper<Booster>()
                .orderByDesc(Booster::getActivatedAt)
                .orderByAsc(Booster::getId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(Booster::getStatus, status);
        }
        if (kw != null && !kw.isEmpty()) {
            if (kw.matches("1[3-9]\\d{9}")) {
                User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                        .eq(User::getPhoneHash, cryptoUtil.sha256(kw))
                        .last("LIMIT 1"));
                if (user == null) {
                    return Collections.emptyList();
                }
                wrapper.eq(Booster::getUserId, user.getId());
            } else {
                wrapper.like(Booster::getNickname, kw);
            }
        }

        List<Booster> boosters = boosterMapper.selectList(wrapper);
        LocalDateTime now = LocalDateTime.now();
        List<AdminDto.AdminBooster> result = new ArrayList<>(boosters.size());
        for (Booster b : boosters) {
            User user = userMapper.selectById(b.getUserId());
            long activeOrders = orderMapper.countInProgressByBooster(b.getId());
            boolean banned = b.getBanUntil() != null && b.getBanUntil().isAfter(now);
            boolean assignable = Booster.STATUS_ACTIVE.equals(b.getStatus()) && !banned && activeOrders == 0;
            BoosterEarningAccount account = accountMapper.selectById(b.getId());
            result.add(AdminDto.AdminBooster.builder()
                    .id(b.getId())
                    .name(user == null ? null : user.getNickname())
                    .nickname(b.getNickname() != null ? b.getNickname() : (user == null ? null : user.getNickname()))
                    .phone(user == null ? null : cryptoUtil.decrypt(user.getPhoneCipher()))
                    .age(b.getAge())
                    .gender(b.getGender())
                    .status(b.getStatus())
                    .activeOrders(activeOrders)
                    .completedOrders(orderMapper.countCompletedByBooster(b.getId()))
                    .totalEarnings(account == null ? 0L : account.getTotal())
                    .score(b.getServiceScore())
                    .activatedAt(b.getActivatedAt())
                    .banUntil(b.getBanUntil())
                    .assignable(assignable)
                    .build());
        }
        return result;
    }


    /**
     * 导出某打手的收益流水 CSV（默认本月由前端传参），用于发给打手对账。
     * columns 为空导出全部列；末尾附“合计”。
     */
    public byte[] exportEarnings(Long boosterId, LocalDate from, LocalDate to, java.util.List<String> columns) {
        Booster booster = boosterMapper.selectById(boosterId);
        if (booster == null) {
            throw com.pw.common.exception.BizException.of(
                    com.pw.common.exception.ErrorCode.NOT_FOUND, "打手不存在");
        }
        List<String> cols = (columns == null || columns.isEmpty())
                ? List.of("time", "bizType", "bizNo", "amount", "frozenAfter", "availableAfter", "remark")
                : columns;
        LambdaQueryWrapper<com.pw.booster.entity.BoosterEarningTxn> wrapper =
                new LambdaQueryWrapper<com.pw.booster.entity.BoosterEarningTxn>()
                        .eq(com.pw.booster.entity.BoosterEarningTxn::getBoosterId, boosterId)
                        .orderByAsc(com.pw.booster.entity.BoosterEarningTxn::getCreatedAt)
                        .orderByAsc(com.pw.booster.entity.BoosterEarningTxn::getId);
        if (from != null) {
            wrapper.ge(com.pw.booster.entity.BoosterEarningTxn::getCreatedAt, from.atStartOfDay());
        }
        if (to != null) {
            wrapper.lt(com.pw.booster.entity.BoosterEarningTxn::getCreatedAt, to.plusDays(1).atStartOfDay());
        }
        List<com.pw.booster.entity.BoosterEarningTxn> txns = txnMapper.selectList(wrapper);
        StringBuilder sb = new StringBuilder("\uFEFF");
        sb.append(String.join(",", cols.stream().map(AdminBoosterService::earningHeader).toList())).append('\n');
        long total = 0;
        for (com.pw.booster.entity.BoosterEarningTxn t : txns) {
            List<String> cells = new java.util.ArrayList<>(cols.size());
            for (String col : cols) {
                cells.add(earningCell(t, col));
            }
            sb.append(String.join(",", cells.stream().map(AdminBoosterService::escape).toList())).append('\n');
            total += t.getAmount() == null ? 0 : t.getAmount();
        }
        List<String> totalRow = new java.util.ArrayList<>();
        for (String col : cols) {
            if ("bizType".equals(col)) {
                totalRow.add("合计 " + txns.size() + " 笔");
            } else if ("amount".equals(col)) {
                totalRow.add(com.pw.common.util.MoneyUtil.fenToYuan(total));
            } else {
                totalRow.add("");
            }
        }
        sb.append(String.join(",", totalRow.stream().map(AdminBoosterService::escape).toList())).append('\n');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String earningHeader(String col) {
        return switch (col) {
            case "time" -> "时间";
            case "bizType" -> "类型";
            case "bizNo" -> "业务单号";
            case "amount" -> "金额(元)";
            case "frozenAfter" -> "冻结余额(元)";
            case "availableAfter" -> "可提现余额(元)";
            case "remark" -> "备注";
            default -> col;
        };
    }

    private static String earningCell(com.pw.booster.entity.BoosterEarningTxn t, String col) {
        return switch (col) {
            case "time" -> t.getCreatedAt() == null ? "" : t.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            case "bizType" -> earningTypeLabel(t.getBizType());
            case "bizNo" -> t.getBizNo();
            case "amount" -> com.pw.common.util.MoneyUtil.fenToYuan(t.getAmount() == null ? 0 : t.getAmount());
            case "frozenAfter" -> com.pw.common.util.MoneyUtil.fenToYuan(t.getFrozenAfter() == null ? 0 : t.getFrozenAfter());
            case "availableAfter" -> com.pw.common.util.MoneyUtil.fenToYuan(t.getAvailableAfter() == null ? 0 : t.getAvailableAfter());
            case "remark" -> t.getRemark();
            default -> "";
        };
    }

    private static String earningTypeLabel(String type) {
        if (type == null) {
            return "";
        }
        return switch (type) {
            case "SETTLE" -> "结算";
            case "WITHDRAW_FREEZE" -> "提现冻结";
            case "WITHDRAW_SUCCESS" -> "提现成功";
            case "WITHDRAW_FAIL" -> "提现退回";
            case "PUNISH_FREEZE" -> "封号冻结";
            case "PUNISH_UNFREEZE" -> "解封退回";
            case "ADJUST" -> "调账";
            default -> type;
        };
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        String v = value.replace("\"", "\"\"");
        return v.contains(",") || v.contains("\"") || v.contains("\n") ? "\"" + v + "\"" : v;
    }

}
