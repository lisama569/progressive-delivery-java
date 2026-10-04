package com.github.lisama569.delivery.domain;

import java.util.List;

/**
 * 创建计划的完整规格（不可变）。幂等/冲突判定以整份规格一致为准：
 * 阶段权重、绑定的规则版本名、生效规则内容与计划版本全部一致才算重复提交。
 */
public record PlanSpec(
        int specVersion,
        List<Integer> stageWeights,
        String ruleVersion,
        HealthRules effectiveRules) {

    public PlanSpec {
        stageWeights = stageWeights == null ? null : List.copyOf(stageWeights);
    }
}
