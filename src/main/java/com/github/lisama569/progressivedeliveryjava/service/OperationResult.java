package com.github.lisama569.progressivedeliveryjava.service;

import com.github.lisama569.progressivedeliveryjava.domain.PlanState;
import com.github.lisama569.progressivedeliveryjava.domain.ReleasePlan;

/**
 * 一次变更操作的结果。
 *
 * @param plan          计划对象（可能随后被其他线程继续推进）
 * @param replayed      是否为幂等重放（重复提交返回首次结果，未重复生效）
 * @param appliedState  操作生效（或重放命中）那一刻、在 per-plan 锁内捕获的状态
 * @param appliedStage  操作生效（或重放命中）那一刻、在锁内捕获的阶段下标
 */
public record OperationResult(ReleasePlan plan, boolean replayed,
                              PlanState appliedState, int appliedStage) {
}
