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
 * 自动判定测试：窗口未结束/样本不足不得推进，越界必须回退，重复判定不重复生效。
 */
class JudgmentTest {

    private static final Logger log = LoggerFactory.getLogger(JudgmentTest.class);

    private PlanService planService;
    private JudgmentService judgmentService;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-09-25T00:00:00Z"));
        PlanRepository repository = new PlanRepository();
        RuleRegistry rules = new RuleRegistry();
        planService = new PlanService(repository, new PlanCompatibility(), clock);
        judgmentService = new JudgmentService(repository, rules, clock);
    }

    /** 两阶段计划：阶段0(minSamples=3, window=1000ms)，阶段1(minSamples=2, window=500ms) */
    private void createAndStart(String id) {
        planService.create(new CreatePlanRequest(id, 2, null, List.of(
                new StageConfig(10, 3, 1000),
                new StageConfig(100, 2, 500))));
        planService.advance(id, null, null);
    }

    private void goodSamples(String id, int n) {
        for (int i = 0; i < n; i++) {
            judgmentService.recordSample(id, new HealthSample(0.001, 100.0, clock.instant()));
        }
    }

    private void logDecision(String caseName, Decision d) {
        log.info("[{}] 判定结论 type={} stage={} attempt={} ruleVersion=v{} samples={} 依据: {}",
                caseName, d.type(), d.stageIndex(), d.attempt(), d.ruleVersion(),
                d.sampleCount(), d.reason());
    }

    @Test
    @DisplayName("观测窗口未结束：即使样本充足也不得推进")
    void windowNotElapsed() {
        createAndStart("j-1");
        goodSamples("j-1", 3);
        clock.advanceMillis(999); // 窗口要求 1000ms
        log.info("[windowNotElapsed] 输入: stage=0, 样本=3, 已观测=999ms < 1000ms");
        Decision d = judgmentService.judge("j-1");
        logDecision("windowNotElapsed", d);
        assertEquals(DecisionType.HOLD_WINDOW_NOT_ELAPSED, d.type());
        assertEquals(0, planService.get("j-1").currentStageIndex(), "窗口未结束不得推进");
    }

    @Test
    @DisplayName("样本不足：窗口结束也不得推进")
    void insufficientSamples() {
        createAndStart("j-2");
        goodSamples("j-2", 2); // 需要 3 条
        clock.advanceMillis(1000);
        log.info("[insufficientSamples] 输入: stage=0, 样本=2 < minSamples=3, 窗口已结束");
        Decision d = judgmentService.judge("j-2");
        logDecision("insufficientSamples", d);
        assertEquals(DecisionType.HOLD_INSUFFICIENT_SAMPLES, d.type());
        assertEquals(0, planService.get("j-2").currentStageIndex(), "样本不足不得推进");
    }

    @Test
    @DisplayName("窗口结束且样本充足且指标健康：自动推进")
    void advanceWhenHealthy() {
        createAndStart("j-3");
        goodSamples("j-3", 3);
        clock.advanceMillis(1000);
        log.info("[advanceWhenHealthy] 输入: stage=0, 样本=3, 窗口已结束, 指标全部达标");
        Decision d = judgmentService.judge("j-3");
        logDecision("advanceWhenHealthy", d);
        assertEquals(DecisionType.ADVANCE, d.type());
        assertEquals(1, planService.get("j-3").currentStageIndex());
    }

    @Test
    @DisplayName("指标越界：立即回退，不受窗口/样本数限制")
    void rollbackOnBreach() {
        createAndStart("j-4");
        judgmentService.recordSample("j-4", new HealthSample(0.05, 100.0, clock.instant())); // 错误率 5% > 1%
        log.info("[rollbackOnBreach] 输入: stage=0, 样本含 errorRate=0.05 > 阈值 0.01, 窗口未结束");
        Decision d = judgmentService.judge("j-4");
        logDecision("rollbackOnBreach", d);
        assertEquals(DecisionType.ROLLBACK, d.type());
        assertEquals(-1, planService.get("j-4").currentStageIndex(), "阶段 0 越界应回退到基线(-1)");
    }

    @Test
    @DisplayName("延迟越界同样触发回退；从阶段 1 回退到阶段 0")
    void rollbackFromLaterStage() {
        createAndStart("j-5");
        goodSamples("j-5", 3);
        clock.advanceMillis(1000);
        judgmentService.judge("j-5"); // 推进到阶段 1
        judgmentService.recordSample("j-5", new HealthSample(0.001, 800.0, clock.instant())); // P99 800 > 500
        log.info("[rollbackFromLaterStage] 输入: stage=1, 样本含 latencyP99=800 > 阈值 500");
        Decision d = judgmentService.judge("j-5");
        logDecision("rollbackFromLaterStage", d);
        assertEquals(DecisionType.ROLLBACK, d.type());
        assertEquals(0, planService.get("j-5").currentStageIndex());
    }

    @Test
    @DisplayName("同一阶段重复判定：无新样本时返回首次结论，不重复记录")
    void duplicateJudgeDedup() {
        createAndStart("j-6");
        goodSamples("j-6", 2);
        clock.advanceMillis(1000);
        Decision first = judgmentService.judge("j-6");
        Decision second = judgmentService.judge("j-6");
        logDecision("duplicateJudge#1", first);
        logDecision("duplicateJudge#2", second);
        assertSame(first, second, "无新样本的重复判定必须返回首次结论");
        assertEquals(1, judgmentService.decisions("j-6").size(), "不得重复记录判定");
    }

    @Test
    @DisplayName("回退后重复判定不会重复回退（新窗口为空，只能 HOLD）")
    void noDoubleRollback() {
        createAndStart("j-7");
        judgmentService.recordSample("j-7", new HealthSample(0.05, 100.0, clock.instant()));
        Decision first = judgmentService.judge("j-7");
        assertEquals(DecisionType.ROLLBACK, first.type());
        assertEquals(-1, planService.get("j-7").currentStageIndex());
        // 回退到基线后再次判定：基线(-1)不可判定
        RolloutException ex = assertThrows(RolloutException.class,
                () -> judgmentService.judge("j-7"));
        assertEquals(FailureReason.ILLEGAL_STATE_TRANSITION, ex.reason());
        log.info("[noDoubleRollback] 回退后 stage=-1，重复判定被拒绝 reason={}（不会重复回退）",
                ex.reason());
        assertEquals(1, judgmentService.decisions("j-7").size(), "只记录了一次回退");
    }

    @Test
    @DisplayName("最后阶段判定通过：计划完成")
    void completeAtLastStage() {
        createAndStart("j-8");
        goodSamples("j-8", 3);
        clock.advanceMillis(1000);
        judgmentService.judge("j-8"); // -> 阶段 1
        goodSamples("j-8", 2);
        clock.advanceMillis(500);
        log.info("[completeAtLastStage] 输入: stage=1(最后), 样本=2>=2, 窗口 500ms 已结束");
        Decision d = judgmentService.judge("j-8");
        logDecision("completeAtLastStage", d);
        assertEquals(DecisionType.COMPLETE, d.type());
        assertEquals(PlanState.COMPLETED, planService.get("j-8").state());
    }

    @Test
    @DisplayName("DRAFT 计划不可判定")
    void judgeOnDraftRejected() {
        planService.create(new CreatePlanRequest("j-9", 2, null,
                List.of(new StageConfig(100, 1, 100))));
        RolloutException ex = assertThrows(RolloutException.class,
                () -> judgmentService.judge("j-9"));
        assertEquals(FailureReason.ILLEGAL_STATE_TRANSITION, ex.reason());
        log.info("[judgeOnDraft] 已拒绝 reason={} message={}", ex.reason(), ex.getMessage());
    }
}
