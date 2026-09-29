package com.github.h3yow.apigatewayjava.gateway.model;

/**
 * 错误来源分类。对外结论必须能一眼分清：
 * <ul>
 *     <li>{@link #LOCAL}：请求尚未进入下游，就被网关本地规则拦下
 *         （头部缺失、版本协商失败、路由不存在/方法不支持、接口组暂停/未开放/灰度不放行/
 *                 版本未开放、本地配额耗尽、网关内部错误）；</li>
 *     <li>{@link #DOWNSTREAM}：请求已放行并进入下游后，由下游产生的失败
 *         （下游超时、下游限流、下游非成功状态）。</li>
 * </ul>
 */
public enum ErrorSource {
    LOCAL,
    DOWNSTREAM
}
