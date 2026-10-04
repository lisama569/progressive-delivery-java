package com.github.lisama569.delivery;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** REST 入口冒烟：创建 -> 启动 -> 推进 -> 查询，验证不同入口结论一致。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DeliveryApplicationTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    @SuppressWarnings("unchecked")
    void restFlow_createStartAdvanceQuery() {
        Map<String, Object> create = Map.of(
                "planId", "rest1", "specVersion", 2, "stageWeights", List.of(20, 100));
        Map<?, ?> created = rest.postForObject("/api/plans", create, Map.class);
        System.out.println("[REST用例] 创建响应=" + created);
        assertEquals("DRAFT", created.get("state"));

        Map<?, ?> started = rest.postForObject("/api/plans/rest1/start", null, Map.class);
        assertEquals("RUNNING", started.get("state"));

        Map<?, ?> advanced = rest.postForObject("/api/plans/rest1/advance", null, Map.class);
        System.out.println("[REST用例] 推进后=" + advanced);
        assertEquals(1, advanced.get("stageIndex"));
        assertEquals(100, advanced.get("currentWeight"));

        Map<?, ?> queried = rest.getForObject("/api/plans/rest1", Map.class);
        assertEquals(advanced.get("state"), queried.get("state"), "不同入口查询结论必须一致");
        assertNotNull(queried.get("decisions"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void restFlow_ruleVersionBindingAndRejection() {
        Map<String, Object> rule = Map.of("name", "web-v1", "rules",
                Map.of("minSamples", 2, "windowMillis", 1000, "maxErrorRate", 0.2, "maxLatencyMillis", 300));
        Map<?, ?> registered = rest.postForObject("/api/rules", rule, Map.class);
        System.out.println("[REST用例] 规则登记响应=" + registered);
        assertEquals("web-v1", registered.get("name"));

        Map<String, Object> create = Map.of("planId", "rest2", "specVersion", 2,
                "stageWeights", List.of(50, 100), "ruleVersion", "web-v1");
        Map<?, ?> created = rest.postForObject("/api/plans", create, Map.class);
        System.out.println("[REST用例] 绑定规则版本创建=" + created);
        assertEquals("web-v1", created.get("ruleVersion"));

        // 未登记版本必须被明确拒绝，reason 可区分
        Map<String, Object> bad = Map.of("planId", "rest3", "specVersion", 2,
                "stageWeights", List.of(50, 100), "ruleVersion", "missing");
        var rejected = rest.postForEntity("/api/plans", bad, Map.class);
        System.out.println("[REST用例] 未登记版本拒绝=" + rejected.getStatusCode() + " " + rejected.getBody());
        assertEquals(422, rejected.getStatusCode().value());
        assertEquals("RULE_VERSION_NOT_FOUND", rejected.getBody().get("reason"));

        // 超范围计划版本必须被明确拒绝
        Map<String, Object> outOfRange = Map.of("planId", "rest4", "specVersion", 99,
                "stageWeights", List.of(50, 100));
        var rejectedRange = rest.postForEntity("/api/plans", outOfRange, Map.class);
        System.out.println("[REST用例] 超范围版本拒绝=" + rejectedRange.getStatusCode() + " " + rejectedRange.getBody());
        assertEquals(422, rejectedRange.getStatusCode().value());
        assertEquals("UNSUPPORTED_SPEC_VERSION", rejectedRange.getBody().get("reason"));
    }
}
