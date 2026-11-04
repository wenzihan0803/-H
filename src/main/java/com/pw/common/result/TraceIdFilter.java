package com.pw.common.result;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 链路追踪过滤器。
 *
 * <p>口径来源：技术规范/04 §1「请求头 X-Trace-Id，响应回传」；01 §17「traceId 贯穿请求、回调、任务」。</p>
 *
 * <ul>
 *   <li>入站：优先复用调用方传入的 {@code X-Trace-Id}，没有则生成；</li>
 *   <li>写入 MDC 供日志 pattern {@code %X{traceId}} 使用；</li>
 *   <li>回写响应头，便于前端与客服排查；</li>
 *   <li>请求结束清理 MDC，防止线程池复用导致串号。</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID = "traceId";
    public static final String HEADER_TRACE_ID = "X-Trace-Id";

    private static final int MAX_TRACE_ID_LENGTH = 64;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = sanitize(request.getHeader(HEADER_TRACE_ID));
        if (traceId == null) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        MDC.put(TRACE_ID, traceId);
        response.setHeader(HEADER_TRACE_ID, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(TRACE_ID);
        }
    }

    /** 调用方传入的 traceId 会进日志与响应头，必须过滤控制字符并限长，避免日志注入。 */
    private static String sanitize(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_TRACE_ID_LENGTH) {
            return null;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            boolean legal = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_';
            if (!legal) {
                return null;
            }
        }
        return trimmed;
    }
}
