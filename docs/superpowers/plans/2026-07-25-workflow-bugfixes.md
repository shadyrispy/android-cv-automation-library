# Workflow Module Bugfix Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 workflow 模块全面检查发现的 10 个问题(2 Critical + 3 High + 3 Medium + 测试补充),保证生产可用性

**Architecture:** TDD 方式修复,每个修复都先写失败测试再改实现。分两批次:A(严重 bug+ktlint)、B(可观察性+健壮性)

**Tech Stack:** Kotlin, JUnit 4, Robolectric, kotlinx.serialization

---

## File Structure

修改文件清单:
- `workflow/src/main/java/.../runtime/ProcessingState.kt` — 修复线程安全(Critical 2)
- `workflow/src/main/java/.../runtime/ConditionsVerifier.kt` — 修复 timer key 共享 bug(Critical 1)+ 取消检查(High 3)+ region 校验(Medium 6)+ StepLogger 集成(High 4)
- `workflow/src/main/java/.../runtime/ActionExecutor.kt` — 修复返回值忽略(High 5)+ StepLogger 集成(High 4)
- `workflow/src/main/java/.../runtime/ScenarioExecutor.kt` — 修复 sleep 硬编码(Medium 7)+ Result.finished 语义(Medium 8)+ StepLogger 集成(High 4)+ 取消检查(High 3)
- 测试文件:新增/补充覆盖盲区(Low 10)

---

### Task A1: 修复 ConditionsVerifier TimerReached 共享计时器 bug(Critical)

**Files:**
- Modify: `workflow/src/main/java/.../runtime/ConditionsVerifier.kt`
- Modify: `workflow/src/main/java/.../runtime/ProcessingState.kt`
- Test: `workflow/src/test/java/.../runtime/ConditionsVerifierTest.kt`

**根因**: `condition.hashCode()` 对相同字段值的 data class 返回相同 hashCode,导致两个相同 TimerReached 条件共享计时器

**修复**: 改用调用方传入的唯一 key(基于 Event.id + condition 在列表中的 index)

- [ ] **Step 1: 写失败测试**

```kotlin
@Test
fun `timer_reached conditions in different events do not share timer`() {
    val state = ProcessingState(nowMsProvider = { 0L })
    val verifier = ConditionsVerifier(state)
    val backend = object : AutomationBackend {
        override val logger = object : StepLogger {}
        override fun findImage(templateName: String, confidence: Double, region: IntArray) = null
        override fun findText(text: String, region: IntArray, similarity: Double) = null
        override fun tap(x: Double, y: Double, imageName: String?) = true
        override fun longPress(x: Double, y: Double, imageName: String?, durationMs: Long) = true
        override fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long) = true
        override fun scroll(scrollDown: Boolean, durationMs: Long) = true
        override fun wait(seconds: Double) {}
    }

    // 两个相同 TimerReached(5000) 在不同 event 中
    val cond1 = Condition.TimerReached(5000L, restartWhenReached = false)
    val cond2 = Condition.TimerReached(5000L, restartWhenReached = false)

    // event1 在 0ms 时检查:未到
    assertFalse(verifier.verify(listOf(cond1), ConditionOperator.AND, backend, contextKey = "event1"))
    // event2 在 0ms 时检查:也未到(独立计时器)
    assertFalse(verifier.verify(listOf(cond2), ConditionOperator.AND, backend, contextKey = "event2"))

    // 推进时间到 5000ms
    // event1 到达
    assertTrue(verifier.verify(listOf(cond1), ConditionOperator.AND, backend, contextKey = "event1"))
}
```

注意:需要 fake clock 推进时间。测试需要修改 verify 签名增加 contextKey 参数。

- [ ] **Step 2: 运行测试验证失败**

Run: `./gradlew :workflow:testDebugUnitTest --tests "...ConditionsVerifierTest"`
Expected: FAIL — `verify` 缺少 contextKey 参数

- [ ] **Step 3: 修改 verify 签名与实现**

```kotlin
// ConditionsVerifier.kt
fun verify(
    conditions: List<Condition>,
    operator: ConditionOperator,
    backend: AutomationBackend,
    contextKey: String = "default",  // 新增:调用方传入唯一 key
): Boolean {
    // ...
    is Condition.TimerReached -> {
        val timerName = "${contextKey}_timer_${index}"  // 用 contextKey + index 替代 hashCode
        // ...
    }
}
```

- [ ] **Step 4: 运行测试验证通过**
- [ ] **Step 5: Commit**

---

### Task A2: 修复 ProcessingState 线程安全(Critical)

**Files:**
- Modify: `workflow/src/main/java/.../runtime/ProcessingState.kt`

**根因**: `isTimerReached` 中 `timer.startMs = nowMs` 是非原子写,与文档承诺的"UI 线程可并发读"冲突

**修复**: 用 `synchronized(timer)` 保护读写

- [ ] **Step 1: 修改 TimerState 为可同步对象**

```kotlin
private class TimerState(
    @Volatile var startMs: Long,
    val durationMs: Long,
    val restart: Boolean,
) {
    fun reachedAt(nowMs: Long): Boolean = synchronized(this) {
        val elapsed = nowMs - startMs
        val reached = elapsed >= durationMs
        if (reached && restart) {
            startMs = nowMs
        }
        reached
    }
}
```

- [ ] **Step 2: 修改 isTimerReached 委托给 TimerState**

