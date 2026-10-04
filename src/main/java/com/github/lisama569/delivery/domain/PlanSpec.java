package com.github.lisama569.delivery.domain;

import java.util.List;

/**
 * 创建计划时提交的完整规格（不可变）。幂等/冲突判定以整份规格完全一致为准：
 * specVersion、阶段权重、显式健康规则、绑定的规则版本，任一不同即视为冲突。
 */
public record PlanSpec(int specVersion, List<Integer> stageWeights,
                       HealthRules healthRules, String ruleVersion) {

    public PlanSpec {
        stageWeights = stageWeights == null ? null : List.copyOf(stageWeights);
    }
}
