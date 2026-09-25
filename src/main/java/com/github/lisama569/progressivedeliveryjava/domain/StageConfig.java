package com.github.lisama569.progressivedeliveryjava.domain;

/**
 * 单个灰度阶段的配置。
 *
 * @param weightPercent            该阶段流量权重（1..100）
 * @param minSamples               判定所需的最小指标样本数
 * @param observationWindowMillis  进入该阶段后必须等待的观测窗口（毫秒）
 */
public record StageConfig(int weightPercent, int minSamples, long observationWindowMillis) {
}
