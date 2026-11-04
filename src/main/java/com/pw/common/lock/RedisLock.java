package com.pw.common.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Redis 分布式锁。
 *
 * <p>口径来源：技术规范/01 §14「抢单：Redis {@code SET NX PX} + DB 条件更新」。</p>
 *
 * <p><b>Redis 锁只是第一道闸门，不能替代数据库约束。</b>
 * 抢单场景必须同时依赖 {@code UPDATE biz_order SET status=? WHERE id=? AND status=?} 的条件更新——
 * 锁失效（超时、Redis 主从切换）时，DB 条件更新仍能保证只有一个打手成功。</p>
 *
 * <p>释放锁用 Lua 脚本比对 value，避免误删他人持有的锁。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisLock {

    /** 比对并删除：只有 value 匹配才删，防止误删其他持有者的锁。 */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redisTemplate;

    /**
     * 尝试加锁（不等待）。
     *
     * @param key      锁 key，建议格式 {@code lock:order:accept:{orderId}}
     * @param ttl      锁自动过期时间，必须大于业务最长耗时
     * @return 锁令牌，{@code null} 表示未抢到
     */
    public String tryLock(String key, Duration ttl) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, token, ttl);
        return Boolean.TRUE.equals(acquired) ? token : null;
    }

    /**
     * 自旋等待加锁。
     *
     * @param key       锁 key
     * @param ttl       锁过期时间
     * @param waitMs    最长等待毫秒数
     * @param retryMs   重试间隔毫秒数
     * @return 锁令牌，{@code null} 表示超时未获取
     */
    public String tryLock(String key, Duration ttl, long waitMs, long retryMs) {
        long deadline = System.currentTimeMillis() + waitMs;
        String token;
        do {
            token = tryLock(key, ttl);
            if (token != null) {
                return token;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(retryMs);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return null;
            }
        } while (System.currentTimeMillis() < deadline);
        return null;
    }

    /** 释放锁。只释放自己持有的那把。 */
    public boolean unlock(String key, String token) {
        if (token == null) {
            return false;
        }
        try {
            Long result = redisTemplate.execute(UNLOCK_SCRIPT, List.of(key), token);
            return result != null && result > 0;
        } catch (Exception ex) {
            log.warn("释放分布式锁异常 key={} err={}", key, ex.getMessage());
            return false;
        }
    }

    /**
     * 加锁执行业务，自动释放。未抢到锁时抛 {@link LockNotAcquiredException}，
     * 由调用方决定转成哪个业务错误码（如抢单失败提示「已被接单」）。
     */
    public <T> T executeWithLock(String key, Duration ttl, Supplier<T> action) {
        String token = tryLock(key, ttl);
        if (token == null) {
            throw new LockNotAcquiredException(key);
        }
        try {
            return action.get();
        } finally {
            unlock(key, token);
        }
    }

    /** 未获取到锁。 */
    public static class LockNotAcquiredException extends RuntimeException {
        public LockNotAcquiredException(String key) {
            super("未获取到分布式锁: " + key);
        }
    }
}
