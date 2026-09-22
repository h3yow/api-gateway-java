package com.github.h3yow.apigatewayjava.gateway.downstream;

import com.github.h3yow.apigatewayjava.gateway.model.RejectReason;

/**
 * 下游调用异常：统一携带可区分原因（超时 / 下游限流 / 下游错误）。
 */
public class DownstreamException extends RuntimeException {

    private final RejectReason reason;

    public DownstreamException(RejectReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public DownstreamException(RejectReason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public RejectReason reason() {
        return reason;
    }
}
