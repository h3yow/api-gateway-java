package com.github.h3yow.apigatewayjava.gateway.core;

import com.github.h3yow.apigatewayjava.gateway.model.ApiVersion;
import com.github.h3yow.apigatewayjava.gateway.model.RejectReason;

/**
 * 网关内部流转的受控异常，携带错误分类所需的全部上下文。
 */
public class GatewayException extends RuntimeException {

    private final RejectReason reason;
    private final String caller;
    private final ApiVersion version;
    private final long retryAfterMillis;

    public GatewayException(RejectReason reason, String caller, ApiVersion version,
                            long retryAfterMillis, String message) {
        this(reason, caller, version, retryAfterMillis, message, null);
    }

    public GatewayException(RejectReason reason, String caller, ApiVersion version,
                            long retryAfterMillis, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.caller = caller;
        this.version = version;
        this.retryAfterMillis = retryAfterMillis;
    }

    public RejectReason reason() {
        return reason;
    }

    public String caller() {
        return caller;
    }

    public ApiVersion version() {
        return version;
    }

    public long retryAfterMillis() {
        return retryAfterMillis;
    }
}
