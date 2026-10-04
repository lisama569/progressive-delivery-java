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

- 计划携带 `specVersion`；受支持范围明确为 `[1, 2]`
  （`ReleasePlan.MIN_SUPPORTED_SPEC_VERSION` ~ `MAX_SUPPORTED_SPEC_VERSION`）。
  超出范围的创建请求一律拒绝，失败原因 `UNSUPPORTED_SPEC_VERSION`，不默默套用缺省值。
- 范围内旧版本缺省行为固定：V1 计划未显式给出健康规则时使用稳定缺省语义
  `HealthRules.DEFAULT = (minSamples=3, windowMillis=60s, maxErrorRate=0.05, maxLatencyMillis=500)`，
  不同入口、多次运行结论一致。
- 创建按 `planId` 幂等：以**整份规格**（`PlanSpec` = specVersion + 阶段权重 + 绑定规则版本名 +
  生效规则内容）完全一致为准。全一致 -> 幂等返回已有计划，不重复生效、不留冲突痕；
  任意一处不同 -> 失败原因 `CONFLICTING_SUBMISSION`，原计划保留并 `conflictMarked=true`，
  同时追加一条 `ConflictRecord`（含既有规格、尝试规格、检测时间），
  可通过 `GET /api/plans/{id}/conflicts` 查询。“重复提交”与“冲突”是两种可区分结论。
- 并发下同 id 不同规格的提交：`putIfAbsent` 保证只有首个规格生效，其余全部作为冲突留痕，
  不丢记录、不覆盖状态。

## 规则版本化（RuleRegistry）

- 健康判定规则可登记为具名版本：`POST /api/rules {name, rules}`。同名重复登记被拒绝
  （`RULE_VERSION_CONFLICT`），已登记版本内容不可变；规则迭代通过登记新版本名进行。
- 创建计划时通过 `ruleVersion` 显式绑定已登记版本；计划创建即**锁定**该版本当时的规则内容
  （快照存入计划），之后无论登记处如何新增版本，该计划的自动判定与回退都按锁定时规则走。
- 绑定未登记的版本 -> 拒绝，失败原因 `RULE_VERSION_NOT_FOUND`，与权重、版本范围等失败可区分。
- 每次判定落盘 `DecisionRecord`，含规则快照与绑定的版本名（`ruleVersion`），
  历史判定可用快照重放同样本复现相同结论。

## REST 入口

```
POST   /api/rules                 登记规则版本 {name, rules}
GET    /api/rules                 查询全部已登记规则版本
POST   /api/plans                 创建 {planId, specVersion?, stageWeights, healthRules?, ruleVersion?}
POST   /api/plans/{id}/start|advance|pause|resume|abort
POST   /api/plans/{id}/metrics    上报指标 {error, latencyMillis, timestampMillis}
POST   /api/plans/{id}/auto-judge?nowMillis=  自动判定
GET    /api/plans/{id}            查询（含 ruleVersion、conflictMarked）
GET    /api/plans/{id}/conflicts  查询冲突留痕
```

失败响应为 HTTP 422 + `{reason, message}`，`reason` 即 `FailureReason` 枚举，可机器区分：
`PLAN_NOT_FOUND` / `CONFLICTING_SUBMISSION` / `INVALID_WEIGHT` / `ILLEGAL_TRANSITION` /
`PLAN_ALREADY_FINISHED` / `INSUFFICIENT_SAMPLES` / `WINDOW_NOT_CLOSED` /
`RULE_VERSION_NOT_FOUND` / `UNSUPPORTED_SPEC_VERSION`。

## 验证方法

```bash
mvn test
```

- `PlanServiceConcurrencyTest`：16 线程混合 advance/abort，断言最终状态一致为 ABORTED、
  失败原因均为 `PLAN_ALREADY_FINISHED`；重复创建幂等、冲突创建被标记。
- `PlanServiceValidationTest`：非法权重、非法状态跳转、终态重复操作的可区分拒绝。
- `HealthGateTest`：窗口未结束/样本不足不推进、健康推进、越界回退、同阶段判定幂等、
  V1 缺省语义与历史判定重放复现。
- `RuleVersionTest`：规则版本绑定后不受后续规则更新影响、历史判定按绑定版本快照复现、
  全规格一致幂等 / 任意差异冲突且留痕可查询、未登记规则版本与超范围计划版本的可区分拒绝、
  16 线程并发冲突提交仅一个规格生效且其余全部留痕。
- `DeliveryApplicationTest`：REST 入口冒烟（含规则登记、绑定创建、未登记/超范围拒绝），
  验证不同入口结论一致。

单测日志打印输入规格、绑定的规则版本与判定依据
（`[并发用例]`/`[判定用例]`/`[回退用例]`/`[兼容用例]`/`[版本用例]`/`[复现用例]`/`[范围用例]`/`[冲突用例]` 等前缀）。
