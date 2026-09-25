package com.github.lisama569.progressivedeliveryjava.domain;

import java.time.Instant;

/**
 * 一次自动判定的结论记录（不可变，可复现）。
 *
 * @param planId        计划 id
 * @param stageIndex    判定时所处阶段下标
 * @param attempt       该阶段的第几次判定（用于幂等去重）
 * @param type          判定结论
 * @param reason        判定依据（人类可读）
 * @param ruleVersion   判定使用的规则版本（保证历史结论可复现）
 * @param sampleCount   判定时窗口内样本数
 * @param avgErrorRate  窗口内平均错误率
 * @param avgLatencyP99 窗口内平均 P99 延迟
 * @param decidedAt     判定时间
 */
public record Decision(String planId,
                       int stageIndex,
                       int attempt,
                       DecisionType type,
                       String reason,
                       int ruleVersion,
                       int sampleCount,
                       double avgErrorRate,
                       double avgLatencyP99,
                       Instant decidedAt) {
}
