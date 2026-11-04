package com.pw.common.idempotent;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * 幂等注解。
 *
 * <p>口径来源：技术规范/04 §1「创建订单 Idempotency-Key；充值/退款/提现用业务单号唯一」；
 * /01 §14 幂等与并发表。</p>
 *
 * <p>两类幂等，不要混用：</p>
 * <ul>
 *   <li><b>请求头幂等</b>（本注解 {@code headerMode=true}）：客户端生成 {@code Idempotency-Key}，
 *       服务端按 (scene, userId, key) 去重，重复请求返回首次结果。用于创建订单。</li>
 *   <li><b>业务单号幂等</b>：靠数据库唯一索引（如 {@code recharge_no}、{@code withdraw_no}、
 *       流水表 {@code (biz_type, biz_no)}）。用于回调、结算、提现——这些场景客户端不传幂等键。</li>
 * </ul>
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {

    /** 业务场景标识，与 idempotency_record.scene 对应，如 {@code CREATE_ORDER}。 */
    String scene();

    /** 幂等键来源的请求头名，默认 Idempotency-Key。 */
    String header() default "Idempotency-Key";

    /** 是否从请求头取幂等键。false 表示由业务单号唯一索引兜底，本注解只做并发闸门。 */
    boolean headerMode() default true;

    /** 结果缓存时长，默认 10 分钟。 */
    long ttl() default 10;

    TimeUnit ttlUnit() default TimeUnit.MINUTES;
}
