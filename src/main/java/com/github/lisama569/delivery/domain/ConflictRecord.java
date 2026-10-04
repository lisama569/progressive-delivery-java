package com.github.lisama569.delivery.domain;

import java.time.Instant;

/**
 * 一次冲突提交的留痕（不可变）。同一 planId 的重复提交规格不一致时记录，
 * 原计划保留，冲突结论可查询，与“幂等重复提交”严格区分。
 */
public record ConflictRecord(
        String planId,
        PlanSpec existingSpec,
        PlanSpec attemptedSpec,
        Instant detectedAt) {
}
