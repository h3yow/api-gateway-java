package com.github.h3yow.apigatewayjava.gateway.group;

import com.github.h3yow.apigatewayjava.gateway.model.ApiVersion;
import com.github.h3yow.apigatewayjava.gateway.model.QuotaPolicy;

/**
 * 配额解析：把“调用方 × 版本 × 接口组”解析为一条最终生效的配额及其判定依据。
 * 接口组有自己的限额/窗口时用组配额，否则回退到“调用方×版本”的既有配额策略。
 */
public interface QuotaResolver {

    Resolved resolve(String caller, ApiVersion version, String groupId);

    /**
     * @param policy 最终生效的配额（永不为 null：未配置组也必须回退到有限默认配额）
     * @param basis  人类可读的判定依据（说明命中了组配额还是哪一级回退）
     */
    record Resolved(QuotaPolicy policy, String basis) {
    }
}
