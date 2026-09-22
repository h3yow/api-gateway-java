package com.github.h3yow.apigatewayjava.gateway.web;

import com.github.h3yow.apigatewayjava.gateway.core.GatewayException;
import com.github.h3yow.apigatewayjava.gateway.model.ErrorResponse;
import com.github.h3yow.apigatewayjava.gateway.model.RejectReason;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一异常处理：所有错误（受控 {@link GatewayException} 与未预期兜底）都映射为
 * 相同的 {@link ErrorResponse} 结构，但 errorCode / HTTP 状态随原因可区分。
 * 429 场景额外下发 Retry-After 提示头。
 */
@RestControllerAdvice
public class GatewayExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayExceptionHandler.class);

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<ErrorResponse> handleGateway(GatewayException ex, HttpServletRequest request) {
        String traceId = traceId(request);
        ErrorResponse body = ErrorResponse.of(traceId, ex.reason(), ex.caller(), ex.version(),
                ex.getMessage(), ex.retryAfterMillis());
        return write(ex.reason(), ex.retryAfterMillis(), traceId, body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        String traceId = traceId(request);
        log.warn("gateway unexpected error traceId={} message={}", traceId, ex.toString());
        ErrorResponse body = ErrorResponse.of(traceId, RejectReason.DOWNSTREAM_ERROR,
                null, null, "网关内部错误: " + ex.getClass().getSimpleName(), 0L);
        return write(RejectReason.DOWNSTREAM_ERROR, 0L, traceId, body);
    }

    private ResponseEntity<ErrorResponse> write(RejectReason reason, long retryAfterMillis,
                                                String traceId, ErrorResponse body) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(reason.httpStatus())
                .header(TraceIdFilter.TRACE_ID_HEADER, traceId);
        if (reason.httpStatus() == HttpStatus.TOO_MANY_REQUESTS.value() && retryAfterMillis > 0) {
            // 向上取整到秒，符合 Retry-After 的 delta-seconds 形式
            long retryAfterSeconds = (retryAfterMillis + 999L) / 1000L;
            builder.header("Retry-After", String.valueOf(retryAfterSeconds));
        }
        return builder.body(body);
    }

    private String traceId(HttpServletRequest request) {
        Object value = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        return value == null ? "unknown" : value.toString();
    }
}
