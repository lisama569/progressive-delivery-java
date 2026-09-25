package com.github.lisama569.progressivedeliveryjava.tests;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST 入口一致性测试：与服务入口共享同一编排逻辑，
 * 非法输入返回可区分的失败原因，成功路径返回锁内一致快照。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiConsistencyTest {

    private static final Logger log = LoggerFactory.getLogger(ApiConsistencyTest.class);

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("REST 创建/推进/中止全链路，非法权重返回 INVALID_WEIGHTS")
    void restEntryConsistency() throws Exception {
        String badBody = """
                {"planId":"api-bad","planVersion":2,"stages":[
                  {"weightPercent":50,"minSamples":3,"observationWindowMillis":1000},
                  {"weightPercent":40,"minSamples":3,"observationWindowMillis":1000}]}
                """;
        MvcResult rejected = mvc.perform(post("/api/plans")
                        .contentType(MediaType.APPLICATION_JSON).content(badBody))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("INVALID_WEIGHTS"))
                .andReturn();
        log.info("[restInvalidWeights] 输入权重=[50,40] 响应={}",
                rejected.getResponse().getContentAsString());

        String goodBody = """
                {"planId":"api-1","planVersion":2,"idempotencyKey":"ck-1","stages":[
                  {"weightPercent":10,"minSamples":3,"observationWindowMillis":1000},
                  {"weightPercent":100,"minSamples":3,"observationWindowMillis":1000}]}
                """;
        mvc.perform(post("/api/plans").contentType(MediaType.APPLICATION_JSON).content(goodBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("DRAFT"))
                .andExpect(jsonPath("$.replayed").value(false));

        // 重复提交同一幂等键：重放，不重复生效
        mvc.perform(post("/api/plans").contentType(MediaType.APPLICATION_JSON).content(goodBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));
        log.info("[restIdempotent] 重复创建返回 replayed=true（未重复生效）");

        mvc.perform(post("/api/plans/api-1/advance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("RUNNING"))
                .andExpect(jsonPath("$.currentStageIndex").value(0))
                .andExpect(jsonPath("$.currentWeightPercent").value(10));

        mvc.perform(post("/api/plans/api-1/abort"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ABORTED"));

        // 已结束计划再推进：409 + PLAN_ALREADY_FINISHED
        MvcResult finished = mvc.perform(post("/api/plans/api-1/advance"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("PLAN_ALREADY_FINISHED"))
                .andReturn();
        log.info("[restFinished] 已结束计划推进被拒绝 响应={}",
                finished.getResponse().getContentAsString());

        // 读入口与写入口结论一致
        MvcResult view = mvc.perform(get("/api/plans/api-1"))
                .andExpect(status().isOk())
                .andReturn();
        String body = view.getResponse().getContentAsString();
        assertTrue(body.contains("\"state\":\"ABORTED\""), "读入口必须看到一致的终态");
        log.info("[restConsistency] 读入口状态与写入口一致: {}", body);
    }

    @Test
    @DisplayName("REST 乐观版本冲突返回 409 + VERSION_CONFLICT")
    void restVersionConflict() throws Exception {
        String body = """
                {"planId":"api-2","planVersion":2,"stages":[
                  {"weightPercent":100,"minSamples":1,"observationWindowMillis":100}]}
                """;
        mvc.perform(post("/api/plans").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        MvcResult conflict = mvc.perform(post("/api/plans/api-2/advance?expectedRevision=99"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("VERSION_CONFLICT"))
                .andReturn();
        log.info("[restConflict] 过期 revision 被拒绝 响应={}",
                conflict.getResponse().getContentAsString());
        assertEquals("VERSION_CONFLICT",
                com.jayway.jsonpath.JsonPath.read(conflict.getResponse().getContentAsString(), "$.reason"));
    }
}
