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
import com.github.lisama569.delivery.service.RuleVersionRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则版本绑定与锁定、冲突留痕、版本兼容边界。
 * 日志打印输入规格、绑定的规则版本与判定依据。
 */
class RuleVersionBindingTest {

    private PlanService newService() {
        return new PlanService(new HealthGate(), new RuleVersionRegistry());
    }

    @Test
    void boundRuleVersion_isLockedAndUnaffectedByLaterRegistryUpdates() {
        PlanService svc = newService();
        HealthRules v1Rules = new HealthRules(2, 1_000L, 0.5, 1_000L);
        svc.ruleRegistry().register("rv1", v1Rules);

        OperationResult<ReleasePlan> created = svc.createPlan("lock1", 3, List.of(50, 100), null, "rv1");
        assertTrue(created.ok());
        svc.start("lock1");
        ReleasePlan plan = svc.find("lock1").orElseThrow();
        System.out.println("[版本锁定用例] 输入规格=" + plan.getSpec()
                + ", 绑定规则版本=" + plan.getRuleVersion()
                + ", 锁定规则=" + plan.getHealthRules());
        assertEquals(v1Rules, plan.getHealthRules(), "创建时锁定 rv1 当时的规则内容");

        // 之后调整注册表中 rv1 的内容（阈值收紧到必然回退）
        svc.ruleRegistry().register("rv1", new HealthRules(2, 1_000L, 0.0, 1L));
        // 也可登记新版本，均不得影响已创建计划
        svc.ruleRegistry().register("rv2", new HealthRules(2, 1_000L, 0.0, 1L));

        long created_ = plan.getCreatedAt().toEpochMilli();
        svc.reportMetric("lock1", false, 100L, created_ + 2_000L);
        svc.reportMetric("lock1", false, 120L, created_ + 2_100L);
        OperationResult<ReleasePlan> judged = svc.autoJudge("lock1", created_ + 3_000L);
        ReleasePlan after = svc.find("lock1").orElseThrow();
        DecisionRecord record = after.getDecisions().get(0);
        System.out.println("[版本锁定用例] 注册表已调整为=" + svc.ruleRegistry().find("rv1").orElseThrow()
                + ", 判定依据(锁定快照)=" + record.rulesSnapshot()
                + ", 判定记录版本=" + record.ruleVersion()
                + ", 错误率=" + record.errorRate() + ", 最大延迟=" + record.maxLatencyMillis()
                + " -> 判定=" + record.verdict());
        assertTrue(judged.ok());
        assertEquals(DecisionRecord.Verdict.ADVANCE, record.verdict(),
                "必须按锁定时那版规则判定，不能被后来的调整带偏");
        assertEquals(v1Rules, record.rulesSnapshot());
        assertEquals("rv1", record.ruleVersion());
        assertEquals(1, after.getCurrentStageIndex());
    }

    @Test
    void historicalDecision_isReproducibleByItsBoundVersionSnapshot() {
        PlanService svc = newService();
        svc.ruleRegistry().register("rvA", new HealthRules(2, 1_000L, 0.5, 1_000L));
        svc.createPlan("replay1", 3, List.of(50, 100), null, "rvA");
        svc.start("replay1");
        ReleasePlan plan = svc.find("replay1").orElseThrow();
        long created = plan.getCreatedAt().toEpochMilli();
        svc.reportMetric("replay1", false, 100L, created + 2_000L);
        svc.reportMetric("replay1", false, 900L, created + 2_100L);
        svc.autoJudge("replay1", created + 3_000L);
        DecisionRecord historical = svc.find("replay1").orElseThrow().getDecisions().get(0);

        // 规则更新后，用历史记录中的版本快照重放同样本，结论必须一致
        svc.ruleRegistry().register("rvA", new HealthRules(2, 1_000L, 0.0, 1L));
        ReleasePlan replay = new ReleasePlan("replay1", 3, List.of(50, 100),
                historical.rulesSnapshot(), historical.ruleVersion(), plan.getCreatedAt());
        plan.getSamples().forEach(s -> replay.getSamples().add(s));
        DecisionRecord replayed = new HealthGate().evaluate(replay, created + 3_000L);
        System.out.println("[复现用例] 历史判定=" + historical.verdict()
                + ", 绑定版本=" + historical.ruleVersion()
                + ", 快照规则=" + historical.rulesSnapshot()
                + ", 重放判定=" + replayed.verdict());
        assertEquals(historical.verdict(), replayed.verdict(), "历史判定必须可按当时绑定版本复现");
        assertEquals(historical.rulesSnapshot(), replayed.rulesSnapshot());
        assertEquals(historical.ruleVersion(), replayed.ruleVersion());
    }

