package com.github.lisama569.progressivedeliveryjava.domain;

import java.time.Instant;

/**
 * 一条健康指标样本。
 *
 * @param errorRate  采样时刻错误率（0..1）
 * @param latencyP99 采样时刻 P99 延迟（毫秒）
 * @param at         采样时间
 */
public record HealthSample(double errorRate, double latencyP99, Instant at) {
}
