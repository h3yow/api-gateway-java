package com.github.h3yow.apigatewayjava.gateway.group;

import com.github.h3yow.apigatewayjava.gateway.model.RejectReason;
import com.github.h3yow.apigatewayjava.gateway.model.Route;

/**
 * 路由注册表（本地状态）。
 *
 * 对下游路径做精确匹配，并区分两种本地错误（都发生在请求进入下游之前）：
 * <ul>
 *     <li>路径未注册 → {@link RejectReason#ROUTE_NOT_FOUND}（404）；</li>
 *     <li>路径存在但 HTTP 方法不被该路由允许 → {@link RejectReason#METHOD_NOT_ALLOWED}（405）。</li>
 * </ul>
 * 注意：路径不存在不会被转发到下游碰运气，因此绝不会伪装成下游 404/故障。
 */
public class RouteRegistry {

    private final ApiGroupConfig config;

    public RouteRegistry(ApiGroupConfig config) {
        this.config = config;
    }

    public Resolution resolve(String method, String downstreamPath) {
        Route route = config.route(downstreamPath);
        if (route == null) {
            return new Resolution(null, RejectReason.ROUTE_NOT_FOUND,
                    "下游路径 " + downstreamPath + " 未在网关注册任何路由；本地拒绝(404)，不转发下游");
        }
        if (route.allowedMethods().stream().noneMatch(m -> m.equalsIgnoreCase(method))) {
            return new Resolution(route, RejectReason.METHOD_NOT_ALLOWED,
                    "路由 " + downstreamPath + " 仅允许方法 " + route.allowedMethods()
                            + "，收到 " + method + "；本地拒绝(405)");
        }
        return new Resolution(route, null,
                "命中路由 " + downstreamPath + " → 接口组 " + route.groupId());
    }

    /**
     * @param route        命中的路由；路径不存在时为 null
     * @param rejectReason 本地拒绝原因；命中时为 null
     * @param basis        判定依据
     */
    public record Resolution(Route route, RejectReason rejectReason, String basis) {

        public boolean matched() {
            return route != null && rejectReason == null;
        }
    }
}