    @Test
    void sameSpecResubmission_isIdempotent_differentSpecIsDistinguishableConflict() {
        PlanService svc = newService();
        svc.ruleRegistry().register("rv1", new HealthRules(3, 60_000L, 0.05, 500L));
        svc.ruleRegistry().register("rv2", new HealthRules(5, 30_000L, 0.01, 200L));

        OperationResult<ReleasePlan> first = svc.createPlan("spec1", 3, List.of(25, 100), null, "rv1");
        OperationResult<ReleasePlan> dup = svc.createPlan("spec1", 3, List.of(25, 100), null, "rv1");
        System.out.println("[幂等用例] 同规格重复提交(含规则版本), ok=" + dup.ok()
                + ", 同一实例=" + (first.value() == dup.value()));
        assertTrue(dup.ok(), "整份规格完全一致必须幂等返回");
        assertTrue(first.value() == dup.value());
        assertEquals(0, svc.getConflicts("spec1").size(), "幂等提交不得产生冲突留痕");

        // 仅规则版本不同 -> 冲突
        OperationResult<ReleasePlan> diffVersion = svc.createPlan("spec1", 3, List.of(25, 100), null, "rv2");
        System.out.println("[冲突用例] 仅规则版本不同, ok=" + diffVersion.ok()
                + ", 原因=" + diffVersion.reason() + ", 依据=" + diffVersion.message());
        assertEquals(FailureReason.CONFLICTING_SUBMISSION, diffVersion.reason());

        // 仅权重不同 -> 冲突
        OperationResult<ReleasePlan> diffWeights = svc.createPlan("spec1", 3, List.of(50, 100), null, "rv1");
        assertEquals(FailureReason.CONFLICTING_SUBMISSION, diffWeights.reason());

        // 仅 specVersion 不同 -> 冲突
        OperationResult<ReleasePlan> diffSpec = svc.createPlan("spec1", 2, List.of(25, 100), null, "rv1");
        assertEquals(FailureReason.CONFLICTING_SUBMISSION, diffSpec.reason());

        List<ConflictRecord> records = svc.getConflicts("spec1");
        System.out.println("[冲突用例] 留痕条数=" + records.size() + ", 留痕=" + records);
        assertEquals(3, records.size(), "每次冲突提交都必须留痕可查");
        assertTrue(svc.find("spec1").orElseThrow().isConflictMarked());
        assertEquals("rv1", svc.find("spec1").orElseThrow().getRuleVersion(), "原计划保持不动");
        assertEquals(List.of(25, 100), svc.find("spec1").orElseThrow().getStageWeights());
        // 重复与冲突是两种可区分结论
        assertNotEquals(dup.ok(), diffVersion.ok() && diffVersion.reason() == null);
    }

    @Test
    void concurrentConflictingSubmissions_exactlyOneSpecWinsAndRestAreRecorded() throws Exception {
        PlanService svc = newService();
        svc.ruleRegistry().register("rv1", HealthRules.DEFAULT);

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<OperationResult<ReleasePlan>>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int n = i;
            // 一半提交规格 A，一半提交规格 B（规则版本不同）
            tasks.add(() -> n % 2 == 0
                    ? svc.createPlan("race", 3, List.of(25, 100), null, "rv1")
                    : svc.createPlan("race", 3, List.of(50, 100), null, null));
        }
        List<Future<OperationResult<ReleasePlan>>> futures = pool.invokeAll(tasks);
        pool.shutdown();