```kotlin
fun isTimerReached(name: String, nowMs: Long = nowMsProvider()): Boolean {
    val timer = timers[name] ?: return false
    return timer.reachedAt(nowMs)
}
```

- [ ] **Step 3: 运行所有测试验证不回归**
- [ ] **Step 4: Commit**

---

### Task A3: 修复 ktlint 违规(Low 9)

**Files:** 所有 workflow/src 下的 .kt 文件

- [ ] **Step 1: 运行 ktlintFormat**

```bash
./gradlew :workflow:ktlintFormat
```

- [ ] **Step 2: 运行 ktlintCheck 验证通过**
- [ ] **Step 3: 运行全量测试验证不回归**
- [ ] **Step 4: Commit**

---

### Task B1: 集成 StepLogger 到执行引擎(High 4)

**Files:**
- Modify: `ScenarioExecutor.kt`, `ConditionsVerifier.kt`, `ActionExecutor.kt`

- [ ] **Step 1: ScenarioExecutor 在关键节点调用 logger**

```kotlin
// ScenarioExecutor.run() 中
backend.logger.info("Scenario '${scenario.name}' started with ${scenario.events.size} events")
// 每个 event 评估后
backend.logger.onEventEvaluated(event.name, satisfied)
// 每个 action 执行后
backend.logger.onActionExecuted(action.toString(), success)
// 结束时
backend.logger.info("Scenario finished: $result")
```

- [ ] **Step 2: ActionExecutor 传入 logger 并记录 action 执行结果**
- [ ] **Step 3: 写测试验证 logger 被调用**
- [ ] **Step 4: 运行测试验证通过**
- [ ] **Step 5: Commit**

---

### Task B2: 修复 ActionExecutor 忽略 backend 返回值(High 5)

**Files:**
- Modify: `workflow/src/main/java/.../runtime/ActionExecutor.kt`
- Test: `workflow/src/test/java/.../runtime/ActionExecutorTest.kt`

- [ ] **Step 1: 写失败测试 — backend 返回 false 时 action 应返回 false**

```kotlin
@Test
fun `tap failure returns false to signal stop`() {
    val backend = FakeBackend().apply { tapResult = false }
    val executor = ActionExecutor()
    val shouldContinue = executor.execute(Action.Tap(0.0, 0.0), backend, ProcessingState())
    assertFalse(shouldContinue)
}
```

- [ ] **Step 2: 修改 ActionExecutor 返回 backend 结果**

```kotlin
is Action.Tap -> backend.tap(action.x, action.y, action.imageName)
```

- [ ] **Step 3: 运行测试验证通过**
- [ ] **Step 4: Commit**

---

### Task B3: 修复 region 校验 + sleep 可配置 + Result 语义(Medium 6, 7, 8)

**Files:**
- Modify: `ConditionsVerifier.kt`(region require)
- Modify: `ScenarioExecutor.kt`(sleep 构造参数 + Result.finished 修复)
- Test: 补充测试

- [ ] **Step 1: ConditionsVerifier.toIntArray 改为 require**

```kotlin
private fun List<Int>.toIntArray(): IntArray {
    require(size == 4) { "region must have 4 elements [x, y, w, h], got $size" }
    return IntArray(4) { this[it] }
}
```

- [ ] **Step 2: ScenarioExecutor 增加 pollIntervalMs 构造参数**

```kotlin
class ScenarioExecutor(
    private val backend: AutomationBackend,
    private val initialState: ProcessingState = ProcessingState(),
    private val pollIntervalMs: Long = 10L,  // 新增
)
// ...
Thread.sleep(pollIntervalMs)
```

- [ ] **Step 3: Result.finished 包含异常退出**

```kotlin
val finished: Boolean get() = completedNormally || cancelled || timedOut || errorMessage != null
```

- [ ] **Step 4: 补充测试覆盖**
- [ ] **Step 5: Commit**

---

### Task B4: 补充测试盲区(Low 10)

**Files:**
- Test: 补充 TimerReached/LongPress/Swipe/ChangeCounter/超时/keepEvaluating 测试

- [ ] **Step 1: 补充 ConditionsVerifier 的 TimerReached 测试**
- [ ] **Step 2: 补充 ActionExecutor 的 LongPress/Swipe/ChangeCounter 测试**
- [ ] **Step 3: 补充 ScenarioExecutor 的超时和 keepEvaluating 测试**
- [ ] **Step 4: 运行全量测试**
- [ ] **Step 5: Commit**

---

## Self-Review

**1. Spec coverage:**
- Critical 1 (timer 共享): Task A1 ✓
- Critical 2 (线程安全): Task A2 ✓
- High 3 (取消检查): Task B1 中集成(logger 记录取消) — 但实际上需要在 evaluateOne 前检查,需要补充到 Task B1
- High 4 (StepLogger): Task B1 ✓
- High 5 (返回值): Task B2 ✓
- Medium 6 (region): Task B3 ✓
- Medium 7 (sleep): Task B3 ✓
- Medium 8 (Result): Task B3 ✓
- Low 9 (ktlint): Task A3 ✓
- Low 10 (测试): Task B4 ✓

**2. Type consistency:** verify 签名在 A1 增加 contextKey,B1 不再改动签名,一致。

**3. 注意 High 3(取消检查)需要补充到 Task B1**:在 ConditionsVerifier.evaluateOne 开头检查 backend.isCancelled()

---

## Execution Handoff

计划已保存。采用 Inline Execution 方式直接执行(无需再派 subagent,主线程直接改更快)。
