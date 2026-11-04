package com.pw.common.util;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 业务主键与单号生成。
 *
 * <p>口径来源：技术规范/05 §1「主键 bigint，雪花 ID，应用生成；对外字符串」。</p>
 *
 * <p>单号格式（见 05 各表字段长度）：</p>
 * <ul>
 *   <li>订单号 {@code order_no} varchar(32)：{@code PW + yyyyMMdd + 8 位序列}，如 {@code PW2026102800000001}；</li>
 *   <li>充值单 {@code recharge_no} varchar(40)：{@code RC + yyyyMMddHHmmss + 8 位随机}；</li>
 *   <li>退款单 {@code refund_no} varchar(40)：{@code RF + yyyyMMddHHmmss + 8 位随机}；</li>
 *   <li>提现单 {@code withdraw_no} varchar(40)：{@code WD + yyyyMMddHHmmss + 8 位随机}。</li>
 * </ul>
 *
 * <p>单号只要求「唯一 + 可读 + 可排序」，不承载业务语义；唯一性由数据库唯一索引兜底。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdGenerator {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 订单日序列的 Redis key 前缀：跨进程、跨重启全局自增。 */
    private static final String ORDER_SEQ_PREFIX = "pw:order:seq:";
    /** 序列 key 保留 2 天，跨天自动过期。 */
    private static final Duration ORDER_SEQ_TTL = Duration.ofDays(2);
    /** 新序列起点下限：远高于历史上任何一天的订单量，避免 Redis 清空后从 1 重新撞号。 */
    private static final long ORDER_SEQ_FLOOR = 10_000_000L;

    private final StringRedisTemplate redisTemplate;

    /** 雪花 ID。MyBatis-Plus 的 IdWorker 内部维护 workerId/datacenterId。 */
    public long nextId() {
        return IdWorker.getId();
    }

    /** 雪花 ID 字符串形式。对外一律用字符串，避免 JS 精度丢失。 */
    public String nextIdStr() {
        return String.valueOf(IdWorker.getId());
    }

    /**
     * 订单号：PW + yyyyMMdd + 8 位序列。
     *
     * <p>示例：{@code PW2026102800000001}（18 位，在 varchar(32) 内）。</p>
     */
    public String nextOrderNo() {
        String date = LocalDate.now().format(DATE);
        long seq = nextOrderSequence(date) % 100_000_000L;
        return "PW" + date + String.format("%08d", seq);
    }

    /**
     * 取当日订单序列号。
     *
     * <p><b>为什么不用进程内计数器</b>：原实现用 {@code AtomicInteger} 从 0 起，进程一重启就回到 1，
     * 与当天已存在的 {@code PWyyyyMMdd00000001} 相撞，命中 {@code biz_order.uk_order_no}
     * 导致重启后第一单直接失败。改用 Redis {@code INCR} 后，多实例、重启都不会回退。</p>
     *
     * <p>key 首次创建时写入一个不低于 {@link #ORDER_SEQ_FLOOR} 的起点（秒级时间戳取模），
     * 保证即使 Redis 被清空，新序列也不会退回到 1 与历史单号冲突。</p>
     *
     * <p>Redis 不可用时降级为随机 8 位序号，仍由唯一索引兜底。</p>
     */
    private long nextOrderSequence(String date) {
        String key = ORDER_SEQ_PREFIX + date;
        try {
            long seed = Math.max(ORDER_SEQ_FLOOR,
                    Math.floorMod(System.currentTimeMillis() / 1000L, 90_000_000L));
            redisTemplate.opsForValue().setIfAbsent(key, String.valueOf(seed), ORDER_SEQ_TTL);
            Long seq = redisTemplate.opsForValue().increment(key);
            if (seq != null && seq > 0 && seq < 100_000_000L) {
                return seq;
            }
            log.warn("订单号日序列已越界或不可用，降级为随机序号 key={} seq={}", key, seq);
        } catch (RuntimeException ex) {
            log.warn("订单号日序列 Redis 不可用，降级为随机序号：{}", ex.getMessage());
        }
        return ThreadLocalRandom.current().nextLong(1, 100_000_000L);
    }

    /** 充值单号：RC + yyyyMMddHHmmss + 8 位随机。 */
    public String nextRechargeNo() {
        return "RC" + timestamp() + random8();
    }

    /** 退款单号：RF + yyyyMMddHHmmss + 8 位随机。 */
    public String nextRefundNo() {
        return "RF" + timestamp() + random8();
    }

    /** 提现单号：WD + yyyyMMddHHmmss + 8 位随机。 */
    public String nextWithdrawNo() {
        return "WD" + timestamp() + random8();
    }

    /** 会话 ID / 幂等键等通用短随机串。 */
    public String randomToken(int length) {
        StringBuilder sb = new StringBuilder(length);
        String pool = "abcdefghijklmnopqrstuvwxyz0123456789";
        for (int i = 0; i < length; i++) {
            sb.append(pool.charAt(ThreadLocalRandom.current().nextInt(pool.length())));
        }
        return sb.toString();
    }

    /** 邀请码：8 位大写字母数字，去掉易混字符 0/O/1/I。 */
    public String nextInviteCode() {
        String pool = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(pool.charAt(ThreadLocalRandom.current().nextInt(pool.length())));
        }
        return sb.toString();
    }

    private static String timestamp() {
        return LocalDateTime.now().format(DATETIME);
    }

    private static String random8() {
        return String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
    }
}
