package com.github.lisama569.progressivedeliveryjava.rules;

import com.github.lisama569.progressivedeliveryjava.domain.FailureReason;
import com.github.lisama569.progressivedeliveryjava.domain.RolloutException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 规则注册表。
 *
 * 规则更新策略：只能注册更高版本号的规则并切换 currentVersion；
 * 历史版本永远保留——历史判定记录中带有 ruleVersion，可随时取出当时的规则
 * 重新评估同样的输入以复现结论。
 *
 * 内置 v1（冻结阈值）：错误率阈值 0.01（1%），P99 延迟阈值 500ms。
 */
@Component
public class RuleRegistry {
    public static final int V1_VERSION = 1;
    public static final double V1_MAX_ERROR_RATE = 0.01d;
    public static final double V1_MAX_LATENCY_P99 = 500.0d;

    private final Map<Integer, JudgmentRuleSet> ruleSets = new ConcurrentHashMap<>();
    private volatile int currentVersion;

    public RuleRegistry() {
        register(new DefaultJudgmentRuleSet(V1_VERSION, V1_MAX_ERROR_RATE, V1_MAX_LATENCY_P99));
        this.currentVersion = V1_VERSION;
    }

    public JudgmentRuleSet current() {
        return ruleSets.get(currentVersion);
    }

    public JudgmentRuleSet byVersion(int version) {
        JudgmentRuleSet ruleSet = ruleSets.get(version);
        if (ruleSet == null) {
            throw new RolloutException(FailureReason.UNSUPPORTED_PLAN_VERSION,
                    "规则版本不存在: v" + version);
        }
        return ruleSet;
    }

    public int currentVersion() {
        return currentVersion;
    }

    /**
     * 注册新版本规则并切换为当前版本。版本号必须严格大于当前版本；
     * 重复注册相同版本会被拒绝，防止静默覆盖。
     */
    public synchronized void register(JudgmentRuleSet ruleSet) {
        int v = ruleSet.version();
        if (ruleSets.containsKey(v)) {
            throw new RolloutException(FailureReason.VERSION_CONFLICT,
                    "规则版本 v" + v + " 已存在，规则不可被覆盖（历史结论必须可复现）");
        }
        ruleSets.put(v, ruleSet);
        if (v > currentVersion) {
            currentVersion = v;
        }
    }
}
