package com.github.lisama569.delivery.api;

import com.github.lisama569.delivery.domain.HealthRules;
import com.github.lisama569.delivery.domain.OperationResult;
import com.github.lisama569.delivery.domain.ReleasePlan;
import com.github.lisama569.delivery.service.PlanService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/plans")
public class PlanController {

    private final PlanService planService;

    public PlanController(PlanService planService) {
        this.planService = planService;
    }

    public record CreateRequest(String planId, Integer specVersion, List<Integer> stageWeights,
                                HealthRules healthRules) {
    }

    public record MetricRequest(boolean error, long latencyMillis, long timestampMillis) {
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateRequest req) {
        return toResponse(planService.createPlan(
                req.planId(),
                req.specVersion() == null ? 1 : req.specVersion(),
                req.stageWeights(),
                req.healthRules()));
    }

    @PostMapping("/{id}/start")
    public ResponseEntity<?> start(@PathVariable String id) {
        return toResponse(planService.start(id));
    }

    @PostMapping("/{id}/advance")
    public ResponseEntity<?> advance(@PathVariable String id) {
        return toResponse(planService.advance(id));
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<?> pause(@PathVariable String id) {
        return toResponse(planService.pause(id));
    }

    @PostMapping("/{id}/resume")
    public ResponseEntity<?> resume(@PathVariable String id) {
        return toResponse(planService.resume(id));
    }

    @PostMapping("/{id}/abort")
    public ResponseEntity<?> abort(@PathVariable String id) {
        return toResponse(planService.abort(id));
    }

    @PostMapping("/{id}/metrics")
    public ResponseEntity<?> reportMetric(@PathVariable String id, @RequestBody MetricRequest req) {
        return toResponse(planService.reportMetric(id, req.error(), req.latencyMillis(), req.timestampMillis()));
    }

    @PostMapping("/{id}/auto-judge")
    public ResponseEntity<?> autoJudge(@PathVariable String id,
                                       @RequestParam(required = false) Long nowMillis) {
        return toResponse(planService.autoJudge(id,
                nowMillis == null ? System.currentTimeMillis() : nowMillis));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        return planService.find(id)
                .<ResponseEntity<?>>map(p -> ResponseEntity.ok(toView(p)))
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(Map.of("reason", "PLAN_NOT_FOUND", "planId", id)));
    }

    private ResponseEntity<?> toResponse(OperationResult<ReleasePlan> result) {
        if (result.ok()) {
            return ResponseEntity.ok(toView(result.value()));
        }
        return ResponseEntity.unprocessableEntity()
                .body(Map.of("reason", result.reason().name(), "message", result.message()));
    }

    private Map<String, Object> toView(ReleasePlan p) {
        return Map.of(
                "planId", p.getPlanId(),
                "specVersion", p.getSpecVersion(),
                "state", p.getState().name(),
                "stageIndex", p.getCurrentStageIndex(),
                "currentWeight", p.currentWeight(),
                "conflictMarked", p.isConflictMarked(),
                "decisions", p.getDecisions().size());
    }
}
