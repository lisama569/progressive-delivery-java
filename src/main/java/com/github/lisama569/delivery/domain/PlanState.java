package com.github.lisama569.delivery.domain;

/**
 * 发布计划状态机：
 * DRAFT -> RUNNING -> (PAUSED -> RUNNING)* -> COMPLETED
 * RUNNING/PAUSED -> ABORTED
 * RUNNING -> ROLLED_BACK (健康指标越界触发)
 * COMPLETED / ABORTED / ROLLED_BACK 为终态。
 */
public enum PlanState {
    DRAFT,
    RUNNING,
    PAUSED,
    COMPLETED,
    ABORTED,
    ROLLED_BACK;

    public boolean isTerminal() {
        return this == COMPLETED || this == ABORTED || this == ROLLED_BACK;
    }
}
