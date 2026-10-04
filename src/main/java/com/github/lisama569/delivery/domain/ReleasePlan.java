package com.github.lisama569.delivery.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 发布计划（内存态）。权重约束：每个阶段权重在 (0,100]，单调递增，末阶段必须为 100。
 */
public class ReleasePlan {

    /** 当前规格版本：V3 起支持绑定规则版本；受支持范围见 PlanService。 */
    public static final int CURRENT_SPEC_VERSION = 3;

    private final String planId;
    private final int specVersion;
    private final List<Integer> stageWeights;
    private final HealthRules healthRules;
    private final String ruleVersion;
    private final PlanSpec spec;
    private final Instant createdAt;

    private PlanState state = PlanState.DRAFT;
    private int currentStageIndex = 0;
    private boolean conflictMarked = false;
    private final List<MetricSample> samples = new ArrayList<>();
    private int stageSampleOffset = 0;
    private final List<DecisionRecord> decisions = new ArrayList<>();
    private final List<Integer> judgedStages = new ArrayList<>();

    public ReleasePlan(String planId, int specVersion, List<Integer> stageWeights, HealthRules healthRules, Instant createdAt) {
        this(planId, specVersion, stageWeights, healthRules, null, createdAt);
    }

    public ReleasePlan(String planId, int specVersion, List<Integer> stageWeights, HealthRules healthRules,
                       String ruleVersion, Instant createdAt) {
        this(planId, new PlanSpec(specVersion, stageWeights, healthRules, ruleVersion), healthRules, createdAt);
    }

    /**
     * @param submittedSpec  提交时的原始规格（用于幂等/冲突判定）
     * @param effectiveRules 解析后的生效规则（锁定快照，后续判定与回退都按它走）
     */
    public ReleasePlan(String planId, PlanSpec submittedSpec, HealthRules effectiveRules, Instant createdAt) {
        this.planId = planId;
        this.specVersion = submittedSpec.specVersion();
        this.stageWeights = List.copyOf(submittedSpec.stageWeights());
        this.healthRules = effectiveRules;
        this.ruleVersion = submittedSpec.ruleVersion();
        this.spec = submittedSpec;
        this.createdAt = createdAt;
    }

    public String getPlanId() { return planId; }
    public int getSpecVersion() { return specVersion; }
    public List<Integer> getStageWeights() { return stageWeights; }
    public HealthRules getHealthRules() { return healthRules; }
    public String getRuleVersion() { return ruleVersion; }
    public PlanSpec getSpec() { return spec; }
    public Instant getCreatedAt() { return createdAt; }
    public PlanState getState() { return state; }
    public void setState(PlanState state) { this.state = state; }
    public int getCurrentStageIndex() { return currentStageIndex; }
    public void setCurrentStageIndex(int currentStageIndex) { this.currentStageIndex = currentStageIndex; }
    public boolean isConflictMarked() { return conflictMarked; }
    public void markConflict() { this.conflictMarked = true; }
    public List<MetricSample> getSamples() { return samples; }
    public int getStageSampleOffset() { return stageSampleOffset; }
    public void setStageSampleOffset(int stageSampleOffset) { this.stageSampleOffset = stageSampleOffset; }
    public List<DecisionRecord> getDecisions() { return decisions; }
    public List<Integer> getJudgedStages() { return judgedStages; }

    public int currentWeight() {
        return stageWeights.get(currentStageIndex);
    }

    public boolean isLastStage() {
        return currentStageIndex == stageWeights.size() - 1;
    }
}
