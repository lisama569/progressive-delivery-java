package com.github.lisama569.progressivedeliveryjava.compat;

import com.github.lisama569.progressivedeliveryjava.domain.FailureReason;
import com.github.lisama569.progressivedeliveryjava.domain.RolloutException;
import com.github.lisama569.progressivedeliveryjava.domain.StageConfig;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 计划版本兼容层。
 *
 * 兼容范围（语义一旦发布即冻结，保证旧版本计划的结论确定且稳定）：
 * <ul>
 *   <li>v1：阶段仅指定 weightPercent，缺省 minSamples=3、observationWindowMillis=1000；</li>
 *   <li>v2：阶段显式携带 minSamples 与 observationWindowMillis。</li>
 * </ul>
 * 未知的更高/更低版本一律以 UNSUPPORTED_PLAN_VERSION 拒绝，不做猜测式兼容。
 */
@Component
public class PlanCompatibility {
    public static final int CURRENT_PLAN_VERSION = 2;
    public static final int MIN_SUPPORTED_PLAN_VERSION = 1;

    public static final int V1_DEFAULT_MIN_SAMPLES = 3;
    public static final long V1_DEFAULT_OBSERVATION_WINDOW_MILLIS = 1000L;

    /**
     * 归一化阶段配置：为旧版本计划填充冻结的缺省值。
     * 入参 StageConfig 中 minSamples&lt;=0 或 window&lt;=0 视为"未显式提供"，按计划版本补默认值。
     */
    public List<StageConfig> normalizeStages(int planVersion, List<StageConfig> stages) {
        checkSupported(planVersion);
        List<StageConfig> normalized = new ArrayList<>();
        for (StageConfig stage : stages) {
            int minSamples = stage.minSamples();
            long window = stage.observationWindowMillis();
            if (planVersion == 1) {
                // v1 永远忽略入参中的这两项，使用冻结缺省值
                minSamples = V1_DEFAULT_MIN_SAMPLES;
                window = V1_DEFAULT_OBSERVATION_WINDOW_MILLIS;
            }
            normalized.add(new StageConfig(stage.weightPercent(), minSamples, window));
        }
        return normalized;
    }

    public void checkSupported(int planVersion) {
        if (planVersion < MIN_SUPPORTED_PLAN_VERSION || planVersion > CURRENT_PLAN_VERSION) {
            throw new RolloutException(FailureReason.UNSUPPORTED_PLAN_VERSION,
                    "不支持的计划版本: " + planVersion
                            + "，当前兼容范围为 v" + MIN_SUPPORTED_PLAN_VERSION
                            + "..v" + CURRENT_PLAN_VERSION);
        }
    }
}
