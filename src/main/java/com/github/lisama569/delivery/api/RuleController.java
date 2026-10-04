package com.github.lisama569.delivery.api;

import com.github.lisama569.delivery.domain.HealthRules;
import com.github.lisama569.delivery.service.RuleVersionRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 规则版本登记与查询入口。 */
@RestController
@RequestMapping("/api/rules")
public class RuleController {

    private final RuleVersionRegistry registry;

    public RuleController(RuleVersionRegistry registry) {
        this.registry = registry;
    }

    public record RegisterRequest(String name, HealthRules rules) {
    }

    @PostMapping
    public ResponseEntity<?> register(@RequestBody RegisterRequest req) {
        try {
            HealthRules saved = registry.register(req.name(), req.rules());
            return ResponseEntity.ok(Map.of("name", req.name(), "rules", saved));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity()
                    .body(Map.of("reason", "INVALID_RULE_VERSION", "message", e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<?> all() {
        return ResponseEntity.ok(registry.all());
    }

    @GetMapping("/{name}")
    public ResponseEntity<?> get(@PathVariable String name) {
        return registry.find(name)
                .<ResponseEntity<?>>map(r -> ResponseEntity.ok(Map.of("name", name, "rules", r)))
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(Map.of("reason", "RULE_VERSION_NOT_FOUND", "name", name)));
    }
}
