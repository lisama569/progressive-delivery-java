# 本地渐进式灰度发布编排

基于 Spring Boot + 本地内存状态的灰度发布编排能力：发布计划生命周期管理、流量权重约束、
健康指标自动判定（推进/回退）、版本兼容与冲突处理。所有状态保存在内存中，可本地完整复现验证。

## 状态机

```
DRAFT --start--> RUNNING --pause--> PAUSED --resume--> RUNNING
RUNNING --advance(末阶段)--> COMPLETED
RUNNING --autoJudge(健康)--> 下一阶段 / COMPLETED(末阶段)
RUNNING --autoJudge(越界)--> ROLLED_BACK
RUNNING/PAUSED --abort--> ABORTED
```

- `COMPLETED` / `ABORTED` / `ROLLED_BACK` 为终态，任何后续操作返回 `PLAN_ALREADY_FINISHED`。
- 不满足前置状态的跳转（如 DRAFT 上 advance、PAUSED 上 advance）返回 `ILLEGAL_TRANSITION`。
- 所有状态变更在计划对象监视器上同步，并发推进/中止下最终状态一致，失败原因可区分。

## 权重规则

- 阶段权重序列非空；每个权重 ∈ (0, 100]；严格递增；末阶段必须为 100。
- 违反任一条即拒绝创建，失败原因 `INVALID_WEIGHT`。
- 手动 `advance` 顺序跳转到下一阶段；末阶段再推进则进入 `COMPLETED`。

## 健康判定规则（HealthGate）

判定输入为当前阶段累积的指标样本（`stageSampleOffset` 之后的样本）：

| 条件 | 结论 | 效果 |
|---|---|---|
| 距计划创建 < `windowMillis` | `WAIT_WINDOW_OPEN` | 不推进，失败原因 `WINDOW_NOT_CLOSED` |
| 样本数 < `minSamples` | `WAIT_INSUFFICIENT_SAMPLES` | 不推进，失败原因 `INSUFFICIENT_SAMPLES` |
| 错误率 > `maxErrorRate` 或最大延迟 > `maxLatencyMillis` | `ROLLBACK` | 状态置 `ROLLED_BACK` |
| 否则 | `ADVANCE` | 推进到下一阶段（末阶段则 `COMPLETED`） |

- 同一阶段只判定一次（`judgedStages`）：重复判定不会重复推进、重复扣减或重复回退。
- 每次判定落盘为 `DecisionRecord`，含规则快照、样本数、错误率、最大延迟与判定时间。

## 兼容与冲突

- 计划携带 `specVersion`；V1 计划未显式给出健康规则时使用稳定缺省语义
  `HealthRules.DEFAULT = (minSamples=3, windowMillis=60s, maxErrorRate=0.05, maxLatencyMillis=500)`。
- 创建按 `planId` 幂等：同 id 同规格重复提交返回已有计划，不重复生效；
  同 id 不同规格返回 `CONFLICTING_SUBMISSION` 并将原计划 `conflictMarked=true`，不静默覆盖。
- 规则更新后历史判定可复现：`DecisionRecord.rulesSnapshot` 保存判定时的规则，
  用快照重放同样本必得相同结论（见 `HealthGateTest#v1Plan_usesStableDefaultRules_andHistoricalDecisionsAreReproducible`）。

## REST 入口

```
POST   /api/plans                 创建 {planId, specVersion?, stageWeights, healthRules?}
POST   /api/plans/{id}/start|advance|pause|resume|abort
POST   /api/plans/{id}/metrics    上报指标 {error, latencyMillis, timestampMillis}
POST   /api/plans/{id}/auto-judge?nowMillis=  自动判定
GET    /api/plans/{id}            查询
```

失败响应为 HTTP 422 + `{reason, message}`，`reason` 即 `FailureReason` 枚举，可机器区分。

## 验证方法

```bash
mvn test
```

- `PlanServiceConcurrencyTest`：16 线程混合 advance/abort，断言最终状态一致为 ABORTED、
  失败原因均为 `PLAN_ALREADY_FINISHED`；重复创建幂等、冲突创建被标记。
- `PlanServiceValidationTest`：非法权重、非法状态跳转、终态重复操作的可区分拒绝。
- `HealthGateTest`：窗口未结束/样本不足不推进、健康推进、越界回退、同阶段判定幂等、
  V1 缺省语义与历史判定重放复现。
- `DeliveryApplicationTest`：REST 入口冒烟，验证不同入口结论一致。

单测日志打印输入状态与判定依据（`[并发用例]`/`[判定用例]`/`[回退用例]`/`[兼容用例]` 等前缀）。
