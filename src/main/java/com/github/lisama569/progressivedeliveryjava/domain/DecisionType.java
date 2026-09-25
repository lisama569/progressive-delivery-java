package com.github.lisama569.progressivedeliveryjava.domain;

public enum DecisionType {
    /** 指标越界，触发回退 */
    ROLLBACK,
    /** 判定通过，推进到下一阶段 */
    ADVANCE,
    /** 已是最后阶段，判定通过后计划完成 */
    COMPLETE,
    /** 样本不足，本次不推进 */
    HOLD_INSUFFICIENT_SAMPLES,
    /** 观测窗口未结束，本次不推进 */
    HOLD_WINDOW_NOT_ELAPSED
}
