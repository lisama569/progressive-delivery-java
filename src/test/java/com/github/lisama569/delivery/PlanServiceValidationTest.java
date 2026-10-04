package com.github.lisama569.delivery;

import com.github.lisama569.delivery.domain.FailureReason;
import com.github.lisama569.delivery.domain.OperationResult;
import com.github.lisama569.delivery.domain.PlanState;
import com.github.lisama569.delivery.domain.ReleasePlan;
import com.github.lisama569.delivery.service.HealthGate;
import com.github.lisama569.delivery.service.PlanService;
import com.github.lisama569.delivery.service.RuleRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** 非法权重、非法状态跳转、终态重复操作必须被拒绝且原因可区分。 */
class PlanServiceValidationTest {

    private PlanService newService() {
        return new PlanService(new HealthGate(), new RuleRegistry());
    }

    @Test
    void invalidWeights_areRejectedWithInvalidWeight() {
        PlanService svc = newService();
        List<List<Integer>> bad = List.of(
                List.of(),                 // 空
                List.of(0, 100),           // 含 0
                List.of(50, 101),          // 超 100
                List.of(50, 50, 100),      // 非严格递增
                List.of(50, 80)            // 末阶段非 100
        );
        for (List<Integer> w : bad) {
            OperationResult<ReleasePlan> r = svc.createPlan("w" + w.hashCode(), 2, w, null);
            System.out.println("[权重用例] 输入=" + w + ", ok=" + r.ok() + ", 原因=" + r.reason());
            assertFalse(r.ok());
            assertEquals(FailureReason.INVALID_WEIGHT, r.reason(), "权重 " + w + " 必须被拒绝");
        }
    }

    @Test
    void illegalTransitions_areRejectedWithDistinguishableReasons() {
        PlanService svc = newService();
        svc.createPlan("t1", 2, List.of(10, 100), null);

        OperationResult<ReleasePlan> advanceDraft = svc.advance("t1");
        System.out.println("[跳转用例] DRAFT 上 advance, 原因=" + advanceDraft.reason()
                + ", 依据=" + advanceDraft.message());
        assertEquals(FailureReason.ILLEGAL_TRANSITION, advanceDraft.reason());

        OperationResult<ReleasePlan> pauseDraft = svc.pause("t1");
        assertEquals(FailureReason.ILLEGAL_TRANSITION, pauseDraft.reason());

        svc.start("t1");
        svc.pause("t1");
        OperationResult<ReleasePlan> advancePaused = svc.advance("t1");
        System.out.println("[跳转用例] PAUSED 上 advance, 原因=" + advancePaused.reason());
        assertEquals(FailureReason.ILLEGAL_TRANSITION, advancePaused.reason());

        svc.resume("t1");
        assertEquals(PlanState.RUNNING, svc.find("t1").orElseThrow().getState());
    }

    @Test
    void operationsOnFinishedPlan_areRejectedWithPlanAlreadyFinished() {
        PlanService svc = newService();
        svc.createPlan("t2", 2, List.of(100), null);
        svc.start("t2");
        svc.advance("t2"); // 末阶段 -> COMPLETED
        assertEquals(PlanState.COMPLETED, svc.find("t2").orElseThrow().getState());

        OperationResult<ReleasePlan> again = svc.advance("t2");
        OperationResult<ReleasePlan> abortAfter = svc.abort("t2");
        System.out.println("[终态用例] COMPLETED 上 advance, 原因=" + again.reason());
        System.out.println("[终态用例] COMPLETED 上 abort, 原因=" + abortAfter.reason());
        assertEquals(FailureReason.PLAN_ALREADY_FINISHED, again.reason());
        assertEquals(FailureReason.PLAN_ALREADY_FINISHED, abortAfter.reason());

        // 重复中止
        PlanService svc2 = newService();
        svc2.createPlan("t3", 2, List.of(100), null);
        svc2.start("t3");
        svc2.abort("t3");
        OperationResult<ReleasePlan> reAbort = svc2.abort("t3");
        System.out.println("[终态用例] 重复 abort, 原因=" + reAbort.reason() + ", 依据=" + reAbort.message());
        assertEquals(FailureReason.PLAN_ALREADY_FINISHED, reAbort.reason());

        OperationResult<ReleasePlan> missing = svc2.pause("ghost");
        assertEquals(FailureReason.PLAN_NOT_FOUND, missing.reason());
    }
}
