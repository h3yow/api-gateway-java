package com.github.h3yow.apigatewayjava.gateway.model;

/**
 * 接口组维度的准入判定结论（骨架定义）。
 *
 * @param groupId      命中的接口组
 * @param allowed      是否放行
 * @param rejectReason 拒绝原因；放行时为 null
 * @param basis        可解释的判定依据
 */
public record GroupAuthorization(String groupId,
                                 boolean allowed,
                                 RejectReason rejectReason,
                                 String basis) {
}
