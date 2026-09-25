package com.github.lisama569.delivery.service;

import com.github.lisama569.delivery.domain.DecisionRecord;
import com.github.lisama569.delivery.domain.FailureReason;
import com.github.lisama569.delivery.domain.HealthRules;
import com.github.lisama569.delivery.domain.MetricSample;
import com.github.lisama569.delivery.domain.OperationResult;
import com.github.lisama569.delivery.domain.PlanState;
import com.github.lisama569.delivery.domain.ReleasePlan;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 发布计划编排。所有状态变更在计划对象上同步，保证并发推进/中止结果一致；
 * 创建按 planId 幂等：同规格重复提交返回已有计划，不同规格标记冲突不覆盖。
 */
@Service
public class PlanService {

    private final ConcurrentHashMap<String, ReleasePlan> plans = new ConcurrentHashMap<>();
    private final HealthGate healthGate;

    public PlanService(HealthGate healthGate) {
        this.healthGate = healthGate;
    }

    public OperationResult<ReleasePlan> createPlan(String planId, int specVersion,
                                                   List<Integer> stageWeights, HealthRules rules) {
        FailureReason weightError = validateWeights(stageWeights);
        if (weightError != null) {
            return OperationResult.fail(weightError, "非法权重序列: " + stageWeights);
        }
        HealthRules effective = HealthRules.forSpecVersion(specVersion, rules);
        ReleasePlan candidate = new ReleasePlan(planId, specVersion, stageWeights, effective, Instant.now());
        ReleasePlan existing = plans.putIfAbsent(planId, candidate);
        if (existing == null) {
            return OperationResult.ok(candidate);
        }
        synchronized (existing) {
            if (sameSpec(existing, specVersion, stageWeights)) {
                // 幂等：重复提交同一计划不重复生效
                return OperationResult.ok(existing);
            }
            // 冲突：明确标记，不静默覆盖
            existing.markConflict();
            return OperationResult.fail(FailureReason.CONFLICTING_SUBMISSION,
                    "计划 " + planId + " 已存在且规格不同，已标记冲突");
        }
    }

    public OperationResult<ReleasePlan> start(String planId) {
        return withPlan(planId, plan -> {
            if (plan.getState() != PlanState.DRAFT) {
                return transitionError(plan, PlanState.DRAFT);
            }
            plan.setState(PlanState.RUNNING);
            return OperationResult.ok(plan);
        });
    }

    public OperationResult<ReleasePlan> advance(String planId) {
        return withPlan(planId, plan -> {
            if (plan.getState().isTerminal()) {
                return OperationResult.fail(FailureReason.PLAN_ALREADY_FINISHED,
                        "计划已结束: " + plan.getState());
            }
            if (plan.getState() != PlanState.RUNNING) {
                return transitionError(plan, PlanState.RUNNING);
            }
            if (plan.isLastStage()) {
                plan.setState(PlanState.COMPLETED);
            } else {
                plan.setCurrentStageIndex(plan.getCurrentStageIndex() + 1);
            }
            return OperationResult.ok(plan);
        });
    }

    public OperationResult<ReleasePlan> pause(String planId) {
        return withPlan(planId, plan -> {
            if (plan.getState().isTerminal()) {
                return OperationResult.fail(FailureReason.PLAN_ALREADY_FINISHED,
                        "计划已结束: " + plan.getState());
            }
            if (plan.getState() != PlanState.RUNNING) {
                return transitionError(plan, PlanState.RUNNING);
            }
            plan.setState(PlanState.PAUSED);
            return OperationResult.ok(plan);
        });
    }

    public OperationResult<ReleasePlan> resume(String planId) {
        return withPlan(planId, plan -> {
            if (plan.getState() != PlanState.PAUSED) {
                return transitionError(plan, PlanState.PAUSED);
            }
            plan.setState(PlanState.RUNNING);
            return OperationResult.ok(plan);
        });
    }

    public OperationResult<ReleasePlan> abort(String planId) {
        return withPlan(planId, plan -> {
            if (plan.getState().isTerminal()) {
                return OperationResult.fail(FailureReason.PLAN_ALREADY_FINISHED,
                        "计划已结束，不能重复中止: " + plan.getState());
            }
            plan.setState(PlanState.ABORTED);
            return OperationResult.ok(plan);
        });
    }

