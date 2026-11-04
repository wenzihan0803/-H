package com.pw.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pw.admin.dto.PunishmentDto;
import com.pw.admin.entity.AdminUser;
import com.pw.admin.entity.Punishment;
import com.pw.admin.entity.PunishmentAppeal;
import com.pw.admin.entity.PunishmentQuota;
import com.pw.admin.mapper.AdminUserMapper;
import com.pw.admin.mapper.PunishmentAppealMapper;
import com.pw.admin.mapper.PunishmentMapper;
import com.pw.admin.mapper.PunishmentQuotaMapper;
import com.pw.booster.entity.Booster;
import com.pw.booster.entity.BoosterEarningTxn;
import com.pw.booster.mapper.BoosterEarningTxnMapper;
import com.pw.booster.mapper.BoosterMapper;
import com.pw.booster.service.BoosterEarningService;
import com.pw.common.enums.EarningTxnType;
import com.pw.common.enums.Role;
import com.pw.common.exception.BizException;
import com.pw.common.exception.ErrorCode;
import com.pw.common.security.LoginUser;
import com.pw.common.util.IdGenerator;
import com.pw.user.entity.User;
import com.pw.user.mapper.UserMapper;
import com.pw.withdraw.service.WithdrawService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 处置中心：警告、临时封禁、长期封禁、解封、申诉查看。
 *
 * <p>封禁打手会立即冻结其可提现收益，并自动拒绝在途提现（01 §7.3-B）；
 * 解封时按当初冻结的金额退回。客服每日 3 次、管理员每月 10 次配额。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminPunishmentService {

    private final PunishmentMapper punishmentMapper;
    private final PunishmentAppealMapper appealMapper;
    private final PunishmentQuotaMapper quotaMapper;
    private final BoosterMapper boosterMapper;
    private final UserMapper userMapper;
    private final AdminUserMapper adminUserMapper;
    private final BoosterEarningTxnMapper txnMapper;
    private final BoosterEarningService earningService;
    private final WithdrawService withdrawService;
    private final IdGenerator idGenerator;

    public IPage<PunishmentDto.Row> list(String status, String targetType, int page, int pageSize) {
        LambdaQueryWrapper<Punishment> wrapper = new LambdaQueryWrapper<Punishment>()
                .orderByDesc(Punishment::getCreatedAt)
                .orderByDesc(Punishment::getId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(Punishment::getStatus, status);
        }
        if (targetType != null && !targetType.isBlank()) {
            wrapper.eq(Punishment::getTargetType, targetType);
        }
        IPage<Punishment> p = punishmentMapper.selectPage(Page.of(Math.max(1, page), Math.min(pageSize, 100)), wrapper);
        List<PunishmentDto.Row> rows = new ArrayList<>(p.getRecords().size());
        for (Punishment punishment : p.getRecords()) {
            rows.add(toRow(punishment));
        }
        return new Page<PunishmentDto.Row>(p.getCurrent(), p.getSize(), p.getTotal()).setRecords(rows);
    }

    @Transactional(rollbackFor = Exception.class)
    public Long create(PunishmentDto.CreateReq req, LoginUser operator) {
        String targetType = req.getTargetType().toUpperCase();
        String type = req.getType().toUpperCase();
        if (!Punishment.TARGET_BOOSTER.equals(targetType) && !Punishment.TARGET_USER.equals(targetType)) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "处置对象只能是打手或用户");
        }
        if (!Punishment.TYPE_WARNING.equals(type) && !Punishment.TYPE_TEMP_BAN.equals(type)
                && !Punishment.TYPE_BAN.equals(type)) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "处置类型只能是警告、临时封禁或长期封禁");
        }
        Booster booster = null;
        User user = null;
        if (Punishment.TARGET_BOOSTER.equals(targetType)) {
            booster = boosterMapper.selectById(req.getTargetId());
            if (booster == null) {
                throw BizException.of(ErrorCode.NOT_FOUND, "打手不存在");
            }
        } else {
            user = userMapper.selectById(req.getTargetId());
            if (user == null) {
                throw BizException.of(ErrorCode.NOT_FOUND, "用户不存在");
            }
        }

        boolean ban = !Punishment.TYPE_WARNING.equals(type);
        if (ban) {
            consumeQuota(operator.getRole());
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime endAt = null;
        if (Punishment.TYPE_TEMP_BAN.equals(type)) {
            int hours = req.getDurationHours() == null ? 0 : req.getDurationHours();
            if (hours <= 0) {
                throw BizException.of(ErrorCode.PARAM_ERROR, "临时封禁必须填写时长（小时）");
            }
            endAt = now.plusHours(hours);
        }

        Punishment punishment = new Punishment();
        punishment.setId(idGenerator.nextId());
        punishment.setTargetType(targetType);
        punishment.setTargetId(req.getTargetId());
        punishment.setType(type);
        punishment.setReason(req.getReason().trim());
        punishment.setEvidenceJson(toJson(req.getEvidenceKeys()));
        punishment.setOperatorId(operator.getSubjectId());
        punishment.setStartAt(now);
        punishment.setEndAt(endAt);
        punishment.setStatus(Punishment.STATUS_ACTIVE);
        punishmentMapper.insert(punishment);

        if (booster != null && ban) {
            booster.setStatus(Punishment.TYPE_TEMP_BAN.equals(type)
                    ? Booster.STATUS_TEMP_BANNED : Booster.STATUS_BANNED);
            booster.setBanUntil(endAt);
            boosterMapper.updateById(booster);
            earningService.freezeAllForPunish(booster.getId(), "PUNISH_" + punishment.getId(), punishment.getReason());
            withdrawService.rejectAllInProgressForPunish(booster.getId());
        }
        if (user != null && Punishment.TYPE_BAN.equals(type)) {
            user.setStatus("BANNED");
            userMapper.updateById(user);
        }
        log.warn("处置创建 operator={} target={}:{} type={} id={}",
                operator.getSubjectId(), targetType, req.getTargetId(), type, punishment.getId());
        return punishment.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void lift(Long punishmentId, LoginUser operator) {
        Punishment punishment = punishmentMapper.selectById(punishmentId);
        if (punishment == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "处置记录不存在");
        }
        if (!Punishment.STATUS_ACTIVE.equals(punishment.getStatus())) {
            throw BizException.of(ErrorCode.STATE_CONFLICT, "该处置已解除，请刷新列表");
        }
        LocalDateTime now = LocalDateTime.now();
        if (Punishment.TARGET_BOOSTER.equals(punishment.getTargetType())
                && !Punishment.TYPE_WARNING.equals(punishment.getType())) {
            Booster booster = boosterMapper.selectById(punishment.getTargetId());
            if (booster != null) {
                // updateById 默认忽略 null，banUntil 必须用 UpdateWrapper 显式置空
                boosterMapper.update(null, new LambdaUpdateWrapper<Booster>()
                        .eq(Booster::getId, booster.getId())
                        .set(Booster::getStatus, Booster.STATUS_ACTIVE)
                        .set(Booster::getBanUntil, null));
                BoosterEarningTxn freeze = txnMapper.selectOne(new LambdaQueryWrapper<BoosterEarningTxn>()
                        .eq(BoosterEarningTxn::getBizType, EarningTxnType.PUNISH_FREEZE.name())
                        .eq(BoosterEarningTxn::getBizNo, "PUNISH_" + punishment.getId())
                        .last("LIMIT 1"));
                if (freeze != null && freeze.getAmount() != null && freeze.getAmount() < 0) {
                    earningService.unfreezeFromPunish(booster.getId(), -freeze.getAmount(),
                            "PUNISH_UNFREEZE_" + punishment.getId());
                }
            }
        }
        if (Punishment.TARGET_USER.equals(punishment.getTargetType())) {
            User user = userMapper.selectById(punishment.getTargetId());
            if (user != null && "BANNED".equals(user.getStatus())) {
                user.setStatus("NORMAL");
                userMapper.updateById(user);
            }
        }
        punishment.setStatus(Punishment.STATUS_LIFTED);
        punishment.setEndAt(now);
        punishmentMapper.updateById(punishment);
        log.warn("处置解除 id={} operator={}", punishmentId, operator.getSubjectId());
    }

    public List<PunishmentDto.AppealRow> appeals(Long punishmentId) {
        List<PunishmentDto.AppealRow> rows = new ArrayList<>();
        for (PunishmentAppeal appeal : appealMapper.selectList(new LambdaQueryWrapper<PunishmentAppeal>()
                .eq(PunishmentAppeal::getPunishmentId, punishmentId)
                .orderByDesc(PunishmentAppeal::getCreatedAt))) {
            rows.add(PunishmentDto.AppealRow.builder()
                    .id(appeal.getId())
                    .punishmentId(appeal.getPunishmentId())
                    .reason(appeal.getReason())
                    .result(appeal.getResult())
                    .reviewerId(appeal.getReviewerId())
                    .createdAt(appeal.getCreatedAt())
                    .build());
        }
        return rows;
    }

    private PunishmentDto.Row toRow(Punishment p) {
        String targetName = null;
        if (Punishment.TARGET_BOOSTER.equals(p.getTargetType())) {
            Booster booster = boosterMapper.selectById(p.getTargetId());
            targetName = booster == null ? null : booster.getNickname();
        } else {
            User user = userMapper.selectById(p.getTargetId());
            targetName = user == null ? null : user.getNickname();
        }
        AdminUser operator = adminUserMapper.selectById(p.getOperatorId());
        return PunishmentDto.Row.builder()
                .id(p.getId())
                .targetType(p.getTargetType())
                .targetId(p.getTargetId())
                .targetName(targetName)
                .type(p.getType())
                .reason(p.getReason())
                .operatorId(p.getOperatorId())
                .operatorName(operator == null ? null : operator.getUsername())
                .startAt(p.getStartAt())
                .endAt(p.getEndAt())
                .status(p.getStatus())
                .createdAt(p.getCreatedAt())
                .build();
    }

    private void consumeQuota(Role role) {
        boolean cs = role == Role.CS;
        PunishmentQuota quota = quotaMapper.selectOne(new LambdaQueryWrapper<PunishmentQuota>()
                .eq(PunishmentQuota::getRole, cs ? "CS" : "ADMIN")
                .last("LIMIT 1"));
        if (quota == null) {
            return;
        }
        LocalDate periodStart = cs ? LocalDate.now() : LocalDate.now().withDayOfMonth(1);
        if (quota.getResetAt() == null || quota.getResetAt().isBefore(periodStart.atStartOfDay())) {
            if (cs) {
                quota.setUsedToday(0);
            } else {
                quota.setUsedMonth(0);
            }
            quota.setResetAt(periodStart.atStartOfDay());
        }
        int used = cs ? quota.getUsedToday() : quota.getUsedMonth();
        int limit = cs ? quota.getDailyLimit() : quota.getMonthlyLimit();
        if (used >= limit) {
            throw BizException.of(ErrorCode.STATE_CONFLICT,
                    cs ? "今日处置配额已用完（每日 " + limit + " 次）"
                            : "本月处置配额已用完（每月 " + limit + " 次）");
        }
        if (cs) {
            quota.setUsedToday(used + 1);
        } else {
            quota.setUsedMonth(used + 1);
        }
        quotaMapper.updateById(quota);
    }

    private String toJson(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(keys.get(i).replace("\"", "\\\"")).append('"');
        }
        return sb.append(']').toString();
    }
}
