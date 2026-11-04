package com.pw.common.exception;

import lombok.Getter;

/**
 * 业务异常。Service 层校验失败时抛出，由 {@link GlobalExceptionHandler} 统一转换为 {@code Result}。
 *
 * <p>不继承 {@code RuntimeException} 的堆栈填充以节省性能开销：业务异常属于预期分支。</p>
 */
@Getter
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public static BizException of(ErrorCode errorCode) {
        return new BizException(errorCode);
    }

    public static BizException of(ErrorCode errorCode, String message) {
        return new BizException(errorCode, message);
    }

    /** 条件不成立时抛出。 */
    public static void throwIf(boolean condition, ErrorCode errorCode) {
        if (condition) {
            throw new BizException(errorCode);
        }
    }

    public static void throwIf(boolean condition, ErrorCode errorCode, String message) {
        if (condition) {
            throw new BizException(errorCode, message);
        }
    }

    /** 断言成立，否则抛出。 */
    public static void assertTrue(boolean condition, ErrorCode errorCode, String message) {
        if (!condition) {
            throw new BizException(errorCode, message);
        }
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        // 业务异常为预期控制流，跳过堆栈填充以降低开销；排查时可临时注释本方法
        return this;
    }
}