        int okCount = 0;
        int conflictCount = 0;
        for (Future<OperationResult<ReleasePlan>> f : futures) {
            OperationResult<ReleasePlan> r = f.get();
            if (r.ok()) {
                okCount++;
            } else {
                assertEquals(FailureReason.CONFLICTING_SUBMISSION, r.reason());
                conflictCount++;
            }
        }
        ReleasePlan winner = svc.find("race").orElseThrow();
        List<ConflictRecord> records = svc.getConflicts("race");
        System.out.println("[并发冲突用例] 生效规格=" + winner.getSpec()
                + ", 成功数=" + okCount + ", 冲突数=" + conflictCount
                + ", 留痕条数=" + records.size());
        assertTrue(okCount >= 1, "先到的规格生效，同规格重复提交幂等");
        assertEquals(threads - okCount, conflictCount, "不同规格全部判为冲突");
        assertEquals(conflictCount, records.size(), "冲突留痕一条都不能丢");
        assertEquals(PlanState.DRAFT, winner.getState(), "冲突不得把状态搞乱");
        // 生效规格唯一：所有留痕的 existingSpec 都与生效计划一致
        for (ConflictRecord rec : records) {
            assertEquals(winner.getSpec(), rec.existingSpec());
        }
    }

    @Test
    void unknownRuleVersion_isRejectedWithDistinguishableReason() {
        PlanService svc = newService();
        svc.ruleRegistry().register("rv1", HealthRules.DEFAULT);
        OperationResult<ReleasePlan> r = svc.createPlan("ghost1", 3, List.of(50, 100), null, "no-such");
        System.out.println("[版本拒绝用例] 绑定不存在版本, ok=" + r.ok()
                + ", 原因=" + r.reason() + ", 依据=" + r.message());
        assertEquals(FailureReason.RULE_VERSION_NOT_FOUND, r.reason());
        assertTrue(svc.find("ghost1").isEmpty(), "被拒绝的创建不得留下计划");
        // 与其它失败原因可区分
        OperationResult<ReleasePlan> badWeight = svc.createPlan("ghost2", 3, List.of(0, 100), null, "rv1");
        assertEquals(FailureReason.INVALID_WEIGHT, badWeight.reason());
        assertNotEquals(r.reason(), badWeight.reason());
    }

    @Test
    void outOfRangeSpecVersion_isRejected_inRangeDefaultsStayStable() {
        PlanService svc = newService();
        for (int v : new int[]{0, -1, PlanService.MAX_SUPPORTED_SPEC_VERSION + 1, 99}) {
            OperationResult<ReleasePlan> r = svc.createPlan("sv" + v, v, List.of(50, 100), null);
            System.out.println("[兼容边界用例] specVersion=" + v + ", ok=" + r.ok()
                    + ", 原因=" + r.reason() + ", 依据=" + r.message());
            assertEquals(FailureReason.UNSUPPORTED_SPEC_VERSION, r.reason(),
                    "超出受支持范围 [" + PlanService.MIN_SUPPORTED_SPEC_VERSION + ", "
                            + PlanService.MAX_SUPPORTED_SPEC_VERSION + "] 必须被拒绝");
            assertTrue(svc.find("sv" + v).isEmpty(), "超范围版本不得默默套缺省值创建成功");
        }
        // 范围内旧版本缺参 -> 固定缺省行为，多次运行一致
        for (int i = 0; i < 2; i++) {
            OperationResult<ReleasePlan> r = svc.createPlan("legacy" + i, 1, List.of(50, 100), null);
            assertTrue(r.ok());
            assertEquals(HealthRules.DEFAULT, r.value().getHealthRules(),
                    "V1 缺参必须得到固定缺省规则");
        }
        ReleasePlan a = svc.find("legacy0").orElseThrow();
        ReleasePlan b = svc.find("legacy1").orElseThrow();
        System.out.println("[兼容边界用例] V1 缺省规则 第一次=" + a.getHealthRules()
                + ", 第二次=" + b.getHealthRules());
        assertEquals(a.getHealthRules(), b.getHealthRules(), "缺省行为多次运行必须一致");
    }
}
