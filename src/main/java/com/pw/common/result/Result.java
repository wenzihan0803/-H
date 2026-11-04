package com.pw.common.result;

import com.pw.common.exception.ErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import org.slf4j.MDC;

/**
 * 统一响应体。
 *
 * <p>口径来源：技术规范/04-接口规范与接口清单.md §1.1</p>
 * <pre>
 * { "code": 0, "message": "ok", "data": {...}, "traceId": "..." }
 * </pre>
 *
 * @param <T> 业务数据类型
 */
@Getter
@Schema(description = "统一响应")
public class Result<T> {

    @Schema(description = "0 成功；非 0 业务错误码", example = "0")
    private final int code;

    @Schema(description = "提示信息，错误时为「原因 + 下一步」", example = "ok")
    private final String message;

    @Schema(description = "业务数据")
    private final T data;

    @Schema(description = "链路追踪 ID，与请求头 X-Trace-Id 对应")
    private final String traceId;

    private Result(int code, String message, T data, String traceId) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.traceId = traceId;
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(ErrorCode.SUCCESS.getCode(), "ok", data, currentTraceId());
    }

    public static Result<Void> ok() {
        return new Result<>(ErrorCode.SUCCESS.getCode(), "ok", null, currentTraceId());
    }

    public static <T> Result<T> fail(ErrorCode errorCode) {
        return new Result<>(errorCode.getCode(), errorCode.getMessage(), null, currentTraceId());
    }

    public static <T> Result<T> fail(ErrorCode errorCode, String message) {
        return new Result<>(errorCode.getCode(), message, null, currentTraceId());
    }

    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null, currentTraceId());
    }

    /** 带数据的失败响应，用于幂等场景回传已有结果。 */
    public static <T> Result<T> fail(ErrorCode errorCode, String message, T data) {
        return new Result<>(errorCode.getCode(), message, data, currentTraceId());
    }

    private static String currentTraceId() {
        String traceId = MDC.get(TraceIdFilter.TRACE_ID);
        return traceId == null ? "" : traceId;
    }
}
