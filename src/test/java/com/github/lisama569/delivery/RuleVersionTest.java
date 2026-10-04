package com.github.lisama569.delivery;

import com.github.lisama569.delivery.domain.ConflictRecord;
import com.github.lisama569.delivery.domain.DecisionRecord;
import com.github.lisama569.delivery.domain.FailureReason;
import com.github.lisama569.delivery.domain.HealthRules;
import com.github.lisama569.delivery.domain.OperationResult;
import com.github.lisama569.delivery.domain.PlanState;
import com.github.lisama569.delivery.domain.ReleasePlan;
import com.github.lisama569.delivery.service.HealthGate;
import com.github.lisama569.delivery.service.PlanService;
import com.github.lisama569.delivery.service.RuleRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 规则版本绑定、历史判定复现、全规格幂等/冲突留痕、受支持版本范围。 */
class RuleVersionTest {

    private RuleRegistry registry;
    private PlanService svc;

    private PlanService newService() {
        registry = new RuleRegistry();
        svc = new PlanService(new HealthGate(), registry);
        return svc;
    }

    @Test
    void boundRuleVersion_isLockedAndImmuneToLaterRuleChanges() {
        newService();
        registry.register("v1.0", new HealthRules(2, 1_000L, 0.1, 100L));
        svc.createPlan("lock", 2, List.of(50, 100), null, "v1.0");
        svc.start("lock");
        ReleasePlan plan = svc.find("lock").orElseThrow();
        System.out.println("[版本用例] 创建规格: 权重=" + plan.getStageWeights()
                + ", 绑定规则版本=" + plan.getRuleVersion() + ", 锁定规则=" + plan.getHealthRules());

        // 后续规则迭代：同名不可篡改，只能登记新版本
        assertFalse(registry.register("v1.0", new HealthRules(99, 1L, 0.99, 99999L)),
                "同名规则版本必须不可变");
        registry.register("v2.0", new HealthRules(99, 1L, 0.99, 99999L));

        // 用锁定时的 v1.0 阈值判定：错误率 0.5 > 0.1 -> 回退；若被 v2.0 带偏则会推进
        long created = plan.getCreatedAt().toEpochMilli();
        svc.reportMetric("lock", true, 50L, created + 2_000L);
        svc.reportMetric("lock", false, 60L, created + 2_100L);
        svc.autoJudge("lock", created + 3_000L);
        ReleasePlan after = svc.find("lock").orElseThrow();
        DecisionRecord record = after.getDecisions().get(0);
        System.out.println("[版本用例] 判定依据: 规则版本=" + record.ruleVersion()
                + ", 快照=" + record.rulesSnapshot()
                + ", 错误率=" + record.errorRate() + " -> 判定=" + record.verdict()
                + ", 状态=" + after.getState());
        assertEquals("v1.0", record.ruleVersion());
        assertEquals(DecisionRecord.Verdict.ROLLBACK, record.verdict(),
                "判定必须按锁定的 v1.0 规则，不受后续 v2.0 影响");
        assertEquals(PlanState.ROLLED_BACK, after.getState());
    }

    @Test
    void historicalDecision_isReproducibleWithBoundVersionSnapshot() {
        newService();
        registry.register("r1", new HealthRules(3, 1_000L, 0.5, 1000L));
        svc.createPlan("hist", 2, List.of(50, 100), null, "r1");
        svc.start("hist");
        ReleasePlan plan = svc.find("hist").orElseThrow();
        long created = plan.getCreatedAt().toEpochMilli();
        for (int i = 0; i < 3; i++) {
            svc.reportMetric("hist", false, 100L, created + 2_000L + i);
        }
        svc.autoJudge("hist", created + 3_000L);
        DecisionRecord historical = svc.find("hist").orElseThrow().getDecisions().get(0);

        // 规则演进后用历史快照重放同样本，结论必须一致
        registry.register("r2", new HealthRules(3, 1_000L, 0.0, 1L));
        ReleasePlan replay = new ReleasePlan("hist", 2, List.of(50, 100),
                historical.rulesSnapshot(), historical.ruleVersion(), plan.getCreatedAt());
        plan.getSamples().forEach(s -> replay.getSamples().add(s));
        DecisionRecord replayed = new HealthGate().evaluate(replay, created + 3_000L);
        System.out.println("[复现用例] 绑定版本=" + historical.ruleVersion()
                + ", 历史判定=" + historical.verdict() + ", 快照重放=" + replayed.verdict());
        assertEquals(historical.verdict(), replayed.verdict(), "历史判定必须可按绑定版本快照复现");
        assertEquals("r1", replayed.ruleVersion());
    }

