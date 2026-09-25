package com.github.lisama569.progressivedeliveryjava.service;

import com.github.lisama569.progressivedeliveryjava.domain.StageConfig;

import java.util.List;

/**
 * 创建发布计划的请求。
 *
 * @param planId         计划 id（业务主键，重复提交同一 id 不重复生效）
 * @param planVersion    计划版本（缺省语义见 PlanCompatibility）
 * @param idempotencyKey 幂等键（可选；同一 key 重复提交返回首次结果）
 * @param stages         阶段配置列表
 */
public record CreatePlanRequest(String planId,
                                int planVersion,
                                String idempotencyKey,
                                List<StageConfig> stages) {
}
