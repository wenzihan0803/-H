package com.pw.common.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Jackson 序列化配置。
 *
 * <p>口径来源：技术规范/04 §1「ID 字符串雪花 ID，避免 JS 精度丢失」「时间 ISO 8601，+08:00」。</p>
 *
 * <ul>
 *   <li><b>Long → String</b>：所有 long/Long 字段输出为字符串，前端拿到的 ID 与金额不会丢精度；</li>
 *   <li><b>LocalDateTime → ISO8601 带偏移</b>：统一 {@code yyyy-MM-dd'T'HH:mm:ss.SSSXXX}。</li>
 * </ul>
 *
 * <p><b>为什么不能用 {@code LocalDateTimeSerializer(DATE_TIME)}</b>：
 * {@code DATE_TIME} 的 pattern 含时区偏移占位符 {@code XXX}，而 {@link LocalDateTime} 本身不带偏移量，
 * Jackson 自带的 {@code LocalDateTimeSerializer} 直接 {@code formatter.format(localDateTime)} 会抛
 * {@code UnsupportedTemporalTypeException: Unsupported field: OffsetSeconds}，
 * 导致<b>所有返回时间字段的接口 500</b>（订单详情、列表、提现记录等全是时间字段）。
 * 因此这里改用自定义序列化器：先按业务时区 {@link #ZONE} 附加偏移，再格式化。</p>
 */
@Configuration
public class JacksonConfig {

    /** 业务统一时区：Asia/Shanghai（与 datasource 的 serverTimezone 一致）。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 接口时间格式：ISO 8601 带毫秒与时区偏移，如 2026-10-28T20:00:00.000+08:00 */
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        return builder -> builder
                .modules(longModule(), timeModule())
                .simpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
    }

    /**
     * 供非 MVC 场景（Redis 会话序列化、幂等结果缓存、手动 JSON 处理）使用的 ObjectMapper，
     * 与 MVC 保持一致的 Long→String 与时间策略。
     *
     * <p><b>不调用 {@code findAndRegisterModules()}</b>：它会通过 SPI 再注册一个默认的
     * {@link JavaTimeModule}，用自带的 {@code LocalDateTimeSerializer} 覆盖本类的自定义序列化器，
     * 使上面的时区偏移修复失效。本项目只需要 Long 与 LocalDateTime 的定制，无需 SPI 发现。</p>
     */
    @Bean("jsonMapper")
    public ObjectMapper jsonMapper() {
        return new ObjectMapper()
                .registerModule(longModule())
                .registerModule(timeModule());
    }

    /** Long/long 一律输出字符串，避免 JS Number 精度丢失（04 §1）。 */
    private static SimpleModule longModule() {
        SimpleModule module = new SimpleModule();
        module.addSerializer(Long.class, ToStringSerializer.instance);
        module.addSerializer(Long.TYPE, ToStringSerializer.instance);
        return module;
    }

    /**
     * 时间模块：在 {@link JavaTimeModule} 基础上覆盖 {@link LocalDateTime} 的序列化/反序列化。
     *
     * <p>{@code addSerializer/addDeserializer} 会覆盖 JavaTimeModule 自带的 LocalDateTime 处理，
     * 其余 JSR-310 类型仍用默认实现。</p>
     */
    private static JavaTimeModule timeModule() {
        JavaTimeModule module = new JavaTimeModule();
        module.addSerializer(LocalDateTime.class, new OffsetLocalDateTimeSerializer());
        module.addDeserializer(LocalDateTime.class, new FlexibleLocalDateTimeDeserializer());
        return module;
    }

    /** LocalDateTime → 按固定时区附加偏移 → ISO8601 字符串（带 +08:00）。 */
    private static class OffsetLocalDateTimeSerializer extends JsonSerializer<LocalDateTime> {
        @Override
        public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider provider)
                throws IOException {
            if (value == null) {
                gen.writeNull();
                return;
            }
            // atZone 后得到 ZonedDateTime，带偏移量，XXX 才能正确格式化
            gen.writeString(value.atZone(ZONE).format(DATE_TIME));
        }
    }

    /**
     * 宽松反序列化：兼容带偏移（{@code 2026-10-28T20:00:00.000+08:00}）
     * 与不带偏移（{@code 2026-10-28T20:00:00}）两种输入。
     */
    private static class FlexibleLocalDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {
        @Override
        public LocalDateTime deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
            String text = p.getText();
            if (text == null || text.isBlank()) {
                return null;
            }
            String trimmed = text.trim();
            // 1) 带时区偏移：换算到业务时区后取本地时间
            try {
                return OffsetDateTime.parse(trimmed).atZoneSameInstant(ZONE).toLocalDateTime();
            } catch (DateTimeParseException ignore) {
                // 落到不带偏移的解析
            }
            // 2) 不带偏移的标准 ISO 本地时间
            try {
                return LocalDateTime.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            } catch (DateTimeParseException ignore) {
                throw new IOException("无法解析时间字段，需 ISO 8601 格式: " + trimmed);
            }
        }
    }
}
