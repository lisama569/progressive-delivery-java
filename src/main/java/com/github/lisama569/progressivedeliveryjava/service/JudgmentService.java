package com.github.lisama569.progressivedeliveryjava.service;

import com.github.lisama569.progressivedeliveryjava.domain.Decision;
import com.github.lisama569.progressivedeliveryjava.domain.DecisionType;
import com.github.lisama569.progressivedeliveryjava.domain.FailureReason;
import com.github.lisama569.progressivedeliveryjava.domain.HealthSample;
import com.github.lisama569.progressivedeliveryjava.domain.PlanState;
import com.github.lisama569.progressivedeliveryjava.domain.ReleasePlan;
import com.github.lisama569.progressivedeliveryjava.domain.RolloutException;
import com.github.lisama569.progressivedeliveryjava.rules.RuleRegistry;
import com.github.lisama569.progressivedeliveryjava.store.PlanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 自动判定服务。
 *
 * 幂等保证（同一阶段重复判定不得重复扣减/重复回退）：
 * 1) 判定窗口 = 进入当前阶段之后的样本；推进/回退会重置窗口起点，
 *    因此并发重复的判定在新阶段上只会得到 HOLD（窗口为空），不会重复生效；
 * 2) 无新样本且上次结论仍为 HOLD 时，直接返回首次结论（不追加记录）。
 */
@Service
public class JudgmentService {

    private static final Logger log = LoggerFactory.getLogger(JudgmentService.class);

    private final PlanRepository repository;
    private final RuleRegistry ruleRegistry;
    private final Clock clock;

    public JudgmentService(PlanRepository repository, RuleRegistry ruleRegistry, Clock clock) {
        this.repository = repository;
        this.ruleRegistry = ruleRegistry;
        this.clock = clock;
    }

    public void recordSample(String planId, HealthSample sample) {
        repository.withLock(planId, () -> {
            ReleasePlan plan = requirePlan(planId);
            if (plan.state().isTerminal()) {
                throw new RolloutException(FailureReason.PLAN_ALREADY_FINISHED,
                        "计划已结束（" + plan.state() + "），拒绝录入指标样本");
            }
            plan.addSample(sample);
            log.info("录入样本 planId={} stage={} errorRate={} latencyP99={} at={}",
                    planId, plan.currentStageIndex(), sample.errorRate(), sample.latencyP99(), sample.at());
            return null;
        });
    }

    /**
     * 对指定计划执行一次判定。结论与依据以 Decision 形式落库，可复现。
     */
    public Decision judge(String planId) {
        return repository.withLock(planId, () -> {
            ReleasePlan plan = requirePlan(planId);
            if (plan.state().isTerminal()) {
                throw new RolloutException(FailureReason.PLAN_ALREADY_FINISHED,
                        "计划已结束（" + plan.state() + "），拒绝判定");
            }
            if (plan.state() != PlanState.RUNNING || plan.currentStageIndex() < 0) {
                throw new RolloutException(FailureReason.ILLEGAL_STATE_TRANSITION,
                        "只有 RUNNING 且已进入阶段的计划可以判定，当前状态=" + plan.state()
                                + "，阶段=" + plan.currentStageIndex());
            }

            Instant now = Instant.now(clock);
            List<HealthSample> windowSamples = plan.windowSamples();
            int stageIndex = plan.currentStageIndex();

            // 去重：无新样本且上次 HOLD 结论在当前时刻仍然成立 → 返回首次结论
            Decision last = plan.lastDecisionForCurrentStage();
            if (last != null && last.sampleCount() == windowSamples.size()
                    && isStillHolding(last, plan, now)) {
                log.info("重复判定命中去重 planId={} stage={} attempt={} type={}（返回首次结论）",
                        planId, stageIndex, last.attempt(), last.type());
                return last;
            }

            int attempt = plan.attemptsFor(stageIndex) + 1;
            Decision decision = ruleRegistry.current()
                    .evaluate(plan, stageIndex, attempt, windowSamples, now);
            plan.addDecision(decision);
            applyEffect(plan, decision, now);

            log.info("判定完成 planId={} stage={} attempt={} type={} ruleVersion=v{} "
                            + "samples={} avgErrorRate={} avgLatencyP99={} 依据: {}",
                    planId, stageIndex, attempt, decision.type(), decision.ruleVersion(),
                    decision.sampleCount(), decision.avgErrorRate(), decision.avgLatencyP99(),
                    decision.reason());
            return decision;
        });
    }

    public List<Decision> decisions(String planId) {
        return requirePlan(planId).decisions();
    }

    // ---------- 内部 ----------

    private boolean isStillHolding(Decision last, ReleasePlan plan, Instant now) {
        return switch (last.type()) {
            case HOLD_INSUFFICIENT_SAMPLES -> true; // 样本数未变，结论不变
            case HOLD_WINDOW_NOT_ELAPSED -> {
                long window = plan.currentStage().observationWindowMillis();
                yield now.toEpochMilli() - plan.stageEnteredAt().toEpochMilli() < window;
            }
            default -> false;
        };
    }

    private void applyEffect(ReleasePlan plan, Decision decision, Instant now) {
        switch (decision.type()) {
            case ROLLBACK -> {
                int target = decision.stageIndex() - 1; // 可回退到 -1（基线，0% 流量）
                plan.enterStage(target, now);
                log.info("触发回退 planId={} 从阶段 {} 回退到 {}", plan.planId(),
                        decision.stageIndex(), target < 0 ? "基线(0%)" : "阶段 " + target);
            }
            case ADVANCE -> {
                plan.enterStage(decision.stageIndex() + 1, now);
                log.info("自动推进 planId={} 进入阶段 {}（权重 {}%）", plan.planId(),
                        plan.currentStageIndex(), plan.currentStage().weightPercent());
            }
            case COMPLETE -> {
                plan.transitionTo(PlanState.COMPLETED, now);
                log.info("计划 planId={} 判定通过最后阶段，发布完成", plan.planId());
            }
            default -> { /* HOLD：不推进、不回退 */ }
        }
    }

    private ReleasePlan requirePlan(String planId) {
        return repository.find(planId)
                .orElseThrow(() -> new RolloutException(FailureReason.PLAN_NOT_FOUND,
                        "计划不存在: " + planId));
    }
}
