# 本地渐进式灰度发布编排

基于 Spring Boot 3 + Java 21 的本地内存实现：发布计划编排、流量权重约束、
基于健康指标的自动推进/回退判定、版本兼容与冲突处理。所有状态保存在本地内存，
不依赖外部组件，可在本地完整复现验证。

## 构建与验证

```bash
mvn test          # 全部单测（含并发场景），日志打印输入状态与判定依据
mvn spring-boot:run   # 启动 REST 入口（默认 8080）
```

REST 入口（`src/main/java/.../api/PlanController.java`）与服务入口
（`PlanService` / `JudgmentService`）共享同一编排逻辑，结论一致：

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/plans` | 创建计划（body: planId/planVersion/idempotencyKey/stages） |
| POST | `/api/plans/{id}/advance` | 启动/推进到下一阶段（最后阶段推进即完成） |
| POST | `/api/plans/{id}/pause` `/resume` `/abort` | 暂停 / 恢复 / 中止 |
| POST | `/api/plans/{id}/weight?stageIndex=&weightPercent=` | 调整未进入阶段的权重 |
| POST | `/api/plans/{id}/samples` | 录入健康指标样本 |
| POST | `/api/plans/{id}/judge` | 执行一次自动判定 |
| GET  | `/api/plans/{id}` `/decisions` | 查询计划 / 判定历史 |

所有被拒绝的操作返回 4xx + `{"reason": "...", "message": "..."}`，
`reason` 为 `FailureReason` 枚举，机器可区分。

## 状态机

```
                 advance()                advance()
   DRAFT ──────────────────▶ RUNNING ──────────────────▶ COMPLETED
                               │  ▲                          ▲
                    pause()    │  │ resume()                 │ 最后阶段判定通过(COMPLETE)
                               ▼  │                          │
                             PAUSED ───────────┐             │
                               │               │             │
   任意非终态 ──── abort() ───▶ ABORTED ◀──────┘   ROLLBACK: 阶段 i → i-1（阶段 0 → 基线 -1）
```

规则：

- `COMPLETED` / `ABORTED` 为终态，终态上的任何操作一律拒绝，原因 `PLAN_ALREADY_FINISHED`；
- 状态机非法跳转（如 DRAFT 上暂停、PAUSED 上推进）拒绝，原因 `ILLEGAL_STATE_TRANSITION`；
- 手动推进只能前进到相邻下一阶段，阶段下标越界拒绝，原因 `ILLEGAL_STAGE_JUMP`；
- 回退只能由自动判定触发，逐级回退，阶段 0 越界回退到基线（-1，0% 流量）。

## 权重与阶段规则

- 每个阶段权重 ∈ [1,100]，且**严格单调递增**；
- 最后一个阶段必须为 **100%**（全量）；
- 权重修改仅允许作用于**尚未进入**的阶段（DRAFT 下任意阶段，RUNNING/PAUSED 下当前阶段之后），
  修改后整体仍须满足上述约束，违反即拒绝，原因 `INVALID_WEIGHTS` / `ILLEGAL_STATE_TRANSITION`。

## 自动判定规则（JudgmentRuleSet，当前内置 v1）

判定窗口 = 进入当前阶段之后录入的样本（推进/回退会重置窗口起点）。
判定优先级（安全优先）：

1. **越界回退**：窗口内任意样本 `errorRate > 0.01` 或 `latencyP99 > 500ms` → `ROLLBACK`
   （不受观测窗口与样本数限制）；
2. **窗口未结束**：已观测时长 < 阶段 `observationWindowMillis` → `HOLD_WINDOW_NOT_ELAPSED`，不得推进；
3. **样本不足**：窗口内样本数 < 阶段 `minSamples` → `HOLD_INSUFFICIENT_SAMPLES`，不得推进；
4. **通过**：最后阶段 → `COMPLETE`（计划完成），否则 → `ADVANCE`（进入下一阶段）。

幂等与并发保证：

- 同一计划的所有操作在 per-plan 锁内串行执行；并发推进/中止的终态唯一，
  终态后的失败统一为 `PLAN_ALREADY_FINISHED`；
- 重复提交：创建按 `idempotencyKey` 去重（同键重放、异键拒绝 `DUPLICATE_SUBMISSION`）；
  变更操作按 `操作名:idempotencyKey` 去重，重放返回首次结果不重复生效；
- 重复判定：无新样本且上次结论仍为 HOLD 时返回首次结论（不重复记录）；
  推进/回退后窗口重置为空，并发重复判定只会得到 HOLD，**不会重复扣减或重复回退**；
- 每次判定落一条不可变 `Decision`（含输入快照、判定依据、规则版本），结论可解释、可复现。

## 版本兼容与冲突处理

- **计划版本**（`PlanCompatibility`，兼容范围 v1..v2，语义冻结）：
  - v1：阶段仅指定权重，`minSamples=3`、`observationWindowMillis=1000` 为冻结缺省值
    （调用方传入也会被忽略，保证旧计划行为确定稳定）；
  - v2：阶段显式携带 `minSamples` 与 `observationWindowMillis`；
  - 范围外版本拒绝，原因 `UNSUPPORTED_PLAN_VERSION`，不做猜测式兼容。
- **规则版本**（`RuleRegistry`）：规则更新只能注册更高版本并切换当前版本；
  重复注册相同版本拒绝，原因 `VERSION_CONFLICT`（不静默覆盖）。
  历史 `Decision` 携带 `ruleVersion`，可用 `registry.byVersion(v).evaluate(...)`
  以相同输入复现历史结论（见 `CompatibilityTest`）。
- **乐观并发**：所有变更操作可携带 `expectedRevision`，与计划当前 `revision`
  不一致即拒绝，原因 `VERSION_CONFLICT`，冲突提交被明确标记而非静默覆盖。

## 失败原因一览（FailureReason）

`PLAN_NOT_FOUND` / `DUPLICATE_SUBMISSION` / `INVALID_WEIGHTS` /
`ILLEGAL_STATE_TRANSITION` / `ILLEGAL_STAGE_JUMP` / `PLAN_ALREADY_FINISHED` /
`INSUFFICIENT_SAMPLES` / `OBSERVATION_WINDOW_NOT_ELAPSED` / `METRICS_OUT_OF_BOUNDS` /
`VERSION_CONFLICT` / `UNSUPPORTED_PLAN_VERSION`

## 测试与验证方法

| 测试类 | 覆盖 |
|---|---|
| `WeightValidationTest` | 非法权重（越界/非递增/末段非 100/空阶段）拒绝，原因可区分 |
| `LifecycleTest` | 状态机流转、非法跳转、终态拒绝、幂等重放、乐观版本冲突、权重修改约束 |
| `JudgmentTest` | 窗口未结束/样本不足不推进、越界回退、重复判定去重、最后阶段完成 |
| `ConcurrencyTest` | 20 线程并发推进（恰好 6 次成功）、推进 vs 中止竞争（终态唯一）、16 线程并发判定（仅 1 次 ADVANCE 生效） |
| `CompatibilityTest` | v1 缺省语义稳定、未知版本拒绝、规则版本冲突、历史判定可复现 |
| `ApiConsistencyTest` | REST 入口全链路、错误体 reason 可区分、读写入口结论一致 |

并发测试以 30 轮压力循环验证计数不变量（如 5 阶段计划恰好 6 次推进成功、
其余全部 `PLAN_ALREADY_FINISHED`），单测日志打印输入状态与判定依据，
可用 `mvn test` 在本地完整复现。
