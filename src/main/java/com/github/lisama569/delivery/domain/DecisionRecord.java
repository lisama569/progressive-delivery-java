package com.github.lisama569.delivery.domain;

import java.time.Instant;

/**
 * 一次健康判定的不可变记录。保存判定时的规则快照、绑定的规则版本名与输入摘要，
 * 使规则更新后历史结论仍可按当时绑定的版本复现。
 */
public record DecisionRecord(
        String planId,
        int stageIndex,
        Verdict verdict,
        HealthRules rulesSnapshot,
        String ruleVersion,
        int sampleCount,
        double errorRate,
        long maxLatencyMillis,
        Instant decidedAt) {

    public enum Verdict {
        ADVANCE,
        ROLLBACK,
        WAIT_INSUFFICIENT_SAMPLES,
        WAIT_WINDOW_OPEN
    }
}
