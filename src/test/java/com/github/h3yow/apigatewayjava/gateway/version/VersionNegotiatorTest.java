package com.github.h3yow.apigatewayjava.gateway.version;

import com.github.h3yow.apigatewayjava.gateway.model.ApiVersion;
import com.github.h3yow.apigatewayjava.gateway.model.VersionNegotiation;
import com.github.h3yow.apigatewayjava.gateway.model.VersionPolicy;
import com.github.h3yow.apigatewayjava.gateway.model.RejectReason;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 版本协商单测：显式白名单、无静默回落、未登记调用方走默认策略、判定稳定。
 */
class VersionNegotiatorTest {

    private static final ApiVersion V10 = new ApiVersion(1, 0);
    private static final ApiVersion V11 = new ApiVersion(1, 1);
    private static final ApiVersion V20 = new ApiVersion(2, 0);

    private final VersionNegotiator negotiator = VersionNegotiator.defaultLocalNegotiator();

    private static void logOutcome(VersionNegotiation n) {
        System.out.printf("[VersionTest] caller=%s declared=%s resolved=%s allowed=%s reason=%s basis=%s%n",
                n.caller(),
                n.declared() == null ? "none" : n.declared().httpValue(),
                n.resolved() == null ? "none" : n.resolved().httpValue(),
                n.allowed(),
                n.rejectReason(),
                n.basis());
    }

    @Test
    void allowedCombinationsAreAcceptedAndStable() {
        for (var tuple : List.of(new Object[]{"caller-a", V10}, new Object[]{"caller-a", V11},
                new Object[]{"caller-b", V10})) {
            String caller = (String) tuple[0];
            ApiVersion v = (ApiVersion) tuple[1];
            VersionNegotiation n = negotiator.negotiate(caller, v);
            logOutcome(n);
            assertTrue(n.allowed());
            assertEquals(v, n.resolved(), "放行版本必须就是声明版本，禁止替换/回落");
        }

        // 稳定性：同样输入连续 100 次判定结论一致
        for (int i = 0; i < 100; i++) {
            VersionNegotiation n = negotiator.negotiate("caller-a", V11);
            assertTrue(n.allowed());
            assertEquals(V11, n.resolved());
        }
    }

    @Test
    void missingVersionIsRejected_noSilentFallback() {
        VersionNegotiation n = negotiator.negotiate("caller-a", null);
        logOutcome(n);
        assertFalse(n.allowed());
        assertEquals(RejectReason.MISSING_VERSION, n.rejectReason());
        assertNull(n.resolved());
        assertTrue(n.basis().contains("不回落默认版本"));
    }

    @Test
    void unsupportedSystemVersionIsRejected() {
        // v2.0 不在系统支持集合 {v1.0,v1.1}
        VersionNegotiation n = negotiator.negotiate("caller-a", V20);
        logOutcome(n);
        assertFalse(n.allowed());
        assertEquals(RejectReason.UNKNOWN_VERSION, n.rejectReason());
        assertNull(n.resolved());
    }

    @Test
    void callerIncompatibleVersionIsRejected() {
        // caller-b 仅允许 v1.0，请求 v1.1 必须被拒绝
        VersionNegotiation n = negotiator.negotiate("caller-b", V11);
        logOutcome(n);
        assertFalse(n.allowed());
        assertEquals(RejectReason.CALLER_VERSION_NOT_ALLOWED, n.rejectReason());
        assertNull(n.resolved());
    }

    @Test
    void unknownCallerUsesDefaultWhitelist_notOpenAccess() {
        // 未登记调用方：默认白名单仅 v1.0
        VersionNegotiation ok = negotiator.negotiate("stranger", V10);
        VersionNegotiation bad = negotiator.negotiate("stranger", V11);
        logOutcome(ok);
        logOutcome(bad);
        assertTrue(ok.allowed());
        assertFalse(bad.allowed());
        assertEquals(RejectReason.CALLER_VERSION_NOT_ALLOWED, bad.rejectReason());
    }

    @Test
    void parseAcceptsHttpForms() {
        assertEquals(V11, ApiVersion.parse("v1.1"));
        assertEquals(V10, ApiVersion.parse("V1.0"));
        assertEquals(V10, ApiVersion.parse(" 1.0 "));
        for (String invalid : List.of("v1", "1.2.3", "abc", "vX.Y")) {
            try {
                ApiVersion.parse(invalid);
                throw new AssertionError("应当无法解析: " + invalid);
            } catch (IllegalArgumentException expected) {
                System.out.println("[VersionTest] 拒绝非法版本字符串: " + invalid);
            }
        }
    }
}
