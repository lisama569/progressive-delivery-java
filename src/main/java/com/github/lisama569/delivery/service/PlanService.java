package com.github.lisama569.delivery.service;

import com.github.lisama569.delivery.domain.ConflictRecord;
import com.github.lisama569.delivery.domain.DecisionRecord;
import com.github.lisama569.delivery.domain.FailureReason;
import com.github.lisama569.delivery.domain.HealthRules;
import com.github.lisama569.delivery.domain.MetricSample;
import com.github.lisama569.delivery.domain.OperationResult;
import com.github.lisama569.delivery.domain.PlanSpec;
import com.github.lisama569.delivery.domain.PlanState;
import com.github.lisama569.delivery.domain.ReleasePlan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 发布计划编排。所有状态变更在计划对象上同步，保证并发推进/中止结果一致；
 * 创建按 planId 幂等：整份规格（specVersion、阶段权重、显式规则、绑定规则版本）完全一致
 * 才算重复提交并幂等返回；任意一处不同即标记冲突、留痕可查，原计划不覆盖。
 * 受支持规格版本范围 [MIN_SUPPORTED_SPEC_VERSION, MAX_SUPPORTED_SPEC_VERSION]，
 * 范围外创建请求一律以 UNSUPPORTED_SPEC_VERSION 拒绝。
 */
@Service
public class PlanService {

    /** 受支持规格版本下限（含）。 */
    public static final int MIN_SUPPORTED_SPEC_VERSION = 1;
    /** 受支持规格版本上限（含），即当前版本。 */
    public static final int MAX_SUPPORTED_SPEC_VERSION = ReleasePlan.CURRENT_SPEC_VERSION;

    private final ConcurrentHashMap<String, ReleasePlan> plans = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<ConflictRecord>> conflicts = new ConcurrentHashMap<>();
    private final HealthGate healthGate;
    private final RuleVersionRegistry ruleRegistry;

    /** 便捷构造：自带独立规则注册表（测试与本地复现用）。 */
    public PlanService(HealthGate healthGate) {
        this(healthGate, new RuleVersionRegistry());
    }

    @Autowired
    public PlanService(HealthGate healthGate, RuleVersionRegistry ruleRegistry) {
        this.healthGate = healthGate;
        this.ruleRegistry = ruleRegistry;
    }

    public RuleVersionRegistry ruleRegistry() {
        return ruleRegistry;
    }

    /** 兼容入口：不绑定规则版本。 */
    public OperationResult<ReleasePlan> createPlan(String planId, int specVersion,
                                                   List<Integer> stageWeights, HealthRules rules) {
        return createPlan(planId, specVersion, stageWeights, rules, null);
    }

    public OperationResult<ReleasePlan> createPlan(String planId, int specVersion,
                                                   List<Integer> stageWeights, HealthRules rules,
                                                   String ruleVersion) {
        if (specVersion < MIN_SUPPORTED_SPEC_VERSION || specVersion > MAX_SUPPORTED_SPEC_VERSION) {
            return OperationResult.fail(FailureReason.UNSUPPORTED_SPEC_VERSION,
                    "规格版本超出受支持范围 [" + MIN_SUPPORTED_SPEC_VERSION + ", "
                            + MAX_SUPPORTED_SPEC_VERSION + "]: " + specVersion);
        }
        FailureReason weightError = validateWeights(stageWeights);
        if (weightError != null) {
            return OperationResult.fail(weightError, "非法权重序列: " + stageWeights);
        }
        HealthRules effective;
        if (ruleVersion != null) {
            Optional<HealthRules> bound = ruleRegistry.find(ruleVersion);
            if (bound.isEmpty()) {
                return OperationResult.fail(FailureReason.RULE_VERSION_NOT_FOUND,
                        "规则版本不存在: " + ruleVersion);
            }
            // 锁定：取登记时的内容作为快照，之后注册表调整不影响本计划
            effective = bound.get();
        } else {
            effective = HealthRules.forSpecVersion(specVersion, rules);
        }
        PlanSpec submitted = new PlanSpec(specVersion, stageWeights, rules, ruleVersion);
        ReleasePlan candidate = new ReleasePlan(planId, submitted, effective, Instant.now());
        ReleasePlan existing = plans.putIfAbsent(planId, candidate);
        if (existing == null) {
            return OperationResult.ok(candidate);
        }
        synchronized (existing) {
            if (existing.getSpec().equals(submitted)) {
                // 幂等：整份规格完全一致的重复提交不重复生效
                return OperationResult.ok(existing);
            }
            // 冲突：明确标记并留痕，不静默覆盖；并发下只有先到的规格生效
            existing.markConflict();
            ConflictRecord record = new ConflictRecord(planId, existing.getSpec(), submitted, Instant.now());
            conflicts.computeIfAbsent(planId, k -> new CopyOnWriteArrayList<>()).add(record);
            return OperationResult.fail(FailureReason.CONFLICTING_SUBMISSION,
                    "计划 " + planId + " 已存在且规格不同，已标记冲突并留痕");
        }
    }

    /** 查询某计划的全部冲突留痕（按发生顺序）。 */
    public List<ConflictRecord> getConflicts(String planId) {
        return List.copyOf(conflicts.getOrDefault(planId, List.of()));
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
