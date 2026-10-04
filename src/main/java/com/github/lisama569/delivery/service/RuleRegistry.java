package com.github.lisama569.delivery.service;

import com.github.lisama569.delivery.domain.HealthRules;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 健康判定规则的具名版本登记处。同一套规则可登记为具名版本，后续可继续登记新版本；
 * 已登记版本不可变，计划创建时按名引用并锁定当时内容。
 */
@Component
public class RuleRegistry {

    private final ConcurrentHashMap<String, HealthRules> versions = new ConcurrentHashMap<>();

    /** 登记一个具名规则版本。同名重复登记拒绝（返回 false），保证版本内容不可变。 */
    public boolean register(String name, HealthRules rules) {
        if (name == null || name.isBlank() || rules == null) {
            return false;
        }
        return versions.putIfAbsent(name, rules) == null;
    }

    /** 按名查询已登记版本。 */
    public Optional<HealthRules> find(String name) {
        return Optional.ofNullable(versions.get(name));
    }

    /** 全部已登记版本（只读视图，用于查询入口）。 */
    public Map<String, HealthRules> all() {
        return Map.copyOf(versions);
    }
}
