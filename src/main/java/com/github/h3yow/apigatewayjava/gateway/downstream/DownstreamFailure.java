package com.github.h3yow.apigatewayjava.gateway.downstream;

/**
 * 下游失败分类：超时、I/O 异常、429 限流、5xx 服务错误。
 */
public enum DownstreamFailure {
    TIMEOUT,
    IO_ERROR,
    THROTTLED,
    SERVER_ERROR
}
