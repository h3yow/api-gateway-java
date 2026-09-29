package com.github.h3yow.apigatewayjava.gateway.group;

import com.github.h3yow.apigatewayjava.gateway.model.ApiGroupPolicy;
import com.github.h3yow.apigatewayjava.gateway.model.ApiVersion;
import com.github.h3yow.apigatewayjava.gateway.model.GroupAuthorization;
import com.github.h3yow.apigatewayjava.gateway.model.GroupStatus;
import com.github.h3yow.apigatewayjava.gateway.model.RejectReason;

/**
 * 接口组准入判定（纯函数，无状态）。
 *
 * 判定顺序（每一步都给出可解释依据，且互不混淆）：
 * <ol>
 *     <li>{@link GroupStatus#NOT_OPENED}：接口尚未开放 → GROUP_NOT_OPENED(404)，一律拒绝；</li>
 *     <li>{@link GroupStatus#PAUSED}：接口暂停 → GROUP_PAUSED(503)，一律拒绝；</li>
 *     <li>{@link GroupStatus#CANARY}：调用方不在灰度白名单 → GROUP_CANARY_NOT_ALLOWED(403)；</li>
 *     <li>声明版本不在本组开放版本白名单 → GROUP_VERSION_NOT_ALLOWED(400)，
 *         不静默替换为默认版本，也不自动套用默认接口策略；</li>
 *     <li>以上都通过 → 放行，生效版本就是声明版本本身。</li>
 * </ol>
 * 相同输入结论稳定，与其他接口组、其他版本的结论彼此独立。
 */
public class GroupAuthorizer {

    public GroupAuthorization authorize(String caller, ApiVersion version, ApiGroupPolicy policy) {
        String groupId = policy.groupId();

        if (policy.status() == GroupStatus.NOT_OPENED) {
            return reject(groupId, RejectReason.GROUP_NOT_OPENED,
                    "接口组 " + groupId + " 尚未开放(status=NOT_OPENED)，调用方=" + caller
                            + " 被本地拒绝，等待上线，不套用默认接口策略");
        }
        if (policy.status() == GroupStatus.PAUSED) {
            return reject(groupId, RejectReason.GROUP_PAUSED,
                    "接口组 " + groupId + " 已暂停(status=PAUSED)，调用方=" + caller
                            + " 被本地拒绝，请稍后再试");
        }
        if (policy.status() == GroupStatus.CANARY
                && policy.canaryCallers().stream().noneMatch(c -> c.equals(caller))) {
            return reject(groupId, RejectReason.GROUP_CANARY_NOT_ALLOWED,
                    "接口组 " + groupId + " 处于灰度(status=CANARY)，灰度白名单="
                            + policy.canaryCallers() + "，调用方=" + caller + " 不在名单内，本地拒绝");
        }
        if (!policy.openVersions().contains(version)) {
            return reject(groupId, RejectReason.GROUP_VERSION_NOT_ALLOWED,
                    "接口组 " + groupId + " 开放版本=" + policy.openVersions()
                            + "，调用方=" + caller + " 声明版本 " + version.httpValue()
                            + " 不在该组开放范围；本地拒绝，不静默替换/不套用默认策略");
        }
        return new GroupAuthorization(groupId, true, null,
                "接口组 " + groupId + " status=" + policy.status() + "，声明版本 "
                        + version.httpValue() + " 在开放集合 " + policy.openVersions() + " 内，组准入通过");
    }

    private static GroupAuthorization reject(String groupId, RejectReason reason, String basis) {
        return new GroupAuthorization(groupId, false, reason, basis);
    }
}
