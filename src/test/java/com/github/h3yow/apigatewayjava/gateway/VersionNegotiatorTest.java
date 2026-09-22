package com.github.h3yow.apigatewayjava.gateway;

import com.github.h3yow.apigatewayjava.gateway.config.ApiVersion;
import com.github.h3yow.apigatewayjava.gateway.config.CallerPolicy;
import com.github.h3yow.apigatewayjava.gateway.version.VersionDecision;
import com.github.h3yow.apigatewayjava.gateway.version.VersionNegotiator;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 版本协商单测：缺失 / 未知 / 不兼容必须明确拒绝且不回落；同一组合判定稳定。
 */
class VersionNegotiatorTest {

    private static final Logger log = LoggerFactory.getLogger(VersionNegotiatorTest.class);

    private final VersionNegotiator negotiator = new VersionNegotiator();
    private final CallerPolicy v1Only = new CallerPolicy(2, 1000, EnumSet.of(ApiVersion.V1));
    private final CallerPolicy v1v2 =
            new CallerPolicy(3, 1000, EnumSet.of(ApiVersion.V1, ApiVersion.V2));

    @Test
    void missingVersionIsRejectedWithoutFallback() {
        for (String raw : new String[]{null, "", "   "}) {
            VersionDecision d = negotiator.negotiate(raw, v1v2);
            log.info("版本协商测试 调用方策略=授权[v1,v2] 原始版本={} 判定=拒绝 原因={} 依据=版本声明缺失，不回落默认版本",
                    raw == null ? "<null>" : "'" + raw + "'", d.reason());
            assertFalse(d.accepted());
            assertEquals("VERSION_MISSING", d.reason());
            assertNull(d.version());
        }
    }

    @Test
    void unknownVersionTokenIsRejectedWithoutFallback() {
        for (String raw : new String[]{"v3", "9.9", "v1 "}) {
            // 注意 "v1 " 带空白可被 trim 接受；真正非法样本是 v3 / 9.9
        }
        for (String raw : new String[]{"v3", "9.9", "beta"}) {
            VersionDecision d = negotiator.negotiate(raw, v1v2);
            log.info("版本协商测试 调用方策略=授权[v1,v2] 原始版本={} 判定=拒绝 原因={} 依据=无法识别的版本标识，不回落默认版本",
                    raw, d.reason());
            assertFalse(d.accepted());
            assertEquals("VERSION_UNKNOWN", d.reason());
            assertNull(d.version());
        }
    }

    @Test
    void recognizedButIncompatibleVersionIsRejected() {
        // legacy-b 仅授权 v1，请求 v2：版本可识别，但组合不兼容
        VersionDecision denied = negotiator.negotiate("v2", v1Only);
        log.info("版本协商测试 调用方策略=授权[v1] 原始版本=v2 判定=拒绝 原因={} 依据=版本可识别但未授权，静默回落被禁止",
                denied.reason());
        assertFalse(denied.accepted());
        assertEquals("VERSION_NOT_SUPPORTED", denied.reason());

        // 大小写/空白容错后仍走兼容判定：V1（大写）应通过，v2 拒绝，二者区分
        VersionDecision accepted = negotiator.negotiate("  V1 ", v1Only);
        log.info("版本协商测试 调用方策略=授权[v1] 原始版本='  V1 ' 判定=通过 解析版本={} 依据=归一化后命中授权集合",
                accepted.version());
        assertTrue(accepted.accepted());
        assertEquals(ApiVersion.V1, accepted.version());
    }

    @Test
    void decisionIsStableForSameCombination() {
        for (int i = 0; i < 5; i++) {
            VersionDecision d1 = negotiator.negotiate("v1", v1v2);
            VersionDecision d2 = negotiator.negotiate("v2", v1v2);
            assertTrue(d1.accepted() && d1.version() == ApiVersion.V1);
            assertTrue(d2.accepted() && d2.version() == ApiVersion.V2);
        }
        log.info("版本协商稳定性测试 调用方策略=授权[v1,v2] 组合=(v1,v2) 判定依据=纯函数判定，连续5轮结论稳定");
        assertEquals(Set.of(ApiVersion.V1, ApiVersion.V2), v1v2.supportedVersions());
    }
}
