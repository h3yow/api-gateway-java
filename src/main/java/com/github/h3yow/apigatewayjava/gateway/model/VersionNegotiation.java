package com.github.h3yow.apigatewayjava.gateway.model;

/**
 * 版本协商结论。
 *
 * @param caller       调用方标识
 * @param declared     请求声明版本（可能为 null，表示未声明）
 * @param resolved     协商后用于路由与配额维度的版本（拒绝时为 null）
 * @param allowed      是否通过兼容判定
 * @param basis        判定依据的人类可读描述（用于日志解释）
 * @param rejectReason 不通过时的拒绝原因；通过时为 null
 */
public record VersionNegotiation(String caller,
                                 ApiVersion declared,
                                 ApiVersion resolved,
                                 boolean allowed,
                                 String basis,
                                 RejectReason rejectReason) {
}
