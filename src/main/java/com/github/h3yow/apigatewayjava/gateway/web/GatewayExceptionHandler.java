package com.github.h3yow.apigatewayjava.gateway.web;

import com.github.h3yow.apigatewayjava.gateway.core.GatewayException;
import com.github.h3yow.apigatewayjava.gateway.model.ErrorResponse;
import com.github.h3yow.apigatewayjava.gateway.model.RejectReason;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一异常处理：所有错误（受控 {@link GatewayException} 与未预期兜底）都映射为
 * 相同的 {@link ErrorResponse} 结构，但 errorCode / HTTP / source 随原因可区分。
 * <ul>
 *     <li>网关自身未预期异常归类为 LOCAL 500 GATEWAY_INTERNAL_ERROR，不再伪装成下游故障；</li>
 *     <li>仅本地配额限流（GATEWAY_RATE_LIMITED 且 retryAfterMillis&gt;0）下发 Retry-After；
 *         下游 429 不带 Retry-After，两种 429 由此可进一步区分。</li>
 * </ul>
 */
@RestControllerAdvice
public class GatewayExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayExceptionHandler.class);

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<ErrorResponse> handleGateway(GatewayException ex, HttpServletRequest request) {
        String traceId = traceId(request);
        ErrorResponse body = ErrorResponse.of(traceId, ex.reason(), ex.caller(), ex.version(), ex.group(),
                ex.getMessage(), ex.retryAfterMillis());
        return write(ex.reason(), ex.retryAfterMillis(), traceId, body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        String traceId = traceId(request);
        log.warn("gateway unexpected local error traceId={} message={}", traceId, ex.toString());
        ErrorResponse body = ErrorResponse.of(traceId, RejectReason.INTERNAL_ERROR,
                null, null, null, "网关本地内部错误: " + ex.getClass().getSimpleName(), 0L);
        return write(RejectReason.INTERNAL_ERROR, 0L, traceId, body);
    }

    private ResponseEntity<ErrorResponse> write(RejectReason reason, long retryAfterMillis,
                                                String traceId, ErrorResponse body) {
        // X-Trace-Id 响应头由 TraceIdFilter 唯一写入（成功/失败响应都有），这里绝不再写，
        // 否则同名头会被追加成两个。traceId 仅用于错误体，保证与响应头同源一致。
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(reason.httpStatus());
        if (reason == RejectReason.RATE_LIMITED_QUOTA_EXHAUSTED && retryAfterMillis > 0) {
            // 仅本地配额限流给出 Retry-After（delta-seconds，向上取整）
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
