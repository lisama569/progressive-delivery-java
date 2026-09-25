package com.github.lisama569.progressivedeliveryjava.rules;

import com.github.lisama569.progressivedeliveryjava.domain.Decision;
import com.github.lisama569.progressivedeliveryjava.domain.DecisionType;
import com.github.lisama569.progressivedeliveryjava.domain.HealthSample;
import com.github.lisama569.progressivedeliveryjava.domain.ReleasePlan;
import com.github.lisama569.progressivedeliveryjava.domain.StageConfig;

import java.time.Instant;
import java.util.List;

/**
 * 默认判定规则集。判定策略（优先级自上而下，安全优先）：
 * <ol>
 *   <li>观测窗口内任意样本越界（错误率或 P99 超阈值）→ ROLLBACK（不受窗口/样本数限制）；</li>
 *   <li>观测窗口未结束 → HOLD_WINDOW_NOT_ELAPSED（不得推进）；</li>
 *   <li>窗口内样本数 &lt; minSamples → HOLD_INSUFFICIENT_SAMPLES（不得推进）；</li>
 *   <li>已是最后阶段 → COMPLETE，否则 → ADVANCE。</li>
 * </ol>
 * evaluate 为纯函数：不修改任何外部状态，同输入同输出（规则版本内）。
 */
public class DefaultJudgmentRuleSet implements JudgmentRuleSet {

    private final int version;
    private final double maxErrorRate;
    private final double maxLatencyP99Millis;

    public DefaultJudgmentRuleSet(int version, double maxErrorRate, double maxLatencyP99Millis) {
        this.version = version;
        this.maxErrorRate = maxErrorRate;
        this.maxLatencyP99Millis = maxLatencyP99Millis;
    }

    @Override
    public int version() {
        return version;
    }

    @Override
    public double maxErrorRate() {
        return maxErrorRate;
    }

    @Override
    public double maxLatencyP99Millis() {
        return maxLatencyP99Millis;
    }

    @Override
    public Decision evaluate(ReleasePlan plan, int stageIndex, int attempt,
                             List<HealthSample> windowSamples, Instant now) {
        StageConfig stage = plan.stages().get(stageIndex);
        int n = windowSamples.size();
        double avgError = n == 0 ? 0.0
                : windowSamples.stream().mapToDouble(HealthSample::errorRate).average().orElse(0);
        double avgLatency = n == 0 ? 0.0
                : windowSamples.stream().mapToDouble(HealthSample::latencyP99).average().orElse(0);

        DecisionType type;
        String reason;

        // 1) 越界优先：任何一条样本超阈值即回退
        HealthSample breached = windowSamples.stream()
                .filter(s -> s.errorRate() > maxErrorRate || s.latencyP99() > maxLatencyP99Millis)
                .findFirst()
                .orElse(null);
        if (breached != null) {
            type = DecisionType.ROLLBACK;
            reason = String.format(
                    "指标越界: 样本{errorRate=%.4f, latencyP99=%.1f}超过阈值{errorRate<=%.4f, latencyP99<=%.1f}",
                    breached.errorRate(), breached.latencyP99(), maxErrorRate, maxLatencyP99Millis);
        } else {
            long elapsed = now.toEpochMilli() - plan.stageEnteredAt().toEpochMilli();
            // 2) 观测窗口未结束
            if (elapsed < stage.observationWindowMillis()) {
                type = DecisionType.HOLD_WINDOW_NOT_ELAPSED;
                reason = String.format("观测窗口未结束: 已观测 %dms < 要求 %dms",
                        elapsed, stage.observationWindowMillis());
            }
            // 3) 样本不足
            else if (n < stage.minSamples()) {
                type = DecisionType.HOLD_INSUFFICIENT_SAMPLES;
                reason = String.format("指标样本不足: 窗口内 %d 条 < 最少 %d 条", n, stage.minSamples());
            }
            // 4) 满足推进条件
            else {
                boolean last = stageIndex == plan.stages().size() - 1;
                type = last ? DecisionType.COMPLETE : DecisionType.ADVANCE;
                reason = String.format(
                        "判定通过: 窗口 %dms 已结束, 样本 %d>=%d, avgErrorRate=%.4f<=%.4f, avgLatencyP99=%.1f<=%.1f",
                        elapsed, n, stage.minSamples(), avgError, maxErrorRate,
                        avgLatency, maxLatencyP99Millis);
            }
        }

        return new Decision(plan.planId(), stageIndex, attempt, type, reason, version,
                n, avgError, avgLatency, now);
    }
}
