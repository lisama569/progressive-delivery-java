package com.github.lisama569.progressivedeliveryjava.store;

import com.github.lisama569.progressivedeliveryjava.domain.ReleasePlan;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 本地内存计划仓库。
 *
 * 并发模型：每个 planId 对应一把锁（{@code computeIfAbsent} 保证唯一），
 * 同一计划的生命周期变更、权重修改、判定全部串行化；不同计划互不阻塞。
 */
@Repository
public class PlanRepository {
    private final ConcurrentHashMap<String, ReleasePlan> plans = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public Optional<ReleasePlan> find(String planId) {
        return Optional.ofNullable(plans.get(planId));
    }

    /**
     * 在 planId 对应的锁内执行操作。锁对象永不移除（本地模拟场景下键数量等于计划数量）。
     */
    public <T> T withLock(String planId, Supplier<T> action) {
        Object lock = locks.computeIfAbsent(planId, k -> new Object());
        synchronized (lock) {
            return action.get();
        }
    }

    public void save(ReleasePlan plan) {
        plans.put(plan.planId(), plan);
    }
}
