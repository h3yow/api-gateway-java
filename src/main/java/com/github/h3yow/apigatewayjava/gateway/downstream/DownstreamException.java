package com.github.h3yow.apigatewayjava.gateway.downstream;

/**
 * 下游调用异常，携带失败分类；THROTTLED 时可携带 Retry-After 毫秒。
 */
public class DownstreamException extends RuntimeException {

    private final DownstreamFailure failure;
    private final int downstreamStatus;
    private final Long retryAfterMillis;

    public DownstreamException(DownstreamFailure failure, int downstreamStatus,
                               Long retryAfterMillis, String message, Throwable cause) {
        super(message, cause);
        this.failure = failure;
        this.downstreamStatus = downstreamStatus;
        this.retryAfterMillis = retryAfterMillis;
    }

    public DownstreamFailure failure() {
        return failure;
    }

    public int downstreamStatus() {
        return downstreamStatus;
    }

    public Long retryAfterMillis() {
        return retryAfterMillis;
    }
}
