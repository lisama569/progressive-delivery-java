# 本地渐进式灰度发布编排

基于 Spring Boot + 本地内存状态的灰度发布编排能力：发布计划生命周期管理、流量权重约束、
健康指标自动判定（推进/回退）、判定规则版本化与提交留痕、版本兼容与冲突处理。
所有状态保存在内存中，不依赖外部服务或真实凭据，可本地完整复现验证。

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
- 每次判定落盘为 `DecisionRecord`，含规则快照、绑定的规则版本名、样本数、错误率、
  最大延迟与判定时间。

## 规则版本化与计划绑定

- `RuleVersionRegistry` 管理具名规则版本：同一套健康判定规则可登记为一个具名版本，
  后续可登记新版本名，也可调整已有版本的内容。
- 创建计划时通过 `ruleVersion` 显式选用一个已登记版本；计划创建即**锁定**该版本当时的
  规则内容（快照保存在计划上）。之后无论注册表如何调整或新增版本，该计划的自动判定与
  回退都继续按锁定时的规则走，结论不会被后来的改动带偏。
- 每次判定的 `DecisionRecord` 同时记录 `ruleVersion`（版本名）与 `rulesSnapshot`
  （当时内容），历史判定结论可按当时绑定的版本复现（重放同样本必得相同结论）。
- 绑定不存在/未登记的版本时创建被拒绝，失败原因 `RULE_VERSION_NOT_FOUND`，
  与权重非法、版本超范围等其它失败可区分。

## 幂等与冲突的判定边界

- 判定单位是**整份提交规格** `PlanSpec = (specVersion, stageWeights, healthRules, ruleVersion)`。
- 同 `planId` 重复提交：整份规格**完全一致**才算重复提交，幂等返回已有计划，不产生留痕；
  任意一处不同（权重、规则版本、specVersion、显式规则）即判为冲突：
  - 返回 `CONFLICTING_SUBMISSION`（与幂等成功是两种可区分结论）；
  - 原计划保留不动，仅置 `conflictMarked=true`；
  - 每次冲突提交落一条 `ConflictRecord`（既有规格、提交规格、时间），
    可经 `GET /api/plans/{id}/conflicts` 查询，留痕不丢。
- 并发同 `planId` 提交不同规格：只有先到的规格生效（`putIfAbsent`），
  其余全部判为冲突并逐条留痕；同规格的并发重复提交仍幂等。最终生效规格唯一，
  所有留痕的 `existingSpec` 都与生效计划一致，状态不被搞乱。

## 受支持的规格版本范围

- 受支持范围：**[1, 3]**（`PlanService.MIN_SUPPORTED_SPEC_VERSION` ~
  `MAX_SUPPORTED_SPEC_VERSION`，当前版本 `ReleasePlan.CURRENT_SPEC_VERSION = 3`）。
- 范围内旧版本的缺省行为（固定、可预期，不同入口与多次运行下一致）：
  - 未显式给出 `healthRules` 且未绑定 `ruleVersion` 时，一律使用
    `HealthRules.DEFAULT = (minSamples=3, windowMillis=60s, maxErrorRate=0.05, maxLatencyMillis=500)`；
  - 显式给出 `healthRules` 时优先使用显式值；绑定 `ruleVersion` 时以注册表中该版本内容为准。
- 超出范围的版本（< 1 或 > 3）创建请求一律拒绝，失败原因 `UNSUPPORTED_SPEC_VERSION`，
  不会默默套缺省值当成创建成功，也不会留下计划。

## REST 入口

```
POST   /api/rules                 登记/调整规则版本 {name, rules}
GET    /api/rules                 查询全部已登记版本
GET    /api/rules/{name}          查询单个版本（不存在 -> 404 RULE_VERSION_NOT_FOUND）
POST   /api/plans                 创建 {planId, specVersion?, stageWeights, healthRules?, ruleVersion?}
POST   /api/plans/{id}/start|advance|pause|resume|abort
POST   /api/plans/{id}/metrics    上报指标 {error, latencyMillis, timestampMillis}
POST   /api/plans/{id}/auto-judge?nowMillis=  自动判定
GET    /api/plans/{id}            查询计划（含 ruleVersion、conflictMarked）
GET    /api/plans/{id}/conflicts  查询该计划的全部冲突留痕
```

失败响应为 HTTP 422 + `{reason, message}`，`reason` 即 `FailureReason` 枚举，可机器区分：
`PLAN_NOT_FOUND` / `DUPLICATE_PLAN` / `CONFLICTING_SUBMISSION` / `INVALID_WEIGHT` /
`ILLEGAL_TRANSITION` / `PLAN_ALREADY_FINISHED` / `INSUFFICIENT_SAMPLES` / `WINDOW_NOT_CLOSED` /
`RULE_VERSION_NOT_FOUND` / `UNSUPPORTED_SPEC_VERSION`。

## 验证方法

```bash
mvn test
```

- `RuleVersionBindingTest`：规则版本绑定后不受注册表后续调整影响、历史判定按当时绑定
  版本复现、同标识同规格幂等/不同规格冲突可分辨且留痕可查、并发不同规格仅一个生效且
  留痕不丢、不存在版本与超范围版本的可区分拒绝、范围内旧版本缺省行为稳定。
- `PlanServiceConcurrencyTest`：16 线程混合 advance/abort，断言最终状态一致为 ABORTED、
  失败原因均为 `PLAN_ALREADY_FINISHED`；重复创建幂等、冲突创建被标记。
- `PlanServiceValidationTest`：非法权重、非法状态跳转、终态重复操作的可区分拒绝。
- `HealthGateTest`：窗口未结束/样本不足不推进、健康推进、越界回退、同阶段判定幂等、
  V1 缺省语义与历史判定重放复现。
- `DeliveryApplicationTest`：REST 入口冒烟（创建/推进/查询一致性），规则版本登记、
  绑定创建、不存在版本拒绝、冲突留痕 REST 查询。

单测日志打印输入规格、绑定的规则版本与判定依据（`[版本锁定用例]`/`[复现用例]`/
`[幂等用例]`/`[冲突用例]`/`[并发冲突用例]`/`[兼容边界用例]` 等前缀）。
