package com.github.lisama569.delivery.api;

import com.github.lisama569.delivery.domain.ConflictRecord;
import com.github.lisama569.delivery.domain.HealthRules;
import com.github.lisama569.delivery.domain.OperationResult;
import com.github.lisama569.delivery.domain.ReleasePlan;
import com.github.lisama569.delivery.service.PlanService;
import com.github.lisama569.delivery.service.RuleRegistry;
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
@RequestMapping("/api")
public class PlanController {

    private final PlanService planService;
    private final RuleRegistry ruleRegistry;

    public PlanController(PlanService planService, RuleRegistry ruleRegistry) {
        this.planService = planService;
        this.ruleRegistry = ruleRegistry;
    }

    public record CreateRequest(String planId, Integer specVersion, List<Integer> stageWeights,
                                HealthRules healthRules, String ruleVersion) {
    }

    public record RegisterRuleRequest(String name, HealthRules rules) {
    }

    @PostMapping("/rules")
    public ResponseEntity<?> registerRule(@RequestBody RegisterRuleRequest req) {
        if (!ruleRegistry.register(req.name(), req.rules())) {
            return ResponseEntity.unprocessableEntity()
                    .body(Map.of("reason", "RULE_VERSION_CONFLICT",
                            "message", "规则版本已存在或参数非法: " + req.name()));
        }
        return ResponseEntity.ok(Map.of("name", req.name(), "rules", req.rules()));
    }

    @GetMapping("/rules")
    public ResponseEntity<?> listRules() {
        return ResponseEntity.ok(ruleRegistry.all());
    }

    @PostMapping("/plans")
    public ResponseEntity<?> create(@RequestBody CreateRequest req) {
        return toResponse(planService.createPlan(
                req.planId(),
                req.specVersion() == null ? 1 : req.specVersion(),
                req.stageWeights(),
                req.healthRules(),
                req.ruleVersion()));
    }

    public record MetricRequest(boolean error, long latencyMillis, long timestampMillis) {
    }

    @PostMapping("/plans/{id}/start")
    public ResponseEntity<?> start(@PathVariable String id) {
        return toResponse(planService.start(id));
    }

    @PostMapping("/plans/{id}/advance")
    public ResponseEntity<?> advance(@PathVariable String id) {
        return toResponse(planService.advance(id));
    }

    @PostMapping("/plans/{id}/pause")
    public ResponseEntity<?> pause(@PathVariable String id) {
        return toResponse(planService.pause(id));
    }

    @PostMapping("/plans/{id}/resume")
    public ResponseEntity<?> resume(@PathVariable String id) {
        return toResponse(planService.resume(id));
    }

    @PostMapping("/plans/{id}/abort")
    public ResponseEntity<?> abort(@PathVariable String id) {
        return toResponse(planService.abort(id));
    }

    @PostMapping("/plans/{id}/metrics")
    public ResponseEntity<?> reportMetric(@PathVariable String id, @RequestBody MetricRequest req) {
        return toResponse(planService.reportMetric(id, req.error(), req.latencyMillis(), req.timestampMillis()));
    }

    @PostMapping("/plans/{id}/auto-judge")
    public ResponseEntity<?> autoJudge(@PathVariable String id,
                                       @RequestParam(required = false) Long nowMillis) {
        return toResponse(planService.autoJudge(id,
                nowMillis == null ? System.currentTimeMillis() : nowMillis));
    }

    @GetMapping("/plans/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        return planService.find(id)
                .<ResponseEntity<?>>map(p -> ResponseEntity.ok(toView(p)))
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(Map.of("reason", "PLAN_NOT_FOUND", "planId", id)));
    }

    @GetMapping("/plans/{id}/conflicts")
    public ResponseEntity<?> conflicts(@PathVariable String id) {
        List<ConflictRecord> records = planService.conflicts(id);
        return ResponseEntity.ok(Map.of("planId", id, "conflicts", records));
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
                "ruleVersion", p.getRuleVersion() == null ? "" : p.getRuleVersion(),
                "state", p.getState().name(),
                "stageIndex", p.getCurrentStageIndex(),
                "currentWeight", p.currentWeight(),
                "conflictMarked", p.isConflictMarked(),
                "decisions", p.getDecisions().size());
    }
}
