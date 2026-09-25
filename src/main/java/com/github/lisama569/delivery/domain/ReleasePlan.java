package com.github.lisama569.delivery.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 发布计划（内存态）。权重约束：每个阶段权重在 (0,100]，单调递增，末阶段必须为 100。
 */
public class ReleasePlan {

    public static final int CURRENT_SPEC_VERSION = 2;

    private final String planId;
    private final int specVersion;
    private final List<Integer> stageWeights;
    private final HealthRules healthRules;
    private final Instant createdAt;

    private PlanState state = PlanState.DRAFT;
    private int currentStageIndex = 0;
    private boolean conflictMarked = false;
    private final List<MetricSample> samples = new ArrayList<>();
    private int stageSampleOffset = 0;
    private final List<DecisionRecord> decisions = new ArrayList<>();
    private final List<Integer> judgedStages = new ArrayList<>();

    public ReleasePlan(String planId, int specVersion, List<Integer> stageWeights, HealthRules healthRules, Instant createdAt) {
        this.planId = planId;
        this.specVersion = specVersion;
        this.stageWeights = List.copyOf(stageWeights);
        this.healthRules = healthRules;
        this.createdAt = createdAt;
    }

    public String getPlanId() { return planId; }
    public int getSpecVersion() { return specVersion; }
    public List<Integer> getStageWeights() { return stageWeights; }
    public HealthRules getHealthRules() { return healthRules; }
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
