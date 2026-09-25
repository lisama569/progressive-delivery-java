package com.github.lisama569.progressivedeliveryjava.rules;

import com.github.lisama569.progressivedeliveryjava.domain.Decision;
import com.github.lisama569.progressivedeliveryjava.domain.HealthSample;
import com.github.lisama569.progressivedeliveryjava.domain.ReleasePlan;

import java.time.Instant;
import java.util.List;

/**
 * 一个版本的判定规则集（不可变）。规则更新 = 注册新版本，历史版本保留以复现历史判定。
 */
public interface JudgmentRuleSet {
    /** 规则版本号，单调递增 */
    int version();

    double maxErrorRate();

    double maxLatencyP99Millis();

    /**
     * 纯函数式判定：给定计划、阶段下标、窗口内样本与当前时间，产出判定结论。
     * 不产生副作用，不修改计划。
     */
    Decision evaluate(ReleasePlan plan, int stageIndex, int attempt,
                      List<HealthSample> windowSamples, Instant now);
}
