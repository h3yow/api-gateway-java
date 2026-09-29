package com.github.h3yow.apigatewayjava.gateway.model;

/**
 * 接口组上线状态。
 * <ul>
 *     <li>{@link #OPEN}：正常开放，命中开放版本即可访问；</li>
 *     <li>{@link #CANARY}：灰度中，仅 canaryCallers 白名单内的调用方可访问；</li>
 *     <li>{@link #PAUSED}：已上线后暂停服务，任何调用方一律拒绝；</li>
 *     <li>{@link #NOT_OPENED}：尚未到开放时间/从未开放，任何调用方一律拒绝。</li>
 * </ul>
 * 注意：PAUSED 与 NOT_OPENED 是不同语义，必须给出可区分的结论。
 */
public enum GroupStatus {
    OPEN,
    CANARY,
    PAUSED,
    NOT_OPENED
}
