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
    void restFlow_ruleVersionBindingAndConflictTrace() {
        // 登记规则版本
        Map<String, Object> rule = Map.of("name", "rv-rest",
                "rules", Map.of("minSamples", 2, "windowMillis", 1000,
                        "maxErrorRate", 0.5, "maxLatencyMillis", 1000));
        Map<?, ?> registered = rest.postForObject("/api/rules", rule, Map.class);
        System.out.println("[REST用例] 登记规则版本响应=" + registered);
        assertEquals("rv-rest", registered.get("name"));

        // 绑定已登记版本创建计划
        Map<String, Object> create = Map.of(
                "planId", "rest2", "specVersion", 3,
                "stageWeights", List.of(50, 100), "ruleVersion", "rv-rest");
        Map<?, ?> created = rest.postForObject("/api/plans", create, Map.class);
        System.out.println("[REST用例] 绑定版本创建响应=" + created);
        assertEquals("rv-rest", created.get("ruleVersion"));

        // 绑定不存在版本 -> 可区分拒绝
        Map<String, Object> badCreate = Map.of(
                "planId", "rest3", "specVersion", 3,
                "stageWeights", List.of(50, 100), "ruleVersion", "no-such");
        var rejected = rest.postForEntity("/api/plans", badCreate, Map.class);
        System.out.println("[REST用例] 绑定不存在版本响应=" + rejected.getBody());
        assertEquals(422, rejected.getStatusCode().value());
        assertEquals("RULE_VERSION_NOT_FOUND", ((Map<?, ?>) rejected.getBody()).get("reason"));

        // 同 id 不同规格 -> 冲突，留痕可查询
        Map<String, Object> conflicting = Map.of(
                "planId", "rest2", "specVersion", 3,
                "stageWeights", List.of(30, 100), "ruleVersion", "rv-rest");
        var conflict = rest.postForEntity("/api/plans", conflicting, Map.class);
        assertEquals(422, conflict.getStatusCode().value());
        assertEquals("CONFLICTING_SUBMISSION", ((Map<?, ?>) conflict.getBody()).get("reason"));

        Map<?, ?> traces = rest.getForObject("/api/plans/rest2/conflicts", Map.class);
        System.out.println("[REST用例] 冲突留痕查询=" + traces);
        assertEquals(1, ((List<?>) traces.get("conflicts")).size(), "冲突留痕必须可经 REST 查询");
    }
}
