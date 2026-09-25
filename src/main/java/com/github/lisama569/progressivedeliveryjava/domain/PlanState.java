package com.github.lisama569.progressivedeliveryjava.domain;

/**
 * 发布计划状态机。
 * DRAFT -> RUNNING -> (PAUSED -> RUNNING)* -> COMPLETED | ABORTED
 */
public enum PlanState {
    DRAFT,
    RUNNING,
    PAUSED,
    COMPLETED,
    ABORTED;

    public boolean isTerminal() {
        return this == COMPLETED || this == ABORTED;
    }
}
