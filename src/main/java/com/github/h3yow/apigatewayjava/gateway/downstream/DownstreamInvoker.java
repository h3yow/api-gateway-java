package com.github.h3yow.apigatewayjava.gateway.downstream;

/**
 * 下游调用抽象，便于测试以确定性替身替换真实 HTTP 调用。
 */
public interface DownstreamInvoker {

    DownstreamResponse invoke(String path, String traceId);
}
