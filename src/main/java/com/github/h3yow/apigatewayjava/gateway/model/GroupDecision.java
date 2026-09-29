package com.github.h3yow.apigatewayjava.gateway.model;

/**
 * 接口组维度判定结论（方法、状态、版本开放范围）。
 *
 * @param route        被判定的路由
 * @param allowed      是否放行
 * @param basis        可解释的判定依据
 * @param rejectReason 拒绝原因；放行时为 null
 */
public record GroupDecision(Route route,
                            boolean allowed,
                            String basis,
                            RejectReason rejectReason) {

    public static GroupDecision pass(Route route, String basis) {
        return new GroupDecision(route, true, basis, null);
    }

    public static GroupDecision reject(Route route, RejectReason reason, String basis) {
        return new GroupDecision(route, false, basis, reason);
    }
}
