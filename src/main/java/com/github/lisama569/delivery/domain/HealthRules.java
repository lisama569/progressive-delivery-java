package com.github.lisama569.delivery.domain;

/**
 * 健康判定规则（不可变，可随计划快照保存以保证历史判定可复现）。
 * 缺省语义（V1 计划未显式给出时使用）：minSamples=3, windowMillis=60000,
 * maxErrorRate=0.05, maxLatencyMillis=500。
 */
public record HealthRules(int minSamples, long windowMillis, double maxErrorRate, long maxLatencyMillis) {

    public static final HealthRules DEFAULT = new HealthRules(3, 60_000L, 0.05, 500L);

    /** 版本兼容入口：specVersion <= 1 或字段缺失时使用缺省语义。 */
    public static HealthRules forSpecVersion(int specVersion, HealthRules explicit) {
        if (explicit != null) {
            return explicit;
        }
        return DEFAULT;
    }
}
