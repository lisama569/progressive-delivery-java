package com.github.lisama569.progressivedeliveryjava.tests;

import com.github.lisama569.progressivedeliveryjava.compat.PlanCompatibility;
import com.github.lisama569.progressivedeliveryjava.domain.FailureReason;
import com.github.lisama569.progressivedeliveryjava.domain.RolloutException;
import com.github.lisama569.progressivedeliveryjava.domain.StageConfig;
import com.github.lisama569.progressivedeliveryjava.rules.RuleRegistry;
import com.github.lisama569.progressivedeliveryjava.service.CreatePlanRequest;
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
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 非法权重拒绝测试：失败原因必须可区分。
 */
class WeightValidationTest {

    private static final Logger log = LoggerFactory.getLogger(WeightValidationTest.class);

    private PlanService planService;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-09-25T00:00:00Z"));
        planService = new PlanService(new PlanRepository(), new PlanCompatibility(), clock);
    }

    private CreatePlanRequest request(String id, int... weights) {
        List<StageConfig> stages = java.util.Arrays.stream(weights)
                .mapToObj(w -> new StageConfig(w, 3, 1000))
                .toList();
        return new CreatePlanRequest(id, 2, null, stages);
    }

    private void assertRejected(String caseName, String planId, int... weights) {
        log.info("[{}] 输入权重={}", caseName, java.util.Arrays.toString(weights));
        RolloutException ex = assertThrows(RolloutException.class,
                () -> planService.create(request(planId, weights)));
        assertEquals(FailureReason.INVALID_WEIGHTS, ex.reason());
        log.info("[{}] 已拒绝 reason={} message={}", caseName, ex.reason(), ex.getMessage());
    }

    @Test
    @DisplayName("权重必须严格递增，非递增被拒绝")
    void nonIncreasingWeightsRejected() {
        assertRejected("nonIncreasing", "p-ni", 10, 10, 100);
        assertRejected("decreasing", "p-dec", 50, 30, 100);
    }

    @Test
    @DisplayName("权重必须落在 1..100")
    void outOfRangeWeightsRejected() {
        assertRejected("zero", "p-zero", 0, 50, 100);
        assertRejected("overHundred", "p-over", 10, 120, 100);
    }

    @Test
    @DisplayName("最后一个阶段必须是 100%")
    void lastStageMustBeFull() {
        assertRejected("lastNotHundred", "p-last", 10, 50, 90);
    }

    @Test
    @DisplayName("阶段列表不能为空")
    void emptyStagesRejected() {
        log.info("[emptyStages] 输入阶段列表=[]");
        RolloutException ex = assertThrows(RolloutException.class,
                () -> planService.create(new CreatePlanRequest("p-empty", 2, null, List.of())));
        assertEquals(FailureReason.INVALID_WEIGHTS, ex.reason());
        log.info("[emptyStages] 已拒绝 reason={} message={}", ex.reason(), ex.getMessage());
    }

    @Test
    @DisplayName("合法权重创建成功")
    void validWeightsAccepted() {
        log.info("[validWeights] 输入权重=[10,50,100]");
        var result = planService.create(request("p-ok", 10, 50, 100));
        assertEquals(false, result.replayed());
        assertEquals(0, result.plan().revision());
        log.info("[validWeights] 创建成功 planId={} revision={}",
                result.plan().planId(), result.plan().revision());
    }
}
