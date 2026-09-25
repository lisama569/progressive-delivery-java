package com.github.lisama569.delivery;

import com.github.lisama569.delivery.domain.FailureReason;
import com.github.lisama569.delivery.domain.OperationResult;
import com.github.lisama569.delivery.domain.PlanState;
import com.github.lisama569.delivery.domain.ReleasePlan;
import com.github.lisama569.delivery.service.HealthGate;
import com.github.lisama569.delivery.service.PlanService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 并发推进与中止：结果必须一致，失败原因可区分。 */
class PlanServiceConcurrencyTest {

    private PlanService newService() {
        return new PlanService(new HealthGate());
    }

    @Test
    void concurrentAdvanceAndAbort_exactlyOneAbortWinsAndRestFailDistinguishably() throws Exception {
        PlanService svc = newService();
        svc.createPlan("p1", 2, List.of(10, 50, 100), null);
        svc.start("p1");

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<OperationResult<ReleasePlan>>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int n = i;
            tasks.add(() -> (n % 2 == 0) ? svc.abort("p1") : svc.advance("p1"));
        }
        List<Future<OperationResult<ReleasePlan>>> futures = pool.invokeAll(tasks);
        pool.shutdown();

        int okCount = 0;
        int finishedFailures = 0;
        for (Future<OperationResult<ReleasePlan>> f : futures) {
            OperationResult<ReleasePlan> r = f.get();
            System.out.println("[并发用例] 输入=advance/abort 混合, 结果 ok=" + r.ok()
                    + (r.ok() ? "" : ", 失败原因=" + r.reason() + ", 依据=" + r.message()));
            if (r.ok()) {
                okCount++;
            } else {
                assertEquals(FailureReason.PLAN_ALREADY_FINISHED, r.reason(),
                        "中止生效后其余操作必须以 PLAN_ALREADY_FINISHED 失败");
                finishedFailures++;
            }
        }
        // 第一个 abort 成功后计划即终态，后续全部失败；成功数 >= 1 且全部成功发生在中止前
        assertTrue(okCount >= 1, "至少首个操作成功");
        assertEquals(threads - okCount, finishedFailures);
        assertEquals(PlanState.ABORTED, svc.find("p1").orElseThrow().getState(),
                "并发后最终状态必须一致为 ABORTED");
        System.out.println("[并发用例] 最终状态=" + svc.find("p1").orElseThrow().getState()
                + ", 成功数=" + okCount + ", 终态失败数=" + finishedFailures);
    }

    @Test
    void duplicateCreate_isIdempotent_andConflictingCreateIsMarked() {
        PlanService svc = newService();
        OperationResult<ReleasePlan> first = svc.createPlan("dup", 2, List.of(25, 100), null);
        OperationResult<ReleasePlan> again = svc.createPlan("dup", 2, List.of(25, 100), null);
        System.out.println("[幂等用例] 重复提交同规格, ok=" + again.ok()
                + ", 同一实例=" + (first.value() == again.value()));
        assertTrue(again.ok());
        assertTrue(first.value() == again.value(), "重复提交必须返回同一计划，不重复生效");

        OperationResult<ReleasePlan> conflict = svc.createPlan("dup", 2, List.of(50, 100), null);
        System.out.println("[冲突用例] 同 id 不同规格, ok=" + conflict.ok()
                + ", 原因=" + conflict.reason() + ", 依据=" + conflict.message());
        assertEquals(FailureReason.CONFLICTING_SUBMISSION, conflict.reason());
        assertTrue(svc.find("dup").orElseThrow().isConflictMarked(), "冲突必须被明确标记");
        assertEquals(List.of(25, 100), svc.find("dup").orElseThrow().getStageWeights(),
                "冲突提交不得静默覆盖原计划");
    }
}
