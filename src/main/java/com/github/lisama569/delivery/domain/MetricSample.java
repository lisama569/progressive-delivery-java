package com.github.lisama569.delivery.domain;

/** 单条健康指标样本。 */
public record MetricSample(long timestampMillis, boolean error, long latencyMillis) {
}
