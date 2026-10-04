package com.github.lisama569.delivery;

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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 指标不足、窗口未结束不得推进；越界必须回退；同阶段重复判定幂等；V1 缺省语义稳定可复现。 */
class HealthGateTest {

    private PlanService newService() {
        return new PlanService(new HealthGate(), new RuleRegistry());
    }

    private ReleasePlan runningPlan(PlanService svc, String id, HealthRules rules) {
        svc.createPlan(id, 2, List.of(10, 50, 100), rules);
        svc.start(id);
        return svc.find(id).orElseThrow();
    }

    @Test
    void windowNotClosed_andInsufficientSamples_doNotAdvance() {
        PlanService svc = newService();
        HealthRules rules = new HealthRules(3, 60_000L, 0.5, 1000L);
        ReleasePlan plan = runningPlan(svc, "h1", rules);
        long created = plan.getCreatedAt().toEpochMilli();

        OperationResult<ReleasePlan> windowOpen = svc.autoJudge("h1", created + 1_000L);
        System.out.println("[判定用例] 窗口未结束, 原因=" + windowOpen.reason() + ", 依据=" + windowOpen.message());
        assertEquals(FailureReason.WINDOW_NOT_CLOSED, windowOpen.reason());
        assertEquals(0, svc.find("h1").orElseThrow().getCurrentStageIndex(), "窗口未结束不得推进");

        // 窗口结束后只上报 2 条样本（要求 3 条）
        svc.reportMetric("h1", false, 100L, created + 61_000L);
        svc.reportMetric("h1", false, 120L, created + 61_100L);
        OperationResult<ReleasePlan> insufficient = svc.autoJudge("h1", created + 61_200L);
        System.out.println("[判定用例] 样本不足(2<3), 原因=" + insufficient.reason() + ", 依据=" + insufficient.message());
        assertEquals(FailureReason.INSUFFICIENT_SAMPLES, insufficient.reason());
        assertEquals(0, svc.find("h1").orElseThrow().getCurrentStageIndex(), "样本不足不得推进");
    }

    @Test
    void healthyMetrics_advance_andRepeatJudgeIsIdempotent() {
        PlanService svc = newService();
        HealthRules rules = new HealthRules(3, 1_000L, 0.5, 1000L);
        ReleasePlan plan = runningPlan(svc, "h2", rules);
        long created = plan.getCreatedAt().toEpochMilli();

        for (int i = 0; i < 3; i++) {
            svc.reportMetric("h2", false, 100L, created + 2_000L + i);
        }
        OperationResult<ReleasePlan> judged = svc.autoJudge("h2", created + 3_000L);
        ReleasePlan after = svc.find("h2").orElseThrow();
        System.out.println("[判定用例] 健康指标, 推进到阶段=" + after.getCurrentStageIndex()
                + ", 判定记录=" + after.getDecisions());
        assertTrue(judged.ok());
        assertEquals(1, after.getCurrentStageIndex());
        assertEquals(1, after.getDecisions().size());

        // 同阶段重复判定：已判定过的阶段 0 不会重复扣减；当前阶段 1 无样本 -> 失败但不重复记录阶段 0
        OperationResult<ReleasePlan> repeat = svc.autoJudge("h2", created + 4_000L);
        ReleasePlan afterRepeat = svc.find("h2").orElseThrow();
        System.out.println("[判定用例] 重复判定, ok=" + repeat.ok() + ", 原因=" + repeat.reason()
                + ", 阶段=" + afterRepeat.getCurrentStageIndex()
                + ", 判定记录数=" + afterRepeat.getDecisions().size());
        assertEquals(1, afterRepeat.getCurrentStageIndex(), "重复判定不得造成重复推进");
        assertEquals(1, afterRepeat.getJudgedStages().size(), "阶段 0 只判定一次");
    }

    @Test
    void breachingMetrics_triggerRollback_andRepeatJudgeDoesNotRollbackTwice() {
        PlanService svc = newService();
        HealthRules rules = new HealthRules(2, 1_000L, 0.1, 100L);
        ReleasePlan plan = runningPlan(svc, "h3", rules);
        long created = plan.getCreatedAt().toEpochMilli();

        svc.reportMetric("h3", true, 50L, created + 2_000L);   // 错误率 0.5 > 0.1
        svc.reportMetric("h3", false, 500L, created + 2_100L); // 延迟 500 > 100
        OperationResult<ReleasePlan> judged = svc.autoJudge("h3", created + 3_000L);
        ReleasePlan after = svc.find("h3").orElseThrow();
        DecisionRecord record = after.getDecisions().get(0);
        System.out.println("[回退用例] 输入: 错误率=" + record.errorRate() + ", 最大延迟=" + record.maxLatencyMillis()
                + ", 阈值: maxErrorRate=" + record.rulesSnapshot().maxErrorRate()
                + ", maxLatency=" + record.rulesSnapshot().maxLatencyMillis()
                + " -> 判定=" + record.verdict() + ", 状态=" + after.getState());
        assertEquals(DecisionRecord.Verdict.ROLLBACK, record.verdict());
        assertEquals(PlanState.ROLLED_BACK, after.getState());

        OperationResult<ReleasePlan> repeat = svc.autoJudge("h3", created + 4_000L);
        System.out.println("[回退用例] 回退后重复判定, ok=" + repeat.ok() + ", 原因=" + repeat.reason());
        assertEquals(FailureReason.PLAN_ALREADY_FINISHED, repeat.reason());
        assertEquals(1, after.getDecisions().size(), "不得重复回退产生第二条判定");
    }

    @Test
    void v1Plan_usesStableDefaultRules_andHistoricalDecisionsAreReproducible() {
        PlanService svc = newService();
        // V1 计划：不显式给规则 -> 缺省语义
        svc.createPlan("v1", 1, List.of(50, 100), null);
        svc.start("v1");
        ReleasePlan plan = svc.find("v1").orElseThrow();
        assertEquals(HealthRules.DEFAULT, plan.getHealthRules(), "V1 缺省语义必须稳定为 DEFAULT");
        System.out.println("[兼容用例] V1 计划缺省规则=" + plan.getHealthRules());

        long created = plan.getCreatedAt().toEpochMilli();
        for (int i = 0; i < 3; i++) {
            svc.reportMetric("v1", false, 100L, created + 61_000L + i);
        }
        svc.autoJudge("v1", created + 61_100L);
        DecisionRecord historical = svc.find("v1").orElseThrow().getDecisions().get(0);

        // 规则更新后（构造新规则），用历史记录中的规则快照重放，结论必须一致
        HealthGate gate = new HealthGate();
        ReleasePlan replay = new ReleasePlan("v1", 1, List.of(50, 100),
                historical.rulesSnapshot(), plan.getCreatedAt());
        svc.find("v1").orElseThrow(); // 原样本仍在
        plan.getSamples().forEach(s -> replay.getSamples().add(s));
        DecisionRecord replayed = gate.evaluate(replay, created + 61_100L);
        System.out.println("[兼容用例] 历史判定=" + historical.verdict() + ", 快照重放=" + replayed.verdict()
                + ", 快照规则=" + historical.rulesSnapshot());
        assertEquals(historical.verdict(), replayed.verdict(), "历史判定结论必须可复现");
        assertEquals(HealthRules.DEFAULT, historical.rulesSnapshot());
    }
}
