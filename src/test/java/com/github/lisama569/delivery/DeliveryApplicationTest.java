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
}
