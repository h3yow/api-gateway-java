package com.github.h3yow.apigatewayjava.gateway.model;

/**
 * 接口组上线状态。
 * <ul>
 *     <li>{@link #ACTIVE}：已全量开放；</li>
 *     <li>{@link #GRAY}：灰度中，仅灰度调用方白名单可访问，其余明确拒绝；</li>
 *     <li>{@link #PAUSED}：已暂停，所有调用方均拒绝（临时下线，区别于从未上线）；</li>
 *     <li>{@link #NOT_OPEN}：尚未开放/未上线，所有调用方均拒绝。</li>
 * </ul>
 */
public enum InterfaceStatus {
    ACTIVE,
    GRAY,
    PAUSED,
    NOT_OPEN
}
