package com.github.h3yow.apigatewayjava.gateway.model;

import java.util.List;

/**
 * 一条下游路由的注册信息（骨架定义）。
 *
 * @param downstreamPath 下游路径（网关去掉 /gateway 前缀后的路径）
 * @param groupId        所属接口组
 * @param allowedMethods 允许的 HTTP 方法
 */
public record Route(String downstreamPath, String groupId, List<String> allowedMethods) {
}
