package com.github.h3yow.apigatewayjava.gateway.routing;

import com.github.h3yow.apigatewayjava.gateway.model.GroupDecision;
import com.github.h3yow.apigatewayjava.gateway.model.InterfaceGroupConfig;
import com.github.h3yow.apigatewayjava.gateway.model.InterfaceStatus;
import com.github.h3yow.apigatewayjava.gateway.model.RejectReason;
import com.github.h3yow.apigatewayjava.gateway.model.Route;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import com.github.h3yow.apigatewayjava.gateway.model.ApiVersion;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接口组判定单测：方法不支持、暂停、未开放、灰度白名单、版本开放范围；
 * 不同拒绝原因必须可区分，且判定为纯函数、结论稳定。
 */
class InterfaceGroupGateTest {

    private static final ApiVersion V10 = new ApiVersion(1, 0);
    private static final ApiVersion V11 = new ApiVersion(1, 1);

    private final InterfaceGroupGate gate = new InterfaceGroupGate();

    private Route route(InterfaceGroupConfig g, ApiVersion declared) {
        return new Route("/api/x", "POST", declared, true, g);
    }

    private InterfaceGroupConfig cfg(InterfaceStatus status, List<ApiVersion> open, Set<String> gray) {
        return new InterfaceGroupConfig("g", status, open, gray, null, null);
    }

    @Test
    void activeAllowsOpenVersion() {
        GroupDecision d = gate.checkAccess(route(cfg(InterfaceStatus.ACTIVE, List.of(V10, V11), Set.of()), V10),
                "anyone");
        assertTrue(d.allowed());
        GroupDecision v = gate.checkVersion(route(cfg(InterfaceStatus.ACTIVE, List.of(V10, V11), Set.of()), V10));
        assertTrue(v.allowed());
    }

    @Test
    void pausedRejectsEveryoneWithDistinctReason() {
        GroupDecision d = gate.checkAccess(route(cfg(InterfaceStatus.PAUSED, List.of(V10), Set.of()), V10),
                "caller-a");
        assertFalse(d.allowed());
        assertEquals(RejectReason.INTERFACE_PAUSED, d.rejectReason());
    }

    @Test
    void notOpenRejectsEveryoneWithDistinctReason() {
        GroupDecision d = gate.checkAccess(route(cfg(InterfaceStatus.NOT_OPEN, List.of(V11), Set.of()), V11),
                "caller-a");
        assertFalse(d.allowed());
        assertEquals(RejectReason.INTERFACE_NOT_OPEN, d.rejectReason());
    }

    @Test
    void grayOnlyAllowsWhitelistedCallers() {
        InterfaceGroupConfig g = cfg(InterfaceStatus.GRAY, List.of(V11), Set.of("caller-a"));
        assertTrue(gate.checkAccess(route(g, V11), "caller-a").allowed());
        GroupDecision denied = gate.checkAccess(route(g, V11), "caller-b");
        assertFalse(denied.allowed());
        assertEquals(RejectReason.INTERFACE_GRAY_NOT_ALLOWED, denied.rejectReason());
    }

    @Test
    void methodNotAllowedIsDistinctLocalError() {
        InterfaceGroupConfig g = new InterfaceGroupConfig("g", InterfaceStatus.ACTIVE,
                List.of(V10, V11), Set.of(), Set.of("POST"), null);
        Route getRoute = new Route("/api/x", "GET", V10, true, g);
        GroupDecision d = gate.checkAccess(getRoute, "caller-a");
        assertFalse(d.allowed());
        assertEquals(RejectReason.METHOD_NOT_ALLOWED, d.rejectReason());
    }

    @Test
    void declaredVersionNotOpenForGroupIsRejected_noSilentSubstitution() {
        // 组仅开放 v1.0，用户声明 v1.1（系统支持、调用方也可能被允许），仍必须按声明版本拒绝
        GroupDecision d = gate.checkVersion(route(cfg(InterfaceStatus.ACTIVE, List.of(V10), Set.of()), V11));
        assertFalse(d.allowed());
        assertEquals(RejectReason.INTERFACE_VERSION_NOT_OPEN, d.rejectReason());
        assertTrue(d.basis().contains("不替换为默认版本"));
    }

    @Test
    void missingVersionSkipsGroupVersionGate_handledByNegotiator() {
        GroupDecision d = gate.checkVersion(route(cfg(InterfaceStatus.ACTIVE, List.of(V10), Set.of()), null));
        assertTrue(d.allowed());
    }

    @Test
    void decisionsAreStableForSameInput() {
        InterfaceGroupConfig g = cfg(InterfaceStatus.GRAY, List.of(V11), Set.of("caller-a"));
        for (int i = 0; i < 50; i++) {
            assertTrue(gate.checkAccess(route(g, V11), "caller-a").allowed());
            assertFalse(gate.checkAccess(route(g, V11), "caller-b").allowed());
        }
    }
}
