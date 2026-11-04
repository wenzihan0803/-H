package com.pw.common.exception;

import com.pw.common.result.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理。
 *
 * <p>约束（技术规范/01 §1、/04 §1.1）：</p>
 * <ul>
 *   <li>HTTP 状态码表达协议层，业务错误用 {@code code}；</li>
 *   <li>错误提示必须是「原因 + 下一步」；</li>
 *   <li>生产环境不返回堆栈。</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：预期分支，WARN 级、不打印堆栈。 */
    @ExceptionHandler(BizException.class)
    public org.springframework.http.ResponseEntity<Result<Void>> handleBiz(BizException ex, HttpServletRequest request) {
        ErrorCode errorCode = ex.getErrorCode();
        log.warn("业务异常 uri={} code={} message={}", request.getRequestURI(), errorCode.getCode(), ex.getMessage());
        return build(errorCode, ex.getMessage());
    }

    /** @Valid 校验失败（RequestBody）。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public org.springframework.http.ResponseEntity<Result<Void>> handleValid(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::fieldMessage)
                .collect(Collectors.joining("；"));
        return build(ErrorCode.PARAM_ERROR, msg.isEmpty() ? ErrorCode.PARAM_ERROR.getMessage() : msg);
    }

    /** 表单/查询参数绑定失败。 */
    @ExceptionHandler(BindException.class)
    public org.springframework.http.ResponseEntity<Result<Void>> handleBind(BindException ex) {
        String msg = ex.getFieldErrors().stream()
                .map(GlobalExceptionHandler::fieldMessage)
                .collect(Collectors.joining("；"));
        return build(ErrorCode.PARAM_ERROR, msg.isEmpty() ? ErrorCode.PARAM_ERROR.getMessage() : msg);
    }

    /** 方法级 @Validated 校验失败。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public org.springframework.http.ResponseEntity<Result<Void>> handleConstraint(ConstraintViolationException ex) {
        String msg = ex.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("；"));
        return build(ErrorCode.PARAM_ERROR, msg.isEmpty() ? ErrorCode.PARAM_ERROR.getMessage() : msg);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public org.springframework.http.ResponseEntity<Result<Void>> handleMissingParam(MissingServletRequestParameterException ex) {
        return build(ErrorCode.PARAM_ERROR, "缺少必填参数「" + ex.getParameterName() + "」，请补全后重试");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public org.springframework.http.ResponseEntity<Result<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return build(ErrorCode.PARAM_ERROR, "参数「" + ex.getName() + "」格式有误，请检查后重试");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public org.springframework.http.ResponseEntity<Result<Void>> handleUnreadable(HttpMessageNotReadableException ex) {
        log.warn("请求体解析失败: {}", ex.getMessage());
        return build(ErrorCode.PARAM_ERROR, "请求内容格式有误，请检查后重新提交");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public org.springframework.http.ResponseEntity<Result<Void>> handleUploadSize(MaxUploadSizeExceededException ex) {
        return build(ErrorCode.PARAM_ERROR, "文件超过 10MB 限制，请压缩后重新上传");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public org.springframework.http.ResponseEntity<Result<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return build(ErrorCode.PARAM_ERROR, "请求方式不支持，请检查后重试");
    }

    @ExceptionHandler({NoHandlerFoundException.class})
    public org.springframework.http.ResponseEntity<Result<Void>> handleNoHandler(NoHandlerFoundException ex) {
        return build(ErrorCode.NOT_FOUND, "接口不存在，请检查地址后重试");
    }

    /**
     * 唯一键冲突。资金类表（流水、托管金、结算）依赖唯一索引兜底并发，
     * 这里统一转成「重复提交」，由调用方回查已有记录返回同一结果。
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public org.springframework.http.ResponseEntity<Result<Void>> handleDuplicateKey(DuplicateKeyException ex,
                                                                                   HttpServletRequest request) {
        log.warn("唯一键冲突 uri={} message={}", request.getRequestURI(), ex.getMessage());
        return build(ErrorCode.DUPLICATE_SUBMIT, ErrorCode.DUPLICATE_SUBMIT.getMessage());
    }

    /** Spring Security：未认证。 */
    @ExceptionHandler(AuthenticationException.class)
    public org.springframework.http.ResponseEntity<Result<Void>> handleAuth(AuthenticationException ex) {
        log.warn("认证失败: {}", ex.getMessage());
        return build(ErrorCode.UNAUTHORIZED, ErrorCode.UNAUTHORIZED.getMessage());
    }

    /** Spring Security：已认证但无权限。 */
    @ExceptionHandler(AccessDeniedException.class)
    public org.springframework.http.ResponseEntity<Result<Void>> handleAccessDenied(AccessDeniedException ex,
                                                                                  HttpServletRequest request) {
        log.warn("权限不足 uri={}", request.getRequestURI());
        return build(ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.getMessage());
    }

    /** 兜底：未预期异常，ERROR 级并打印完整堆栈，对外只暴露通用提示。 */
    @ExceptionHandler(Exception.class)
    public org.springframework.http.ResponseEntity<Result<Void>> handleUnknown(Exception ex, HttpServletRequest request) {
        log.error("系统异常 uri={}", request.getRequestURI(), ex);
        return build(ErrorCode.SYSTEM_ERROR, ErrorCode.SYSTEM_ERROR.getMessage());
    }

    private static String fieldMessage(FieldError error) {
        String field = error.getField();
        String msg = error.getDefaultMessage();
        if (field == null || field.isBlank()) {
            return msg == null ? "" : msg;
        }
        return msg == null ? field : field + " " + msg;
    }

    private static org.springframework.http.ResponseEntity<Result<Void>> build(ErrorCode errorCode, String message) {
        return org.springframework.http.ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(Result.fail(errorCode, message));
    }
}
