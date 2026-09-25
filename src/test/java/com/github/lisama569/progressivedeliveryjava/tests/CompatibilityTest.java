package com.github.lisama569.progressivedeliveryjava.tests;

import com.github.lisama569.progressivedeliveryjava.compat.PlanCompatibility;
import com.github.lisama569.progressivedeliveryjava.domain.Decision;
import com.github.lisama569.progressivedeliveryjava.domain.DecisionType;
import com.github.lisama569.progressivedeliveryjava.domain.FailureReason;
import com.github.lisama569.progressivedeliveryjava.domain.HealthSample;
import com.github.lisama569.progressivedeliveryjava.domain.RolloutException;
import com.github.lisama569.progressivedeliveryjava.domain.StageConfig;
import com.github.lisama569.progressivedeliveryjava.rules.DefaultJudgmentRuleSet;
import com.github.lisama569.progressivedeliveryjava.rules.RuleRegistry;
import com.github.lisama569.progressivedeliveryjava.service.CreatePlanRequest;
import com.github.lisama569.progressivedeliveryjava.service.JudgmentService;
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 计划版本兼容、规则更新与历史判定复现测试。
 */
class CompatibilityTest {

    private static final Logger log = LoggerFactory.getLogger(CompatibilityTest.class);

    private MutableClock clock;
    private PlanRepository repository;
    private RuleRegistry registry;
    private PlanService planService;
    private JudgmentService judgmentService;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-09-25T00:00:00Z"));
        repository = new PlanRepository();
        registry = new RuleRegistry();
        planService = new PlanService(repository, new PlanCompatibility(), clock);
        judgmentService = new JudgmentService(repository, registry, clock);
    }

    private Decision evaluateOnFreshStartedPlan(String planId, int ruleVersion,
                                                double errorRate, double latency) {
        // 构造与历史判定相同的输入（纯函数复现用，不触发任何状态变更）
        planService.create(new CreatePlanRequest(planId, 2, null,
                List.of(new StageConfig(10, 3, 1000), new StageConfig(100, 2, 500))));
        planService.advance(planId, null, null); // stageEnteredAt = T0
        var plan = planService.get(planId);
        List<HealthSample> samples = List.of(
                new HealthSample(errorRate, latency, clock.instant()),
                new HealthSample(errorRate, latency, clock.instant()),
                new HealthSample(errorRate, latency, clock.instant()));
        clock.advanceMillis(1000);
        return registry.byVersion(ruleVersion)
                .evaluate(plan, 0, 1, samples, clock.instant());
    }

    @Test
    @DisplayName("v1 计划缺省语义确定稳定：忽略调用方传入的 minSamples/window，使用冻结默认值")
    void v1DefaultsAreStable() {
        var created = planService.create(new CreatePlanRequest("c-1", 1, null,
                List.of(new StageConfig(10, 99, 99999), new StageConfig(100, 77, 88888))));
        var stages = created.plan().stages();
        assertEquals(PlanCompatibility.V1_DEFAULT_MIN_SAMPLES, stages.get(0).minSamples());
        assertEquals(PlanCompatibility.V1_DEFAULT_OBSERVATION_WINDOW_MILLIS,
                stages.get(0).observationWindowMillis());
        assertEquals(PlanCompatibility.V1_DEFAULT_MIN_SAMPLES, stages.get(1).minSamples());
        log.info("[v1Defaults] v1 计划归一化后 stages={}", stages);
    }

    @Test
    @DisplayName("未知计划版本被拒绝，不做猜测式兼容")
    void unsupportedPlanVersion() {
        RolloutException ex = assertThrows(RolloutException.class,
                () -> planService.create(new CreatePlanRequest("c-2", 99, null,
                        List.of(new StageConfig(100, 1, 100)))));
        assertEquals(FailureReason.UNSUPPORTED_PLAN_VERSION, ex.reason());
        log.info("[unsupportedVersion] 已拒绝 reason={} message={}", ex.reason(), ex.getMessage());
    }

    @Test
    @DisplayName("规则更新只能新增更高版本；重复注册相同版本被标记为冲突而非静默覆盖")
    void ruleVersionConflict() {
        var anotherV1 = new DefaultJudgmentRuleSet(1, 0.5, 9999);
        RolloutException ex = assertThrows(RolloutException.class,
                () -> registry.register(anotherV1));
        assertEquals(FailureReason.VERSION_CONFLICT, ex.reason());
        log.info("[ruleConflict] 已拒绝 reason={} message={}", ex.reason(), ex.getMessage());
    }

    @Test
    @DisplayName("规则更新后，历史判定结论可用当时版本规则复现")
    void historicalDecisionIsReproducible() {
        // 1) v1 规则（错误率阈值 1%）下，错误率 3% 的样本必然回退
        Decision historical = evaluateOnFreshStartedPlan("c-3", 1, 0.03, 100);
        assertEquals(DecisionType.ROLLBACK, historical.type());
        assertEquals(1, historical.ruleVersion());
        log.info("[reproduce] 历史判定(2026-09-25, v1): type={} 依据={}",
                historical.type(), historical.reason());

        // 2) 更新规则到 v2（放宽错误率阈值到 10%）
        registry.register(new DefaultJudgmentRuleSet(2, 0.10, 1000));
        assertEquals(2, registry.currentVersion());

        // 3) 同样输入在 v2 下为 ADVANCE（规则已更新）
        Decision underCurrent = evaluateOnFreshStartedPlan("c-4", 2, 0.03, 100);
        assertEquals(DecisionType.ADVANCE, underCurrent.type());
        log.info("[reproduce] 相同输入在新规则 v2 下: type={} 依据={}",
                underCurrent.type(), underCurrent.reason());

        // 4) 用历史规则版本 v1 重放相同输入，结论与历史判定完全一致
        Decision replayed = evaluateOnFreshStartedPlan("c-5", 1, 0.03, 100);
        assertEquals(historical.type(), replayed.type());
        assertEquals(historical.ruleVersion(), replayed.ruleVersion());
        log.info("[reproduce] 用 v1 规则复现历史结论: type={} ruleVersion=v{}（结论可复现）",
                replayed.type(), replayed.ruleVersion());
    }

    @Test
    @DisplayName("生产路径中判定记录携带规则版本，规则更新后老记录不变")
    void storedDecisionsKeepRuleVersion() {
        planService.create(new CreatePlanRequest("c-6", 2, null,
                List.of(new StageConfig(100, 3, 1000))));
        planService.advance("c-6", null, null);
        for (int i = 0; i < 3; i++) {
            judgmentService.recordSample("c-6", new HealthSample(0.03, 100, clock.instant()));
        }
        clock.advanceMillis(1000);
        Decision d1 = judgmentService.judge("c-6");
        assertEquals(1, d1.ruleVersion());
        registry.register(new DefaultJudgmentRuleSet(2, 0.10, 1000));
        Decision stored = judgmentService.decisions("c-6").get(0);
        assertSame(d1, stored);
        assertEquals(1, stored.ruleVersion(), "历史判定记录中的规则版本不可被改写");
        log.info("[storedRuleVersion] 规则已升级到 v2，历史记录仍为 {}",
                stored.type() + "@v" + stored.ruleVersion());
    }
}