    public OperationResult<ReleasePlan> reportMetric(String planId, boolean error,
                                                     long latencyMillis, long timestampMillis) {
        return withPlan(planId, plan -> {
            if (plan.getState().isTerminal()) {
                return OperationResult.fail(FailureReason.PLAN_ALREADY_FINISHED,
                        "计划已结束，拒绝指标上报: " + plan.getState());
            }
            plan.getSamples().add(new MetricSample(timestampMillis, error, latencyMillis));
            return OperationResult.ok(plan);
        });
    }

    public OperationResult<ReleasePlan> autoJudge(String planId, long nowMillis) {
        return withPlan(planId, plan -> {
            if (plan.getState().isTerminal()) {
                return OperationResult.fail(FailureReason.PLAN_ALREADY_FINISHED,
                        "计划已结束: " + plan.getState());
            }
            if (plan.getState() != PlanState.RUNNING) {
                return transitionError(plan, PlanState.RUNNING);
            }
            int stage = plan.getCurrentStageIndex();
            // 幂等：同一阶段只判定一次，重复判定不重复扣减/回退
            if (plan.getJudgedStages().contains(stage)) {
                return OperationResult.ok(plan);
            }
            DecisionRecord record = healthGate.evaluate(plan, nowMillis);
            plan.getDecisions().add(record);
            switch (record.verdict()) {
                case ADVANCE -> {
                    plan.getJudgedStages().add(stage);
                    plan.setStageSampleOffset(plan.getSamples().size());
                    if (plan.isLastStage()) {
                        plan.setState(PlanState.COMPLETED);
                    } else {
                        plan.setCurrentStageIndex(stage + 1);
                    }
                }
                case ROLLBACK -> {
                    plan.getJudgedStages().add(stage);
                    plan.setState(PlanState.ROLLED_BACK);
                }
                case WAIT_INSUFFICIENT_SAMPLES -> {
                    return OperationResult.fail(FailureReason.INSUFFICIENT_SAMPLES,
                            "样本不足: " + record.sampleCount() + " < " + record.rulesSnapshot().minSamples());
                }
                case WAIT_WINDOW_OPEN -> {
                    return OperationResult.fail(FailureReason.WINDOW_NOT_CLOSED,
                            "观测窗口未结束");
                }
            }
            return OperationResult.ok(plan);
        });
    }

    public Optional<ReleasePlan> find(String planId) {
        return Optional.ofNullable(plans.get(planId));
    }

    // ---- 内部辅助 ----

    private interface PlanOp {
        OperationResult<ReleasePlan> apply(ReleasePlan plan);
    }

    private OperationResult<ReleasePlan> withPlan(String planId, PlanOp op) {
        ReleasePlan plan = plans.get(planId);
        if (plan == null) {
            return OperationResult.fail(FailureReason.PLAN_NOT_FOUND, "计划不存在: " + planId);
        }
        synchronized (plan) {
            return op.apply(plan);
        }
    }

    private OperationResult<ReleasePlan> transitionError(ReleasePlan plan, PlanState required) {
        return OperationResult.fail(FailureReason.ILLEGAL_TRANSITION,
                "非法状态跳转: 当前=" + plan.getState() + ", 要求=" + required);
    }

    private boolean sameSpec(ReleasePlan plan, int specVersion, List<Integer> stageWeights) {
        return plan.getSpecVersion() == specVersion && plan.getStageWeights().equals(stageWeights);
    }

    /** 权重约束：非空，每个在 (0,100]，严格递增，末阶段=100。 */
    private FailureReason validateWeights(List<Integer> weights) {
        if (weights == null || weights.isEmpty()) {
            return FailureReason.INVALID_WEIGHT;
        }
        int prev = 0;
        for (int w : weights) {
            if (w <= 0 || w > 100 || w <= prev) {
                return FailureReason.INVALID_WEIGHT;
            }
            prev = w;
        }
        if (prev != 100) {
            return FailureReason.INVALID_WEIGHT;
        }
        return null;
    }
}
