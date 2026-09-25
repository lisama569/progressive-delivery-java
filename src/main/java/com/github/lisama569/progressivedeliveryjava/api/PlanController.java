package com.github.lisama569.progressivedeliveryjava.api;

import com.github.lisama569.progressivedeliveryjava.domain.Decision;
import com.github.lisama569.progressivedeliveryjava.domain.HealthSample;
import com.github.lisama569.progressivedeliveryjava.domain.ReleasePlan;
import com.github.lisama569.progressivedeliveryjava.domain.StageConfig;
import com.github.lisama569.progressivedeliveryjava.service.CreatePlanRequest;
import com.github.lisama569.progressivedeliveryjava.service.JudgmentService;
import com.github.lisama569.progressivedeliveryjava.service.OperationResult;
import com.github.lisama569.progressivedeliveryjava.service.PlanService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 发布计划 REST 入口。与服务入口共享 PlanService/JudgmentService，
 * 保证不同入口下结论一致。
 */
@RestController
@RequestMapping("/api/plans")
public class PlanController {

    private final PlanService planService;
    private final JudgmentService judgmentService;

    public PlanController(PlanService planService, JudgmentService judgmentService) {
        this.planService = planService;
        this.judgmentService = judgmentService;
    }

    public record CreatePlanBody(String planId, Integer planVersion, String idempotencyKey,
                                 List<StageConfig> stages) {
    }

    public record SampleBody(double errorRate, double latencyP99, Instant at) {
    }

    public record PlanView(String planId, int planVersion, String state, int currentStageIndex,
                           Integer currentWeightPercent, long revision, boolean replayed) {
        /** 写操作：使用锁内捕获的快照，保证不同入口结论一致 */
        static PlanView of(OperationResult r) {
            ReleasePlan p = r.plan();
            Integer weight = r.appliedStage() >= 0
                    ? p.stages().get(r.appliedStage()).weightPercent() : null;
            return new PlanView(p.planId(), p.planVersion(), r.appliedState().name(),
                    r.appliedStage(), weight, p.revision(), r.replayed());
        }

        /** 读操作 */
        static PlanView of(ReleasePlan p) {
            return new PlanView(p.planId(), p.planVersion(), p.state().name(),
                    p.currentStageIndex(),
                    p.currentStage() == null ? null : p.currentStage().weightPercent(),
                    p.revision(), false);
        }
    }

    @PostMapping
    public PlanView create(@RequestBody CreatePlanBody body) {
        OperationResult result = planService.create(new CreatePlanRequest(
                body.planId(),
                body.planVersion() == null ? 2 : body.planVersion(),
                body.idempotencyKey(), body.stages()));
        return PlanView.of(result);
    }

    @PostMapping("/{id}/advance")
    public PlanView advance(@PathVariable String id,
                            @RequestParam(required = false) String idempotencyKey,
                            @RequestParam(required = false) Long expectedRevision) {
        OperationResult r = planService.advance(id, idempotencyKey, expectedRevision);
        return PlanView.of(r);
    }

    @PostMapping("/{id}/pause")
    public PlanView pause(@PathVariable String id,
                          @RequestParam(required = false) String idempotencyKey,
                          @RequestParam(required = false) Long expectedRevision) {
        OperationResult r = planService.pause(id, idempotencyKey, expectedRevision);
        return PlanView.of(r);
    }

    @PostMapping("/{id}/resume")
    public PlanView resume(@PathVariable String id,
                           @RequestParam(required = false) String idempotencyKey,
                           @RequestParam(required = false) Long expectedRevision) {
        OperationResult r = planService.resume(id, idempotencyKey, expectedRevision);
        return PlanView.of(r);
    }

    @PostMapping("/{id}/abort")
    public PlanView abort(@PathVariable String id,
                          @RequestParam(required = false) String idempotencyKey,
                          @RequestParam(required = false) Long expectedRevision) {
        OperationResult r = planService.abort(id, idempotencyKey, expectedRevision);
        return PlanView.of(r);
    }

    @PostMapping("/{id}/weight")
    public PlanView setWeight(@PathVariable String id,
                              @RequestParam int stageIndex,
                              @RequestParam int weightPercent,
                              @RequestParam(required = false) String idempotencyKey,
                              @RequestParam(required = false) Long expectedRevision) {
        OperationResult r = planService.setWeight(id, stageIndex, weightPercent,
                idempotencyKey, expectedRevision);
        return PlanView.of(r);
    }

    @PostMapping("/{id}/samples")
    public void recordSample(@PathVariable String id, @RequestBody SampleBody body) {
        judgmentService.recordSample(id, new HealthSample(body.errorRate(), body.latencyP99(),
                body.at() == null ? Instant.now() : body.at()));
    }

    @PostMapping("/{id}/judge")
    public Decision judge(@PathVariable String id) {
        return judgmentService.judge(id);
    }

    @GetMapping("/{id}")
    public PlanView get(@PathVariable String id) {
        return PlanView.of(planService.get(id));
    }

    @GetMapping("/{id}/decisions")
    public List<Decision> decisions(@PathVariable String id) {
        return judgmentService.decisions(id);
    }
}
