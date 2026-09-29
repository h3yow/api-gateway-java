package com.github.h3yow.apigatewayjava.gateway.model;

/**
 * 一次路由解析的结果。
 *
 * @param path            去网关前缀后的下游路径
 * @param method          HTTP 方法
 * @param declaredVersion 用户声明版本（可能为 null，表示未声明）
 * @param matched         是否命中已配置的接口组
 * @param group           命中的接口组配置；未命中时为 null
 */
public record Route(String path,
                    String method,
                    ApiVersion declaredVersion,
                    boolean matched,
                    InterfaceGroupConfig group) {
}
