package com.github.lisama569.delivery.service;

import com.github.lisama569.delivery.domain.DecisionRecord;
import com.github.lisama569.delivery.domain.HealthRules;
import com.github.lisama569.delivery.domain.MetricSample;
import com.github.lisama569.delivery.domain.ReleasePlan;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 基于健康指标的自动推进/回退判定。
 * 规则：
 *  - 观测窗口（从计划创建起 windowMillis）未结束 -> WAIT_WINDOW_OPEN，不推进；
 *  - 样本数 < minSamples -> WAIT_INSUFFICIENT_SAMPLES，不推进；
 *  - 错误率 > maxErrorRate 或最大延迟 > maxLatencyMillis -> ROLLBACK；
 *  - 否则 -> ADVANCE。
 */
@Component
public class HealthGate {

    public DecisionRecord evaluate(ReleasePlan plan, long nowMillis) {
        HealthRules rules = plan.getHealthRules();
        List<MetricSample> samples = plan.getSamples().subList(plan.getStageSampleOffset(), plan.getSamples().size());
        int count = samples.size();
        long errors = samples.stream().filter(MetricSample::error).count();
        double errorRate = count == 0 ? 0.0 : (double) errors / count;
        long maxLatency = samples.stream().mapToLong(MetricSample::latencyMillis).max().orElse(0L);

        DecisionRecord.Verdict verdict;
        long windowEnd = plan.getCreatedAt().toEpochMilli() + rules.windowMillis();
        if (nowMillis < windowEnd) {
            verdict = DecisionRecord.Verdict.WAIT_WINDOW_OPEN;
        } else if (count < rules.minSamples()) {
            verdict = DecisionRecord.Verdict.WAIT_INSUFFICIENT_SAMPLES;
        } else if (errorRate > rules.maxErrorRate() || maxLatency > rules.maxLatencyMillis()) {
            verdict = DecisionRecord.Verdict.ROLLBACK;
        } else {
            verdict = DecisionRecord.Verdict.ADVANCE;
        }
        return new DecisionRecord(plan.getPlanId(), plan.getCurrentStageIndex(), verdict,
                rules, count, errorRate, maxLatency, Instant.ofEpochMilli(nowMillis));
    }
}
