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
    private final String group;
    private final long retryAfterMillis;

    public GatewayException(RejectReason reason, String caller, ApiVersion version,
                            long retryAfterMillis, String message) {
        this(reason, caller, version, null, retryAfterMillis, message, null);
    }

    public GatewayException(RejectReason reason, String caller, ApiVersion version, String group,
                            long retryAfterMillis, String message) {
        this(reason, caller, version, group, retryAfterMillis, message, null);
    }

    public GatewayException(RejectReason reason, String caller, ApiVersion version,
                            long retryAfterMillis, String message, Throwable cause) {
        this(reason, caller, version, null, retryAfterMillis, message, cause);
    }

    public GatewayException(RejectReason reason, String caller, ApiVersion version, String group,
                            long retryAfterMillis, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.caller = caller;
        this.version = version;
        this.group = group;
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

    public String group() {
        return group;
    }

    public long retryAfterMillis() {
        return retryAfterMillis;
    }
}
