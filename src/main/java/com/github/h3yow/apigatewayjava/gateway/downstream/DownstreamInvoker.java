package com.github.h3yow.apigatewayjava.gateway.downstream;

import com.github.h3yow.apigatewayjava.gateway.model.ApiVersion;
import com.github.h3yow.apigatewayjava.gateway.model.GatewayRequest;
import com.github.h3yow.apigatewayjava.gateway.model.GatewayResponse;

/**
 * 下游调用抽象。编排层只依赖该接口，便于在单测中替换为可控桩。
 */
public interface DownstreamInvoker {

    GatewayResponse invoke(GatewayRequest request, ApiVersion routedVersion);
}
