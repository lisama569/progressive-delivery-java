package com.github.lisama569.progressivedeliveryjava.domain;

/**
 * 可区分的失败原因。所有被拒绝的操作都必须携带其中之一。
 */
public enum FailureReason {
    PLAN_NOT_FOUND,
    DUPLICATE_SUBMISSION,
    INVALID_WEIGHTS,
    ILLEGAL_STATE_TRANSITION,
    ILLEGAL_STAGE_JUMP,
    PLAN_ALREADY_FINISHED,
    INSUFFICIENT_SAMPLES,
    OBSERVATION_WINDOW_NOT_ELAPSED,
    METRICS_OUT_OF_BOUNDS,
    VERSION_CONFLICT,
    UNSUPPORTED_PLAN_VERSION
}
