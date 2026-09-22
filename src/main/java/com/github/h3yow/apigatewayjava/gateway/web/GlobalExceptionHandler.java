package com.github.h3yow.apigatewayjava.gateway.web;

import com.github.h3yow.apigatewayjava.gateway.error.ApiError;
import com.github.h3yow.apigatewayjava.gateway.error.GatewayErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 兜底异常处理。正常路径不会走到这里（GatewayService 已把下游异常消化为统一错误结构）；
 * 一旦到达，仍输出一致的 {@link ApiError} 结构并生成可追踪标识。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        String traceId = "gw-" + java.util.UUID.randomUUID();
        log.error("兜底异常处理 traceId={}", traceId, ex);
        ApiError error = new ApiError(
                GatewayErrorCode.INTERNAL_ERROR.name(),
                "unexpected error: " + ex.getClass().getSimpleName(),
                null, null, "INTERNAL", null, traceId);
        return ResponseEntity.status(GatewayErrorCode.INTERNAL_ERROR.httpStatus())
                .header("X-Trace-Id", traceId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(error);
    }
}
