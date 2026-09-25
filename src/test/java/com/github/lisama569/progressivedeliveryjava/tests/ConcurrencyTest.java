package com.github.lisama569.progressivedeliveryjava.tests;

import com.github.lisama569.progressivedeliveryjava.compat.PlanCompatibility;
import com.github.lisama569.progressivedeliveryjava.domain.Decision;
import com.github.lisama569.progressivedeliveryjava.domain.DecisionType;
import com.github.lisama569.progressivedeliveryjava.domain.FailureReason;
import com.github.lisama569.progressivedeliveryjava.domain.HealthSample;
import com.github.lisama569.progressivedeliveryjava.domain.PlanState;
import com.github.lisama569.progressivedeliveryjava.domain.RolloutException;
import com.github.lisama569.progressivedeliveryjava.domain.StageConfig;
import com.github.lisama569.progressivedeliveryjava.rules.RuleRegistry;
import com.github.lisama569.progressivedeliveryjava.service.CreatePlanRequest;
import com.github.lisama569.progressivedeliveryjava.service.JudgmentService;
import com.github.lisama569.progressivedeliveryjava.service.OperationResult;
import com.github.lisama569.progressivedeliveryjava.service.PlanService;
import com.github.lisama569.progressivedeliveryjava.store.PlanRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发测试（重点场景）。
 *
 * 要求：并发结果必须一致（终态唯一、成功次数确定），失败原因可区分，
 * 不允许出现越权跳转或意外异常。
 */
class ConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyTest.class);

    private record OpOutcome(boolean success, FailureReason reason, String state, int stage) {
    }

    private final ExecutorService pool = Executors.newFixedThreadPool(32);

    @org.junit.jupiter.api.AfterEach
    void shutdownPool() {
        pool.shutdownNow();
    }

    private PlanService newPlanService(PlanRepository repository) {
        return new PlanService(repository, new PlanCompatibility(),
                new MutableClock(Instant.parse("2026-09-25T00:00:00Z")));
    }

    private List<StageConfig> fiveStages() {
        return List.of(new StageConfig(10, 3, 1),
                new StageConfig(20, 3, 1),
                new StageConfig(30, 3, 1),
                new StageConfig(50, 3, 1),
                new StageConfig(100, 3, 1));
    }

    @Test
    @DisplayName("并发推进：20 个线程同时推进，恰好 6 次成功（启动+4次跳转+完成），其余全部 PLAN_ALREADY_FINISHED")
    void concurrentAdvance() throws Exception {
        int threads = 20;
        for (int iter = 0; iter < 30; iter++) {
            PlanRepository repository = new PlanRepository();
            PlanService service = newPlanService(repository);
            String planId = "ca-" + iter;
            service.create(new CreatePlanRequest(planId, 2, null, fiveStages()));

            var outcomes = runConcurrent(threads, () -> {
                OperationResult r = service.advance(planId, null, null);
                return new OpOutcome(true, null,
                        r.appliedState().name(), r.appliedStage());
            }, service);

            AtomicInteger successes = new AtomicInteger();
            ConcurrentLinkedQueue<FailureReason> failReasons = new ConcurrentLinkedQueue<>();
            for (var o : outcomes) {
                if (o.success) {
                    successes.incrementAndGet();
                } else {
                    failReasons.add(o.reason);
                }
            }
            PlanState finalState = service.get(planId).state();
            assertTrue(failReasons.stream().allMatch(FailureReason.PLAN_ALREADY_FINISHED::equals),
                    "并发推进的失败原因必须全部可区分为 PLAN_ALREADY_FINISHED，实际: " + failReasons);
            assertEquals(6, successes.get(),
                    "5 阶段计划恰好需要 6 次成功推进（迭代 " + iter + "）");
            assertEquals(threads - 6, failReasons.size());
            assertEquals(PlanState.COMPLETED, finalState);
        }
        log.info("[concurrentAdvance] 30 轮 x 20 线程：终态一致 COMPLETED，每轮 6 次成功/"
                + "14 次 PLAN_ALREADY_FINISHED");
    }

    @Test
    @DisplayName("并发推进与中止竞争：终态唯一（COMPLETED 或 ABORTED），中止至多成功一次，失败原因可区分")
    void concurrentAdvanceVsAbort() throws Exception {
        int perSide = 10;
        for (int iter = 0; iter < 30; iter++) {
            PlanRepository repository = new PlanRepository();
            PlanService service = newPlanService(repository);
            String planId = "cv-" + iter;
            service.create(new CreatePlanRequest(planId, 2, null, fiveStages()));

            CyclicBarrier barrier = new CyclicBarrier(perSide * 2);
            ConcurrentLinkedQueue<OpOutcome> outcomes = new ConcurrentLinkedQueue<>();
            CountDownLatch done = new CountDownLatch(perSide * 2);

            for (int i = 0; i < perSide; i++) {
                pool.submit(() -> {
                    await(barrier);
                    outcomes.add(safeCall(() -> {
                        OperationResult r = service.advance(planId, null, null);
                        return new OpOutcome(true, null,
                                r.appliedState().name(), r.appliedStage());
                    }));
                    done.countDown();
                });
                pool.submit(() -> {
                    await(barrier);
                    outcomes.add(safeCall(() -> {
                        OperationResult r = service.abort(planId, null, null);
                        return new OpOutcome(true, null,
                                r.appliedState().name(), r.appliedStage());
                    }));
                    done.countDown();
                });
            }
            assertTrue(done.await(10, TimeUnit.SECONDS));

            long abortSuccesses = outcomes.stream()
                    .filter(o -> o.success && "ABORTED".equals(o.state())).count();
            long advanceSuccesses = outcomes.stream()
                    .filter(o -> o.success && !"ABORTED".equals(o.state())).count();
            long fails = outcomes.stream().filter(o -> !o.success).count();
            boolean allFailsFinished = outcomes.stream()
                    .filter(o -> !o.success)
                    .allMatch(o -> o.reason() == FailureReason.PLAN_ALREADY_FINISHED);

            PlanState finalState = service.get(planId).state();

            assertTrue(allFailsFinished, "终态后的所有失败必须是 PLAN_ALREADY_FINISHED: " + outcomes);
            assertTrue(finalState.isTerminal(), "终态必须唯一且已结束: " + finalState);
            if (finalState == PlanState.ABORTED) {
                assertEquals(1, abortSuccesses, "中止至多成功一次（迭代 " + iter + "）");
                assertTrue(advanceSuccesses + abortSuccesses + fails == perSide * 2);
                log.info("[race iter={}] 中止抢先：推进成功 {} 次，中止成功 1 次，终态后失败 {} 次",
                        iter, advanceSuccesses, fails);
            } else {
                assertEquals(0, abortSuccesses);
                assertEquals(6, advanceSuccesses, "完成路径恰好 6 次推进成功（迭代 " + iter + "）");
                log.info("[race iter={}] 推进抢先：6 次推进完成，中止 0 次成功，终态后失败 {} 次",
                        iter, fails);
            }
        }
        log.info("[concurrentAdvanceVsAbort] 30 轮竞争：终态唯一，计数一致，失败原因均为 PLAN_ALREADY_FINISHED");
    }

    @Test
    @DisplayName("并发判定：同一阶段只允许一次生效的推进，其余判定不会重复扣减")
    void concurrentJudge() throws Exception {
        int threads = 16;
        PlanRepository repository = new PlanRepository();
        MutableClock clock = new MutableClock(Instant.parse("2026-09-25T00:00:00Z"));
        RuleRegistry rules = new RuleRegistry();
        PlanService service = new PlanService(repository, new PlanCompatibility(), clock);
        JudgmentService judgment = new JudgmentService(repository, rules, clock);
        String planId = "cj-1";
        service.create(new CreatePlanRequest(planId, 2, null,
                List.of(new StageConfig(10, 3, 1), new StageConfig(100, 3, 1))));
        service.advance(planId, null, null);
        for (int i = 0; i < 3; i++) {
            judgment.recordSample(planId, new HealthSample(0.001, 100, clock.instant()));
        }
        clock.advanceMillis(2); // 观测窗口结束

        var outcomes = runConcurrent(threads, () -> {
            Decision d = judgment.judge(planId);
            return new OpOutcome(true, null, d.type().name(),
                    service.get(planId).currentStageIndex());
        }, service);

        long advances = outcomes.stream()
                .filter(o -> o.success && "ADVANCE".equals(o.state())).count();
        long holds = outcomes.stream()
                .filter(o -> o.success && o.state().startsWith("HOLD")).count();
        long errors = outcomes.stream().filter(o -> !o.success).count();

        long recordedAdvances = judgment.decisions(planId).stream()
                .filter(d -> d.type() == DecisionType.ADVANCE).count();
        int finalStage = service.get(planId).currentStageIndex();

        assertEquals(1, advances, "并发判定中 ADVANCE 只能有一次");
        assertEquals(1, recordedAdvances, "判定记录中 ADVANCE 只能有一条（不得重复扣减）");
        assertEquals(1, finalStage, "阶段只允许前进一次");
        assertEquals(0, errors, "判定线程不应出现异常");
        log.info("[concurrentJudge] {} 个判定线程: 1 次 ADVANCE 生效, {} 次 HOLD（新窗口样本不足）, 异常 0, "
                + "最终阶段=1", threads, holds);
    }

    // ---------- 并发工具 ----------

    @FunctionalInterface
    private interface Call {
        OpOutcome call() throws Exception;
    }

    private OpOutcome safeCall(Call call) {
        try {
            return call.call();
        } catch (RolloutException e) {
            return new OpOutcome(false, e.reason(), null, -99);
        } catch (Exception e) {
            return new OpOutcome(false, null, null, -99);
        }
    }

    private void await(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception e) {
            Thread.currentThread().interrupt();
        }
    }

    private ConcurrentLinkedQueue<OpOutcome> runConcurrent(int threads, Call call,
                                                           PlanService ignored) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ConcurrentLinkedQueue<OpOutcome> outcomes = new ConcurrentLinkedQueue<>();
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                await(barrier);
                outcomes.add(safeCall(call));
                done.countDown();
            });
        }
        assertTrue(done.await(10, TimeUnit.SECONDS));
        return outcomes;
    }
}
