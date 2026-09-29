package com.github.h3yow.apigatewayjava.gateway.model;

/**
 * 错误来源分类。对外结论必须能一眼分清：
 * <ul>
 *     <li>{@link #LOCAL}：请求尚未进入下游，由网关本地规则拦下
 *         （缺头、版本协商、接口组状态、配额耗尽、路由不存在、方法不支持、网关自身异常）；</li>
 *     <li>{@link #DOWNSTREAM}：请求已放行并实际调用下游，由下游侧失败
 *         （下游超时、下游限流、下游业务异常）。</li>
 * </ul>
 */
public enum ErrorSource {
    LOCAL,
    DOWNSTREAM
}
