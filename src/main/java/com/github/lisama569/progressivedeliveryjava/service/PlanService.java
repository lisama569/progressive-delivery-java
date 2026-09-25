package com.github.lisama569.progressivedeliveryjava.service;

import com.github.lisama569.progressivedeliveryjava.compat.PlanCompatibility;
import com.github.lisama569.progressivedeliveryjava.domain.FailureReason;
import com.github.lisama569.progressivedeliveryjava.domain.PlanState;
import com.github.lisama569.progressivedeliveryjava.domain.ReleasePlan;
import com.github.lisama569.progressivedeliveryjava.domain.RolloutException;
import com.github.lisama569.progressivedeliveryjava.domain.StageConfig;
import com.github.lisama569.progressivedeliveryjava.store.PlanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 发布计划生命周期编排。
 *
 * 所有操作通过仓库的 per-plan 锁串行化；每个写操作：
 * 1) 校验计划存在；2) 乐观版本（revision）校验；3) 幂等键去重；
 * 4) 状态机/权重/阶段跳转约束校验；5) 落状态。
 */
@Service
public class PlanService {

    private static final Logger log = LoggerFactory.getLogger(PlanService.class);

    private final PlanRepository repository;
    private final PlanCompatibility compatibility;
    private final Clock clock;

    public PlanService(PlanRepository repository, PlanCompatibility compatibility, Clock clock) {
        this.repository = repository;
        this.compatibility = compatibility;
        this.clock = clock;
    }

    public OperationResult create(CreatePlanRequest request) {
        Objects.requireNonNull(request.planId(), "planId");
        if (request.stages() == null || request.stages().isEmpty()) {
            throw new RolloutException(FailureReason.INVALID_WEIGHTS, "阶段列表不能为空");
        }
        List<StageConfig> normalized = compatibility.normalizeStages(request.planVersion(), request.stages());
        validateWeights(normalized);

        return repository.withLock(request.planId(), () -> {
            ReleasePlan existing = repository.find(request.planId()).orElse(null);
            if (existing != null) {
                if (request.idempotencyKey() != null
                        && request.idempotencyKey().equals(existing.idempotencyKey())) {
                    log.info("重复创建命中幂等键 planId={} key={}，返回首次结果（不重复生效）",
                            request.planId(), request.idempotencyKey());
                    return new OperationResult(existing, true,
                            existing.state(), existing.currentStageIndex());
                }
                throw new RolloutException(FailureReason.DUPLICATE_SUBMISSION,
                        "计划 " + request.planId() + " 已存在：重复提交被拒绝（幂等键缺失或不一致）");
            }
            Instant now = Instant.now(clock);
            ReleasePlan plan = new ReleasePlan(request.planId(), request.planVersion(),
                    request.idempotencyKey(), normalized, now);
            repository.save(plan);
            log.info("创建计划 planId={} version=v{} stages={} weights={}",
                    plan.planId(), plan.planVersion(), normalized.size(),
                    normalized.stream().map(StageConfig::weightPercent).toList());
            return new OperationResult(plan, false, plan.state(), plan.currentStageIndex());
        });
    }

    /**
     * 手动推进。DRAFT 上调用表示"启动并进入阶段 0"；RUNNING 上只能前进到相邻下一阶段，
     * 最后阶段前进即 COMPLETED。
     */
    public OperationResult advance(String planId, String idempotencyKey, Long expectedRevision) {
        return mutate(planId, idempotencyKey, expectedRevision, "advance", plan -> {
            Instant now = Instant.now(clock);
            if (plan.state() == PlanState.DRAFT) {
                plan.transitionTo(PlanState.RUNNING, now);
                plan.enterStage(0, now);
                log.info("启动计划 planId={}，进入阶段 0（权重 {}%）", planId,
                        plan.currentStage().weightPercent());
                return "started: enter stage 0";
            }
            requireRunning(plan, "推进");
            if (plan.isLastStage()) {
                plan.transitionTo(PlanState.COMPLETED, now);
                log.info("计划 planId={} 已完成（最后阶段 100% 推进完成）", planId);
                return "completed";
            }
            int next = plan.currentStageIndex() + 1;
            plan.enterStage(next, now);
            log.info("计划 planId={} 推进到阶段 {}（权重 {}%）", planId, next,
                    plan.currentStage().weightPercent());
            return "advanced to stage " + next;
        });
    }

    public OperationResult pause(String planId, String idempotencyKey, Long expectedRevision) {
        return mutate(planId, idempotencyKey, expectedRevision, "pause", plan -> {
                    if (plan.state() != PlanState.RUNNING) {
                        throw new RolloutException(FailureReason.ILLEGAL_STATE_TRANSITION,
                                "只有 RUNNING 状态可以暂停，当前状态: " + plan.state());
                    }
                    plan.transitionTo(PlanState.PAUSED, Instant.now(clock));
                    log.info("计划 planId={} 已暂停", planId);
                    return "paused";
                });
    }

    public OperationResult resume(String planId, String idempotencyKey, Long expectedRevision) {
        return mutate(planId, idempotencyKey, expectedRevision, "resume", plan -> {
            if (plan.state() != PlanState.PAUSED) {
                throw new RolloutException(FailureReason.ILLEGAL_STATE_TRANSITION,
                        "只有 PAUSED 状态可以恢复，当前状态: " + plan.state());
            }
            plan.transitionTo(PlanState.RUNNING, Instant.now(clock));
            log.info("计划 planId={} 已恢复运行", planId);
            return "resumed";
        });
    }

