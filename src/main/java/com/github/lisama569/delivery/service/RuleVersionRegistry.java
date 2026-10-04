package com.github.lisama569.delivery.service;

import com.github.lisama569.delivery.domain.HealthRules;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 健康判定规则的具名版本注册表。
 * 语义：
 *  - 同一套规则可登记为一个具名版本，后续可登记新版本名，也可调整已有版本的内容；
 *  - 计划创建时绑定一个已登记版本并快照当时内容，之后注册表如何调整都不影响已创建计划；
 *  - 查询不存在的版本返回空，由调用方映射为可区分的失败原因。
 */
@Component
public class RuleVersionRegistry {

    private final ConcurrentHashMap<String, HealthRules> versions = new ConcurrentHashMap<>();

    /** 登记（或调整）一个具名版本的规则内容，返回登记后的内容。 */
    public HealthRules register(String name, HealthRules rules) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("规则版本名不能为空");
        }
        if (rules == null) {
            throw new IllegalArgumentException("规则内容不能为空");
        }
        versions.put(name, rules);
        return rules;
    }

    /** 按名查找已登记版本。 */
    public Optional<HealthRules> find(String name) {
        return Optional.ofNullable(versions.get(name));
    }

    /** 当前全部已登记版本（只读视图）。 */
    public Map<String, HealthRules> all() {
        return Collections.unmodifiableMap(versions);
    }
}
