package com.github.lisama569.progressivedeliveryjava.tests;

import com.github.lisama569.progressivedeliveryjava.compat.PlanCompatibility;
import com.github.lisama569.progressivedeliveryjava.domain.FailureReason;
import com.github.lisama569.progressivedeliveryjava.domain.PlanState;
import com.github.lisama569.progressivedeliveryjava.domain.RolloutException;
import com.github.lisama569.progressivedeliveryjava.domain.StageConfig;
import com.github.lisama569.progressivedeliveryjava.service.CreatePlanRequest;
import com.github.lisama569.progressivedeliveryjava.service.OperationResult;
import com.github.lisama569.progressivedeliveryjava.service.PlanService;
import com.github.lisama569.progressivedeliveryjava.store.PlanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 状态机、阶段跳转、重复提交与乐观版本冲突测试。
 */
class LifecycleTest {

    private static final Logger log = LoggerFactory.getLogger(LifecycleTest.class);

    private PlanService planService;

    @BeforeEach
    void setUp() {
        planService = new PlanService(new PlanRepository(), new PlanCompatibility(),
                new MutableClock(Instant.parse("2026-09-25T00:00:00Z")));
    }

    private void create(String id, String idemKey, int... weights) {
        List<StageConfig> stages = java.util.Arrays.stream(weights)
                .mapToObj(w -> new StageConfig(w, 3, 1000)).toList();
        planService.create(new CreatePlanRequest(id, 2, idemKey, stages));
    }

    private void assertFailure(String caseName, FailureReason expected, Runnable action) {
        RolloutException ex = assertThrows(RolloutException.class, action::run);
        assertEquals(expected, ex.reason(),
                "期望 " + expected + " 实际 " + ex.reason() + " msg=" + ex.getMessage());
        log.info("[{}] 已拒绝 reason={} message={}", caseName, ex.reason(), ex.getMessage());
    }

    @Test
    @DisplayName("正常状态机流转：DRAFT->RUNNING->PAUSED->RUNNING->COMPLETED")
    void happyPath() {
        create("lp-1", null, 10, 50, 100);
        var r1 = planService.advance("lp-1", null, null);
        assertEquals(PlanState.RUNNING, r1.plan().state());
        assertEquals(0, r1.plan().currentStageIndex());
        var pause = planService.pause("lp-1", null, null);
        assertEquals(PlanState.PAUSED, pause.plan().state());
        var resume = planService.resume("lp-1", null, null);
        assertEquals(PlanState.RUNNING, resume.plan().state());
        planService.advance("lp-1", null, null); // 阶段 0 -> 1
        planService.advance("lp-1", null, null); // 阶段 1 -> 2（最后阶段）
        var done = planService.advance("lp-1", null, null); // 最后阶段 -> COMPLETED
        assertEquals(PlanState.COMPLETED, done.plan().state());
        log.info("[happyPath] 状态机流转完成 revision={} 最终状态={}",
                done.plan().revision(), done.plan().state());
    }

    @Test
    @DisplayName("非法状态跳转：DRAFT 上暂停/恢复被拒绝；PAUSED 上直接推进被拒绝")
    void illegalTransitions() {
        create("lp-2", null, 10, 100);
        assertFailure("pauseFromDraft", FailureReason.ILLEGAL_STATE_TRANSITION,
                () -> planService.pause("lp-2", null, null));
        assertFailure("resumeFromDraft", FailureReason.ILLEGAL_STATE_TRANSITION,
                () -> planService.resume("lp-2", null, null));
        planService.advance("lp-2", null, null);
        planService.pause("lp-2", null, null);
        assertFailure("advanceWhilePaused", FailureReason.ILLEGAL_STATE_TRANSITION,
                () -> planService.advance("lp-2", null, null));
    }

