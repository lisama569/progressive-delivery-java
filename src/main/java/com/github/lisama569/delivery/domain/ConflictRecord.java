package com.github.lisama569.delivery.domain;

import java.time.Instant;

/**
 * 一次冲突提交的留痕：同 planId 已存在且整份规格不一致时记录，
 * 原计划保留不动，冲突结论可查询。
 */
public record ConflictRecord(String planId, PlanSpec existingSpec, PlanSpec submittedSpec,
                             Instant detectedAt) {
}
