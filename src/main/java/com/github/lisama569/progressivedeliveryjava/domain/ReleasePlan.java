package com.github.lisama569.progressivedeliveryjava.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 发布计划（可变状态对象）。
 *
 * 线程模型：所有读写都必须发生在 PlanRepository.withLock(planId, ...) 内，
 * 因此本类不需要自身同步。
 *
 * 不变量：
 *  - currentStageIndex == -1 表示尚未进入任何灰度阶段（基线）；
 *  - 进入/离开阶段时必须同时更新 stageEnteredAt（观测窗口的起点）；
 *  - 每次状态变更 revision 单调递增（用于乐观并发与冲突检测）。
 */
public class ReleasePlan {

    private final String planId;
    private final int planVersion;
    private final String idempotencyKey;
    private final Instant createdAt;

    private PlanState state = PlanState.DRAFT;
    private final List<StageConfig> stages;
    private int currentStageIndex = -1;
    private long revision = 0;
    private Instant updatedAt;
    private Instant stageEnteredAt;

    private final List<HealthSample> samples = new ArrayList<>();
    private final List<Decision> decisions = new ArrayList<>();
    private final Map<Integer, Integer> stageAttempts = new HashMap<>();
    private final Set<String> processedOpKeys = new HashSet<>();

    public ReleasePlan(String planId, int planVersion, String idempotencyKey,
                       List<StageConfig> stages, Instant now) {
        this.planId = planId;
        this.planVersion = planVersion;
        this.idempotencyKey = idempotencyKey;
        this.stages = new ArrayList<>(stages);
        this.createdAt = now;
        this.updatedAt = now;
        this.stageEnteredAt = now;
    }

    // ---------- 只读访问 ----------

    public String planId() { return planId; }
    public int planVersion() { return planVersion; }
    public String idempotencyKey() { return idempotencyKey; }
    public PlanState state() { return state; }
    public List<StageConfig> stages() { return List.copyOf(stages); }
    public int currentStageIndex() { return currentStageIndex; }
    public long revision() { return revision; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public Instant stageEnteredAt() { return stageEnteredAt; }
    public List<HealthSample> samples() { return List.copyOf(samples); }
    public List<Decision> decisions() { return List.copyOf(decisions); }
    public int attemptsFor(int stageIndex) { return stageAttempts.getOrDefault(stageIndex, 0); }

    public StageConfig currentStage() {
        return currentStageIndex >= 0 ? stages.get(currentStageIndex) : null;
    }

    public boolean isLastStage() {
        return currentStageIndex == stages.size() - 1;
    }

    /** 当前阶段观测窗口内的样本（采样时间不早于进入本阶段的时刻）。 */
    public List<HealthSample> windowSamples() {
        return samples.stream()
                .filter(s -> !s.at().isBefore(stageEnteredAt))
                .toList();
    }

    /** 当前阶段的最近一次判定，无则 null。 */
    public Decision lastDecisionForCurrentStage() {
        for (int i = decisions.size() - 1; i >= 0; i--) {
            Decision d = decisions.get(i);
            if (d.stageIndex() == currentStageIndex) {
                return d;
            }
        }
        return null;
    }

    // ---------- 变更（必须在 per-plan 锁内调用） ----------

    /** 标记一次状态变更：revision +1，刷新 updatedAt。 */
    public void touch(Instant now) {
        this.revision++;
        this.updatedAt = now;
    }

    public void transitionTo(PlanState target, Instant now) {
        this.state = target;
        touch(now);
    }

    public void enterStage(int stageIndex, Instant now) {
        this.currentStageIndex = stageIndex;
        this.stageEnteredAt = now;
        touch(now);
    }

    public void setStageWeight(int stageIndex, int weightPercent, Instant now) {
        StageConfig old = stages.get(stageIndex);
        stages.set(stageIndex, new StageConfig(weightPercent, old.minSamples(), old.observationWindowMillis()));
        touch(now);
    }

    public void addSample(HealthSample sample) {
        samples.add(sample);
    }

    /** 记录一条判定结论，并推进该阶段的判定序号。 */
    public void addDecision(Decision decision) {
        decisions.add(decision);
        stageAttempts.merge(decision.stageIndex(), 1, Integer::sum);
    }

    /** 幂等键是否已处理过（重复提交检测）。 */
    public boolean hasProcessedOp(String opKey) {
        return processedOpKeys.contains(opKey);
    }

    public void markOpProcessed(String opKey) {
        processedOpKeys.add(opKey);
    }
}