    @Test
    @DisplayName("已结束计划的任何操作被拒绝，原因 PLAN_ALREADY_FINISHED")
    void terminalPlanRejectsOps() {
        create("lp-3", null, 10, 100);
        planService.advance("lp-3", null, null);
        planService.abort("lp-3", null, null);
        assertFailure("advanceAfterAbort", FailureReason.PLAN_ALREADY_FINISHED,
                () -> planService.advance("lp-3", null, null));
        assertFailure("pauseAfterAbort", FailureReason.PLAN_ALREADY_FINISHED,
                () -> planService.pause("lp-3", null, null));
        assertFailure("abortAfterAbort", FailureReason.PLAN_ALREADY_FINISHED,
                () -> planService.abort("lp-3", null, null));
    }

    @Test
    @DisplayName("重复提交同一 planId：相同幂等键重放不生效，不同键被拒绝")
    void duplicateSubmissionSemantics() {
        create("lp-4", "create-key", 10, 100);
        OperationResult replay = planService.create(
                new CreatePlanRequest("lp-4", 2, "create-key",
                        List.of(new StageConfig(10, 3, 1000), new StageConfig(100, 3, 1000))));
        assertTrue(replay.replayed(), "相同幂等键必须返回重放结果");
        assertEquals(0, replay.plan().revision(), "重放不得产生新变更");
        log.info("[duplicateCreate] 重复创建命中重放 revision 保持={}", replay.plan().revision());

        assertFailure("duplicateCreateDifferentKey", FailureReason.DUPLICATE_SUBMISSION,
                () -> create("lp-4", "other-key", 10, 100));
    }

    @Test
    @DisplayName("同一操作相同幂等键重复提交只生效一次")
    void idempotentOperation() {
        create("lp-5", null, 10, 100);
        OperationResult first = planService.advance("lp-5", "k-adv", null);
        OperationResult again = planService.advance("lp-5", "k-adv", null);
        assertFalse(first.replayed());
        assertTrue(again.replayed());
        assertEquals(0, again.plan().currentStageIndex(), "重复推进不得再次跳转");
        log.info("[idempotentAdvance] 首次生效 stage={} 重复重放 stage 保持={}",
                first.plan().currentStageIndex(), again.plan().currentStageIndex());
    }

    @Test
    @DisplayName("乐观版本冲突：过期 revision 的提交被明确拒绝，不静默覆盖")
    void optimisticConflict() {
        create("lp-6", null, 10, 100);
        planService.advance("lp-6", null, 0L); // revision 0 -> 2（状态+阶段各 touch 一次）
        assertFailure("staleRevision", FailureReason.VERSION_CONFLICT,
                () -> planService.pause("lp-6", null, 0L));
    }

    @Test
    @DisplayName("权重修改：只能改尚未进入的阶段，且修改后仍满足权重约束")
    void weightEditingConstraints() {
        create("lp-7", null, 10, 50, 100);
        planService.advance("lp-7", null, null); // 进入阶段 0
        assertFailure("editEnteredStage", FailureReason.ILLEGAL_STATE_TRANSITION,
                () -> planService.setWeight("lp-7", 0, 20, null, null));
        assertFailure("editToIllegalWeight", FailureReason.INVALID_WEIGHTS,
                () -> planService.setWeight("lp-7", 1, 5, null, null)); // 5 <= 当前阶段权重 10
        var ok = planService.setWeight("lp-7", 1, 30, null, null);
        assertEquals(30, ok.plan().stages().get(1).weightPercent());
        log.info("[weightEdit] 阶段 1 权重改为 30%，修订后 weights={}",
                ok.plan().stages().stream().map(StageConfig::weightPercent).toList());
    }

    @Test
    @DisplayName("不存在的计划返回 PLAN_NOT_FOUND")
    void planNotFound() {
        assertFailure("missing", FailureReason.PLAN_NOT_FOUND,
                () -> planService.advance("no-such-plan", null, null));
    }

    @Test
    @DisplayName("中止后不可恢复")
    void abortIsTerminal() {
        create("lp-8", null, 10, 100);
        planService.advance("lp-8", null, null);
        planService.abort("lp-8", null, null);
        assertFailure("resumeAfterAbort", FailureReason.PLAN_ALREADY_FINISHED,
                () -> planService.resume("lp-8", null, null));
        // 终态校验优先于状态机校验：已中止计划的一切操作统一报 PLAN_ALREADY_FINISHED
    }
}