    public OperationResult abort(String planId, String idempotencyKey, Long expectedRevision) {
        return mutate(planId, idempotencyKey, expectedRevision, "abort", plan -> {
            plan.transitionTo(PlanState.ABORTED, Instant.now(clock));
            log.info("计划 planId={} 已中止", planId);
            return "aborted";
        });
    }

    /**
     * 调整某个阶段的权重。仅允许修改"尚未进入"的阶段：
     * DRAFT 下可改任意阶段；RUNNING/PAUSED 下只能改当前阶段之后的阶段。
     */
    public OperationResult setWeight(String planId, int stageIndex, int weightPercent,
                                     String idempotencyKey, Long expectedRevision) {
        return mutate(planId, idempotencyKey, expectedRevision, "setWeight#" + stageIndex, plan -> {
            if (stageIndex < 0 || stageIndex >= plan.stages().size()) {
                throw new RolloutException(FailureReason.ILLEGAL_STAGE_JUMP,
                        "阶段下标越界: " + stageIndex + "，阶段数=" + plan.stages().size());
            }
            if (plan.state() != PlanState.DRAFT && stageIndex <= plan.currentStageIndex()) {
                throw new RolloutException(FailureReason.ILLEGAL_STATE_TRANSITION,
                        "只能修改尚未进入的阶段权重，stageIndex=" + stageIndex
                                + "，当前阶段=" + plan.currentStageIndex());
            }
            List<StageConfig> candidate = new java.util.ArrayList<>(plan.stages());
            StageConfig old = candidate.get(stageIndex);
            candidate.set(stageIndex,
                    new StageConfig(weightPercent, old.minSamples(), old.observationWindowMillis()));
            validateWeights(candidate);
            plan.setStageWeight(stageIndex, weightPercent, Instant.now(clock));
            log.info("计划 planId={} 阶段 {} 权重调整为 {}%", planId, stageIndex, weightPercent);
            return "weight of stage " + stageIndex + " set to " + weightPercent;
        });
    }

    public ReleasePlan get(String planId) {
        return requirePlan(planId);
    }

    // ---------- 内部机制 ----------

    @FunctionalInterface
    private interface PlanMutation {
        String apply(ReleasePlan plan);
    }

    private OperationResult mutate(String planId, String idempotencyKey, Long expectedRevision,
                                   String opName, PlanMutation action) {
        ReleasePlan plan = requirePlan(planId);
        return repository.withLock(planId, () -> {
            if (plan.state().isTerminal()) {
                throw new RolloutException(FailureReason.PLAN_ALREADY_FINISHED,
                        "计划已结束（" + plan.state() + "），拒绝操作: " + opName);
            }
            if (expectedRevision != null && expectedRevision != plan.revision()) {
                throw new RolloutException(FailureReason.VERSION_CONFLICT,
                        "乐观版本冲突: 期望 revision=" + expectedRevision
                                + "，实际 revision=" + plan.revision() + "，拒绝静默覆盖");
            }
            if (idempotencyKey != null) {
                String opKey = opName + ":" + idempotencyKey;
                if (plan.hasProcessedOp(opKey)) {
                    log.info("重复操作命中幂等键 planId={} op={} key={}，返回首次结果（不重复生效）",
                            planId, opName, idempotencyKey);
                    return new OperationResult(plan, true,
                            plan.state(), plan.currentStageIndex());
                }
                String outcome = action.apply(plan);
                plan.markOpProcessed(opKey);
                log.info("操作生效 planId={} op={} key={} outcome={} revision={}",
                        planId, opName, idempotencyKey, outcome, plan.revision());
                return new OperationResult(plan, false, plan.state(), plan.currentStageIndex());
            }
            String outcome = action.apply(plan);
            log.info("操作生效 planId={} op={} outcome={} revision={}",
                    planId, opName, outcome, plan.revision());
            return new OperationResult(plan, false, plan.state(), plan.currentStageIndex());
        });
    }

    private ReleasePlan requirePlan(String planId) {
        return repository.find(planId)
                .orElseThrow(() -> new RolloutException(FailureReason.PLAN_NOT_FOUND,
                        "计划不存在: " + planId));
    }

    private void requireRunning(ReleasePlan plan, String op) {
        if (plan.state() != PlanState.RUNNING) {
            throw new RolloutException(FailureReason.ILLEGAL_STATE_TRANSITION,
                    op + "要求 RUNNING 状态，当前状态: " + plan.state());
        }
    }

    /**
     * 权重约束（既定规则）：
     * 1) 每个权重在 [1,100]；2) 严格单调递增；3) 最后一个阶段必须是 100（全量）。
     */
    private void validateWeights(List<StageConfig> stages) {
        int prev = 0;
        for (int i = 0; i < stages.size(); i++) {
            int w = stages.get(i).weightPercent();
            if (w < 1 || w > 100) {
                throw new RolloutException(FailureReason.INVALID_WEIGHTS,
                        "非法权重: 阶段 " + i + " 权重 " + w + " 超出范围 [1,100]");
            }
            if (w <= prev) {
                throw new RolloutException(FailureReason.INVALID_WEIGHTS,
                        "非法权重: 阶段 " + i + " 权重 " + w + " 必须严格大于前一阶段权重 " + prev);
            }
            prev = w;
        }
        if (prev != 100) {
            throw new RolloutException(FailureReason.INVALID_WEIGHTS,
                    "非法权重: 最后一个阶段必须为 100%（全量），实际为 " + prev + "%");
        }
    }
}
