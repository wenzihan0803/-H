package com.pw.common.lock;

import com.pw.common.exception.BizException;
import com.pw.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 简单限流器。
 *
 * <p>口径来源：技术规范/01 §17「登录/充值/提现限流」。</p>
 *
 * <p>实现：Redis INCR + 首次设置过期时间，固定窗口计数。
 * 对登录/充值/提现这类低频高价值操作足够；不追求滑动窗口的平滑度。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimiter {

    private final StringRedisTemplate redisTemplate;

    /**
     * 检查是否超限，超限抛 42901。
     *
     * @param key      限流 key，如 {@code ratelimit:login:{ip}}
     * @param limit    窗口内最大次数
     * @param window   窗口时长
     */
    public void check(String key, int limit, Duration window) {
        Long count;
        try {
            count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, window);
            }
        } catch (Exception ex) {
            // Redis 不可用时放行，不因限流组件故障阻断核心业务
            log.warn("限流器异常，本次放行 key={} err={}", key, ex.getMessage());
            return;
        }
        if (count != null && count > limit) {
            log.warn("触发限流 key={} count={} limit={}", key, count, limit);
            throw BizException.of(ErrorCode.TOO_MANY_REQUESTS, "操作过于频繁，请稍后重试");
        }
    }
}