    @Test
    void sameFullSpec_isIdempotent_anyDifferenceIsConflictAndQueryable() {
        newService();
        registry.register("a", new HealthRules(3, 1_000L, 0.5, 1000L));
        registry.register("b", new HealthRules(5, 2_000L, 0.1, 100L));

        OperationResult<ReleasePlan> first = svc.createPlan("spec", 2, List.of(25, 100), null, "a");
        OperationResult<ReleasePlan> dup = svc.createPlan("spec", 2, List.of(25, 100), null, "a");
        System.out.println("[幂等用例] 全规格一致重复提交, ok=" + dup.ok()
                + ", 同一实例=" + (first.value() == dup.value()));
        assertTrue(dup.ok());
        assertTrue(first.value() == dup.value());
        assertTrue(svc.conflicts("spec").isEmpty(), "幂等重复提交不得产生冲突留痕");

        // 仅规则版本不同 -> 冲突
        OperationResult<ReleasePlan> diffRule = svc.createPlan("spec", 2, List.of(25, 100), null, "b");
        System.out.println("[冲突用例] 规则版本不同(a->b), ok=" + diffRule.ok()
                + ", 原因=" + diffRule.reason());
        assertEquals(FailureReason.CONFLICTING_SUBMISSION, diffRule.reason());

        // 仅权重不同 -> 冲突
        OperationResult<ReleasePlan> diffWeight = svc.createPlan("spec", 2, List.of(50, 100), null, "a");
        assertEquals(FailureReason.CONFLICTING_SUBMISSION, diffWeight.reason());

        // 仅计划版本不同 -> 冲突
        OperationResult<ReleasePlan> diffSpec = svc.createPlan("spec", 1, List.of(25, 100), null, "a");
        assertEquals(FailureReason.CONFLICTING_SUBMISSION, diffSpec.reason());

        List<ConflictRecord> records = svc.conflicts("spec");
        System.out.println("[冲突用例] 留痕条数=" + records.size() + ", 明细=" + records);
        assertEquals(3, records.size(), "每次冲突提交都必须留痕");
        assertTrue(svc.find("spec").orElseThrow().isConflictMarked());
        assertEquals(List.of(25, 100), svc.find("spec").orElseThrow().getStageWeights(),
                "冲突不得覆盖原计划");
        assertEquals("a", svc.find("spec").orElseThrow().getRuleVersion());
    }

    @Test
    void unregisteredRuleVersion_isRejectedDistinguishably() {
        newService();
        OperationResult<ReleasePlan> r = svc.createPlan("ghost-rule", 2, List.of(50, 100), null, "nope");
        System.out.println("[版本用例] 未登记规则版本, ok=" + r.ok() + ", 原因=" + r.reason()
                + ", 依据=" + r.message());
        assertFalse(r.ok());
        assertEquals(FailureReason.RULE_VERSION_NOT_FOUND, r.reason());
        assertTrue(svc.find("ghost-rule").isEmpty(), "被拒绝的创建不得留下计划");
    }

    @Test
    void outOfRangeSpecVersion_isRejectedDistinguishably_andInRangeDefaultsAreStable() {
        newService();
        for (int v : new int[]{0, -1, 3, 99}) {
            OperationResult<ReleasePlan> r = svc.createPlan("sv" + v, v, List.of(50, 100), null);
            System.out.println("[范围用例] specVersion=" + v + ", ok=" + r.ok()
                    + ", 原因=" + r.reason() + ", 依据=" + r.message());
            assertFalse(r.ok());
            assertEquals(FailureReason.UNSUPPORTED_SPEC_VERSION, r.reason(),
                    "超出支持范围的版本必须被拒绝且原因可区分");
            assertTrue(svc.find("sv" + v).isEmpty());
        }
        // 范围内旧版本缺省行为固定：V1 不带判定参数 -> 稳定缺省规则，多次运行一致
        svc.createPlan("inrange1", 1, List.of(50, 100), null);
        svc.createPlan("inrange2", 1, List.of(50, 100), null);
        HealthRules r1 = svc.find("inrange1").orElseThrow().getHealthRules();
        HealthRules r2 = svc.find("inrange2").orElseThrow().getHealthRules();
        System.out.println("[范围用例] V1 缺省规则: 第一次=" + r1 + ", 第二次=" + r2);
        assertEquals(HealthRules.DEFAULT, r1);
        assertEquals(r1, r2, "范围内旧版本缺省行为必须固定可预期");
    }

    @Test
    void concurrentConflictingCreates_exactlyOneSpecWinsAndRestAreRecorded() throws Exception {
        newService();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<OperationResult<ReleasePlan>>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int n = i;
            // 每个线程提交不同权重规格，同 planId
            tasks.add(() -> svc.createPlan("race", 2, List.of(10 + n, 100), null));
        }
        List<Future<OperationResult<ReleasePlan>>> futures = pool.invokeAll(tasks);
        pool.shutdown();

        int ok = 0;
        int conflicted = 0;
        for (Future<OperationResult<ReleasePlan>> f : futures) {
            OperationResult<ReleasePlan> r = f.get();
            System.out.println("[并发冲突用例] ok=" + r.ok()
                    + (r.ok() ? "" : ", 原因=" + r.reason()));
            if (r.ok()) {
                ok++;
            } else {
                assertEquals(FailureReason.CONFLICTING_SUBMISSION, r.reason());
                conflicted++;
            }
        }
        List<ConflictRecord> records = svc.conflicts("race");
        System.out.println("[并发冲突用例] 生效规格=" + svc.find("race").orElseThrow().spec()
                + ", 成功数=" + ok + ", 冲突数=" + conflicted + ", 留痕数=" + records.size());
        assertEquals(1, ok, "并发下同 id 只能有一个规格生效");
        assertEquals(threads - 1, conflicted);
        assertEquals(threads - 1, records.size(), "每个失败提交都必须留痕，不能丢记录");
        assertTrue(svc.find("race").orElseThrow().isConflictMarked());
    }
}
