package com.pw.common.idempotent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pw.common.exception.BizException;
import com.pw.common.exception.ErrorCode;
import com.pw.common.security.LoginUser;
import com.pw.common.security.SecurityContexts;
import com.pw.common.util.CryptoUtil;
import com.pw.common.util.IdGenerator;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 幂等切面。
 *
 * <p>口径来源：技术规范/01 §5「创建订单必须带 Idempotency-Key，同一用户重复提交返回同一订单」、
 * §14 幂等与并发表。</p>
 *
 * <p>执行流程：</p>
 * <ol>
 *   <li>取请求头幂等键，缺失时报 40001；</li>
 *   <li>计算请求体哈希，先查历史记录：已 DONE 则直接回传首次响应；</li>
 *   <li>插入 PROCESSING 占位（靠唯一索引抢占），插入冲突说明有并发请求正在处理；</li>
 *   <li>执行业务，成功则回写响应 JSON 并置 DONE；</li>
 *   <li>业务失败时删除占位，允许客户端用同一幂等键重试。</li>
 * </ol>
 *
 * <p><b>同键不同参</b>视为客户端误用，报 40902，避免用同一幂等键提交两笔不同订单。</p>
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class IdempotentAspect {

    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_DONE = "DONE";
    private static final int MAX_KEY_LENGTH = 64;

    /**
     * PROCESSING 占位超时阈值（毫秒）。业务最长耗时（下单 <1s）远小于该值；
     * 超过视为进程崩溃残留，允许同键重新执行（2026-10-29 补，防僵尸占位永久挡单）。
     */
    private static final long STALE_THRESHOLD_MS = 10 * 60 * 1000L;

    private final com.pw.common.mapper.IdempotencyRecordMapper mapper;
    private final IdGenerator idGenerator;
    private final CryptoUtil cryptoUtil;
    private final ObjectMapper jsonMapper;

    @Around("@annotation(idempotent)")
    public Object around(ProceedingJoinPoint joinPoint, Idempotent idempotent) throws Throwable {
        if (!idempotent.headerMode()) {
            // 非请求头模式：由业务单号唯一索引兜底，这里直接放行
            return joinPoint.proceed();
        }

        String idemKey = resolveHeader(idempotent.header());
        if (idemKey == null || idemKey.isBlank()) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "缺少幂等标识，请重新提交");
        }
        if (idemKey.length() > MAX_KEY_LENGTH) {
            throw BizException.of(ErrorCode.PARAM_ERROR, "幂等标识过长，请重新生成后提交");
        }

        LoginUser loginUser = SecurityContexts.require();
        Long userId = loginUser.getSubjectId();
        String requestHash = cryptoUtil.sha256(serializeArgs(joinPoint));

        // 1. 命中已完成记录：直接回传首次结果
        IdempotencyRecord existing = find(idempotent.scene(), userId, idemKey);
        if (existing != null) {
            if (STATUS_DONE.equals(existing.getStatus()) && existing.getResponseJson() != null) {
                if (!existing.getRequestHash().equals(requestHash)) {
                    throw BizException.of(ErrorCode.DUPLICATE_SUBMIT,
                            "同一幂等标识已用于其他请求，请更换后重新提交");
                }
                log.info("幂等命中，返回首次结果 scene={} userId={}", idempotent.scene(), userId);
                return deserialize(existing.getResponseJson(), returnTypeOf(joinPoint));
            }
            // 仍在处理中：区分「正在并发执行」与「进程崩溃残留的僵尸占位」。
            // 超过 STALE_THRESHOLD 的占位视为残留，删除后重新执行，避免同键永久被挡
            if (isStale(existing)) {
                log.warn("幂等占位超时（疑似进程崩溃残留），删除后重试 scene={} userId={} idemKey={} createdAt={}",
                        idempotent.scene(), userId, idemKey, existing.getCreatedAt());
                mapper.deleteById(existing.getId());
            } else {
                throw BizException.of(ErrorCode.DUPLICATE_SUBMIT, "订单已创建，请勿重复提交");
            }
        }

        // 2. 抢占占位记录
        IdempotencyRecord record = new IdempotencyRecord();
        record.setId(idGenerator.nextId());
        record.setScene(idempotent.scene());
        record.setUserId(userId);
        record.setIdemKey(idemKey);
        record.setRequestHash(requestHash);
        record.setStatus(STATUS_PROCESSING);
        record.setCreatedAt(java.time.LocalDateTime.now());
        try {
            mapper.insert(record);
        } catch (DuplicateKeyException ex) {
            throw BizException.of(ErrorCode.DUPLICATE_SUBMIT, "订单已创建，请勿重复提交");
        }

        // 3. 执行业务
        Object result;
        try {
            result = joinPoint.proceed();
        } catch (Throwable ex) {
            // 业务失败：删除占位，允许同键重试
            safeDelete(record.getId());
            throw ex;
        }

        // 4. 回写结果
        try {
            IdempotencyRecord update = new IdempotencyRecord();
            update.setId(record.getId());
            update.setStatus(STATUS_DONE);
            update.setResponseJson(jsonMapper.writeValueAsString(result));
            mapper.updateById(update);
        } catch (Exception ex) {
            // 回写失败不影响主流程：下次同键请求会走「处理中」分支，由前端刷新兜底
            log.warn("幂等结果回写失败 scene={} userId={} err={}", idempotent.scene(), userId, ex.getMessage());
        }
        return result;
    }

    private IdempotencyRecord find(String scene, Long userId, String idemKey) {
        return mapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<IdempotencyRecord>()
                .eq(IdempotencyRecord::getScene, scene)
                .eq(IdempotencyRecord::getUserId, userId)
                .eq(IdempotencyRecord::getIdemKey, idemKey)
                .last("LIMIT 1"));
    }

    /** PROCESSING 占位是否已超时（进程崩溃残留的僵尸占位）。 */
    private boolean isStale(IdempotencyRecord record) {
        return record.getCreatedAt() != null
                && record.getCreatedAt().isBefore(java.time.LocalDateTime.now().minusMinutes(STALE_THRESHOLD_MS / 60000));
    }

    private void safeDelete(Long id) {
        try {
            mapper.deleteById(id);
        } catch (Exception ex) {
            log.warn("幂等占位清理失败 id={} err={}", id, ex.getMessage());
        }
    }

    private String resolveHeader(String headerName) {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        HttpServletRequest request = attributes.getRequest();
        return request.getHeader(headerName);
    }

    private String serializeArgs(ProceedingJoinPoint joinPoint) {
        try {
            return jsonMapper.writeValueAsString(joinPoint.getArgs());
        } catch (Exception ex) {
            return String.valueOf(java.util.Arrays.hashCode(joinPoint.getArgs()));
        }
    }

    private Class<?> returnTypeOf(ProceedingJoinPoint joinPoint) {
        return ((MethodSignature) joinPoint.getSignature()).getReturnType();
    }

    private Object deserialize(String json, Class<?> type) {
        try {
            return jsonMapper.readValue(json, type);
        } catch (Exception ex) {
            log.warn("幂等结果反序列化失败，按重复提交处理: {}", ex.getMessage());
            throw BizException.of(ErrorCode.DUPLICATE_SUBMIT, "订单已创建，请勿重复提交");
        }
    }
}
