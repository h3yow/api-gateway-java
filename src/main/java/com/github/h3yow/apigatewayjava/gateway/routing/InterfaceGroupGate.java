package com.github.h3yow.apigatewayjava.gateway.routing;

import com.github.h3yow.apigatewayjava.gateway.model.GroupDecision;
import com.github.h3yow.apigatewayjava.gateway.model.InterfaceGroupConfig;
import com.github.h3yow.apigatewayjava.gateway.model.InterfaceStatus;
import com.github.h3yow.apigatewayjava.gateway.model.RejectReason;
import com.github.h3yow.apigatewayjava.gateway.model.Route;

import java.util.Set;

/**
 * 接口组维度判定（纯函数，相同输入结论稳定）。拆成两段，供编排层按序调用：
 * <ol>
 *     <li>{@link #checkAccess}：HTTP 方法是否支持；上线状态 PAUSED / NOT_OPEN / GRAY 白名单；</li>
 *     <li>版本协商（调用方层，由 VersionNegotiator 完成）之后再调用 {@link #checkVersion}，
 *         判定“用户声明版本”是否在该接口组开放白名单内。</li>
 * </ol>
 * 关键约束：版本开放判定只看用户声明的版本，绝不替换成系统默认版本，也不自动套用默认接口策略。
 */
public class InterfaceGroupGate {

    /** 接口组未显式配置允许方法时的默认集合。 */
    private static final Set<String> DEFAULT_METHODS = Set.of("POST");

    /**
     * 方法与上线状态判定。未命中接口组时直接放行（路由命中与否由编排层先判定）。
     */
    public GroupDecision checkAccess(Route route, String caller) {
        if (!route.matched() || route.group() == null) {
            return GroupDecision.pass(route, "未命中接口组，跳过接口组判定");
        }
        InterfaceGroupConfig g = route.group();
        String who = "接口组=" + g.id() + " 调用方=" + caller + " 方法=" + route.method();

        Set<String> allowedMethods = g.allowedMethods() == null ? DEFAULT_METHODS : g.allowedMethods();
        if (!allowedMethods.contains(route.method())) {
            return GroupDecision.reject(route, RejectReason.METHOD_NOT_ALLOWED,
                    who + " 命中接口组，但方法 " + route.method() + " 不在允许集合 "
                            + allowedMethods + "，本地拒绝（不进入下游）");
        }

        switch (g.status()) {
            case PAUSED -> {
                return GroupDecision.reject(route, RejectReason.INTERFACE_PAUSED,
                        who + " 接口组处于暂停状态 PAUSED，暂不提供服务，本地拒绝（不进入下游）");
            }
            case NOT_OPEN -> {
                return GroupDecision.reject(route, RejectReason.INTERFACE_NOT_OPEN,
                        who + " 接口组尚未开放 NOT_OPEN，本地拒绝（不进入下游）");
            }
            case GRAY -> {
                if (!g.grayCallers().contains(caller)) {
                    return GroupDecision.reject(route, RejectReason.INTERFACE_GRAY_NOT_ALLOWED,
                            who + " 接口组处于灰度 GRAY，灰度白名单=" + g.grayCallers()
                                    + "，该调用方不在灰度范围内，本地拒绝（不进入下游）");
                }
                return GroupDecision.pass(route,
                        who + " 接口组处于灰度 GRAY 且调用方命中灰度白名单，状态判定通过");
            }
            case ACTIVE -> {
                return GroupDecision.pass(route, who + " 接口组状态 ACTIVE，状态判定通过");
            }
        }
        throw new IllegalStateException("未知接口组状态: " + g.status());
    }

    /**
     * 接口组版本开放范围判定。必须在调用方层版本协商通过后调用：
     * 此时若 declared 非空，则它必然是系统支持且调用方被允许的版本，但还可能未对该接口组开放。
     * declared 为 null（未声明）时直接放行，由调用方版本协商层负责报 MISSING_VERSION。
     */
    public GroupDecision checkVersion(Route route) {
        if (!route.matched() || route.group() == null) {
            return GroupDecision.pass(route, "未命中接口组，跳过接口组版本判定");
        }
        InterfaceGroupConfig g = route.group();
        if (route.declaredVersion() == null) {
            return GroupDecision.pass(route,
                    "接口组=" + g.id() + " 未声明版本，接口组版本判定放行，交由版本协商层处理");
        }
        if (!g.openVersions().contains(route.declaredVersion())) {
            return GroupDecision.reject(route, RejectReason.INTERFACE_VERSION_NOT_OPEN,
                    "接口组=" + g.id() + " 调用方 声明版本=" + route.declaredVersion().httpValue()
                            + " 不在接口组开放版本 " + g.openVersions()
                            + " 内，按声明版本拒绝（不替换为默认版本、不套用默认接口策略）");
        }
        return GroupDecision.pass(route,
                "接口组=" + g.id() + " 声明版本 " + route.declaredVersion().httpValue()
                        + " 在开放集合 " + g.openVersions() + " 内，接口组版本判定通过");
    }
}
