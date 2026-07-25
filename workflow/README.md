# Automation Library - Workflow Module

ECA(事件-条件-动作)编排引擎,为 [android-cv-automation-library](../app) 提供流程编排能力。

> **独立性**: 本模块是可选依赖,不打包进主 AAR。需要流程编排的宿主项目按需引入。

## 核心特性

- **ECA 模型**: Scenario → Event → Condition + Action(借鉴 [Smart-AutoClicker](https://github.com/Nain57/Smart-AutoClicker) 简化版)
- **类型安全 DSL**: Kotlin `@DslMarker` 构建 Scenario,IDE 全自动补全
- **JSON 持久化**: kotlinx.serialization,Scenario 可序列化为文件支持热更新
- **短路求值**: AND/OR 条件按顺序短路,廉价条件(Counter/Timer)建议排在前
- **两阶段评估**: Trigger Event(廉价,无图像)先于 Image Event(昂贵,需 OCR/模板匹配)
- **Strategy 模式**: Action/Condition 多态分发,新增类型不影响现有代码
- **可观察**: StepLogger 接口暴露执行全过程,便于调试
- **取消响应**: 每个条件评估前检查 `isCancelled()`,避免昂贵的 OCR 在用户停止后继续执行
- **线程安全**: ProcessingState 计时器/计数器/事件状态全部 `synchronized` + `@Volatile`

## 架构

```
┌─────────────────────────────────────────────────────┐
│  宿主 App(如 genshin-inventory-scanner-v2)         │
│  ┌───────────────────┐  ┌────────────────────┐    │
│  │  ScanScenario     │  │  GameUiBackend      │    │
│  │  (ECA 定义)       │  │  (实现 Backend)     │    │
│  └─────────┬─────────┘  └─────────┬──────────┘    │
└────────────┼──────────────────────┼───────────────┘
             │                      │
             │   :workflow AAR       │  :app AAR
             ▼                      ▼
┌─────────────────────────────────────────────────────┐
│  workflow module                                     │
│  ┌──────────────────────────────────────────────┐   │
│  │  ScenarioExecutor(主循环)                     │   │
│  │   ├─ ConditionsVerifier(AND/OR 短路)        │   │
│  │   ├─ ActionExecutor(Strategy 多态)          │   │
│  │   └─ ProcessingState(计数器/计时器/事件)     │   │
│  └──────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────┐   │
│  │  AutomationBackend 接口(桥接原子动作)       │   │
│  └──────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────┐   │
│  │  model/  (Scenario/Event/Condition/Action)   │   │
│  │  dsl/    (WorkflowDsl 构建器)                │   │
│  │  logging/(StepLogger)                        │   │
│  └──────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────┘
             │
             ▼
┌─────────────────────────────────────────────────────┐
│  app module(原子动作库)                              │
│  ImageUtils.findImage / findText / waitVanish       │
│  MyAccessibilityService.tap / scroll / swipe        │
│  OnnxPpocrEngine(PP-OCRv6)                          │
└─────────────────────────────────────────────────────┘
```

## 模块结构

```
workflow/
├── build.gradle.kts
├── consumer-rules.pro                    # ProGuard 规则
└── src/main/java/com/steve1316/automation_library/workflow/
    ├── AutomationBackend.kt              # 桥接接口(宿主实现)
    ├── model/                            # ECA 数据模型(@Serializable)
    │   ├── Scenario.kt                   # 顶级容器
    │   ├── Event.kt                      # sealed: Image / Trigger
    │   ├── Condition.kt                  # sealed: ImageAppears/TextMatches/CounterReached/TimerReached/Custom
    │   ├── Action.kt                     # sealed: Tap/LongPress/Swipe/Scroll/Wait/ChangeCounter/ToggleEvent/Complete/Custom
    │   ├── ConditionOperator.kt         # enum: AND / OR
    │   └── JsonSerializers.kt           # Scenario.toJson() / scenarioFromJson()
    ├── runtime/                          # 执行引擎
    │   ├── ScenarioExecutor.kt          # 主循环 + 超时 + 取消
    │   ├── ConditionsVerifier.kt        # 短路求值
    │   ├── ActionExecutor.kt            # Strategy 多态分发
    │   └── ProcessingState.kt           # 计数器/计时器/事件状态
    ├── dsl/
    │   └── WorkflowDsl.kt               # scenario { } DSL 构建器
    └── logging/
        └── StepLogger.kt                # 可观察日志接口
```

## 安装

### Gradle (JitPack)

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://jitpack.io") }
    }
}

// app/build.gradle.kts
dependencies {
    // 原子动作库(必需)
    implementation("com.github.shadyrispy.android-cv-automation-library:app:<version>")
    // 编排引擎(可选)
    implementation("com.github.shadyrispy.android-cv-automation-library:workflow:<version>")
}
```

### 模块依赖

```kotlin
// 本地模块依赖
dependencies {
    api(project(":app"))                              // 原子动作层
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
}
```

**AAR 体积**: ~155KB(不含主 AAR 的 ~6.5MB)

## 快速开始

### 1. 实现 AutomationBackend

宿主 App 实现此接口,将 ECA 的 Condition/Action 翻译为对 `ImageUtils` / `MyAccessibilityService` 的调用:

```kotlin
class GameUiBackend(
    private val imageUtils: ImageUtils,
    private val service: MyAccessibilityService,
    override val logger: StepLogger = MessageLogStepLogger(),
) : AutomationBackend {

    override fun findImage(
        templateName: String, confidence: Double, region: IntArray,
    ): PointF? {
        return imageUtils.findImage(templateName, confidence)
            ?.let { PointF(it.x.toFloat(), it.y.toFloat()) }
    }

    override fun findText(text: String, region: IntArray, similarity: Double): String? {
        val results = imageUtils.findText(text)
        return results.firstOrNull { it.text.contains(text, ignoreCase = true) }?.text
    }

    override fun tap(x: Double, y: Double, imageName: String?): Boolean {
        return try {
            service.tap(x.toInt(), y.toInt(), imageName)
            true
        } catch (e: Exception) {
            logger.error("tap failed", e)
            false
        }
    }

    override fun wait(seconds: Double) { Thread.sleep((seconds * 1000).toLong()) }

    override fun isCancelled(): Boolean = !BotService.isRunning
    // ... 其他方法委托给 service
}
```

### 2. 用 DSL 定义 Scenario

```kotlin
val scanArtifacts = scenario(id = "scan_artifacts", name = "扫圣遗物") {
    maxDuration(minutes = 30)

    // 导航到圣遗物 tab
    imageEvent(id = "nav", name = "navigate") {
        condition { imageAppears("tab_artifacts_unselected", confidence = 0.9) }
        action { tap(500.0, 200.0) }
        action { wait(0.5) }
    }

    // 滚动到底部时完成
    imageEvent(id = "scroll_end", name = "检查底部") {
        condition { imageAppears("scroll_end_indicator", shouldBeDetected = true) }
        action { complete() }
    }

    // 扫描当前页并翻页
    imageEvent(id = "scan_page", name = "扫描页面", keepEvaluating = false) {
        conditionOperator = ConditionOperator.AND
        condition { imageAppears("artifact_level_5") }
        condition { counterReached("scanned", 100, Comparison.LESS_OR_EQUAL) }
        action { custom("extract_details") }
        action { changeCounter("scanned", 1) }
        action { scroll(scrollDown = true) }
        action { wait(0.3) }
    }
}
```

### 3. 执行 Scenario

```kotlin
class ScanBotService : BotService() {

    @Subscribe(sticky = true)
    fun onStart(event: StartEvent) {
        val backend = GameUiBackend(imageUtils, myAccessibilityService)
        val executor = ScenarioExecutor(backend, pollIntervalMs = 50L)

        val result = executor.run(scanArtifacts)
        when {
            result.completedNormally -> MessageLog.logInfo("扫描完成")
            result.cancelled -> MessageLog.logInfo("用户取消")
            result.timedOut -> MessageLog.logWarn("扫描超时")
            result.errorMessage != null -> MessageLog.logErr("扫描异常: ${result.errorMessage}")
        }
    }
}
```

## JSON 持久化(可选)

Scenario 可序列化为 JSON 文件,支持热更新流程而无需重编译:

```kotlin
// 保存
val json = scanArtifacts.toJson()
File(context.filesDir, "scenarios/scan_artifacts.json").writeText(json)

// 加载
val json = File(context.filesDir, "scenarios/scan_artifacts.json").readText()
val scenario = scenarioFromJson(json)
val result = ScenarioExecutor(backend).run(scenario)
```

JSON 格式示例:

```json
{
    "id": "scan_artifacts",
    "name": "扫圣遗物",
    "events": [
        {
            "type": "image_event",
            "id": "nav",
            "name": "navigate",
            "conditionOperator": "AND",
            "conditions": [
                { "type": "image_appears", "templateName": "tab_artifacts_unselected", "confidence": 0.9 }
            ],
            "actions": [
                { "type": "tap", "x": 500.0, "y": 200.0 },
                { "type": "wait", "seconds": 0.5 }
            ]
        }
    ],
    "maxDurationMinutes": 30
}
```

## API 参考

### Scenario

| 属性 | 类型 | 说明 |
|---|---|---|
| `id` | String | 唯一标识 |
| `name` | String | 人类可读名称 |
| `events` | List\<Event\> | 事件列表(可混合 Image/Trigger) |
| `maxDurationMinutes` | Int | 最长执行时长,0=不限 |

### Event(Sealed)

| 子类型 | 属性 | 说明 |
|---|---|---|
| `Image` | id, name, conditions, actions, conditionOperator, priority, keepEvaluating, enabledOnStart | 需图像分析 |
| `Trigger` | id, name, conditions, actions, conditionOperator, enabledOnStart | 不依赖图像 |

### Condition(Sealed)

| 子类型 | 关键属性 | 求值代价 |
|---|---|---|
| `ImageAppears` | templateName, confidence, region, shouldBeDetected | 高(模板匹配) |
| `TextMatches` | text, region, similarity | 高(OCR 推理) |
| `CounterReached` | counterName, targetValue, comparison | 低 |
| `TimerReached` | durationMs, restartWhenReached | 低 |
| `Custom` | id | 由宿主定义 |

**建议**: 把廉价条件(Counter/Timer)放在 conditions 列表前面,以便短路跳过昂贵的 Image/Text。

### Action(Sealed)

| 子类型 | 返回值 | 说明 |
|---|---|---|
| `Tap` / `LongPress` / `Swipe` / `Scroll` | backend 返回值 | 动作失败会停止 Scenario |
| `Wait` | true | 固定等待 |
| `ChangeCounter` | true | 修改运行时计数器 |
| `ToggleEvent` | true | 启用/禁用其它 Event |
| `Complete` | false | 结束整个 Scenario |
| `Custom` | backend 返回值 | 宿主自定义动作 |

### ScenarioExecutor

```kotlin
class ScenarioExecutor(
    backend: AutomationBackend,
    initialState: ProcessingState = ProcessingState(),
    pollIntervalMs: Long = 10L,
)
```

**执行顺序**(每轮循环):
1. 检查 `backend.isCancelled()` → 退出
2. 检查超时 → 退出
3. 检查所有事件是否被禁用 → 退出
4. 评估所有 Trigger Event(廉价,按列表顺序)
5. 如果本轮无 Trigger 命中,评估所有 Image Event(按 priority 升序)
6. 首个满足条件的 Event 执行其 actions(`keepEvaluating=true` 时继续评估后续)
7. 任一 action 返回 false → 退出
8. `Thread.sleep(pollIntervalMs)` 让出 CPU

### ScenarioExecutor.Result

| 属性 | 说明 |
|---|---|
| `completedNormally` | Action.Complete 或所有事件被禁用 |
| `cancelled` | backend 报告取消或 InterruptedException |
| `timedOut` | 超过 maxDurationMinutes |
| `eventsProcessed` | 触发的 Event 总次数 |
| `errorMessage` | 异常消息(非 null 表示异常退出) |
| `finished` | 上述任一为 true 时返回 true |

## 设计决策

### 为什么用 ECA 而不是状态机?

- **行业事实标准**: Tasker / IFTTT / iOS 快捷指令都用 ECA,开发者更熟悉
- **更符合自动化场景**: "当 X 出现时做 Y" 比状态转换图更直观
- **组合性好**: 多个 Event 独立声明,无需手动维护状态转换

### 为什么 workflow 是独立模块?

- **职责单一**: 主库保持"原子动作库"定位,编排是可选能力
- **体积控制**: workflow +155KB 不影响只用原子动作的宿主
- **演进自由**: 编排层可独立发版

### 为什么用 AutomationBackend 接口?

- **解耦**: 编排层不依赖 :app 的内部实现,可独立测试(注入 mock)
- **可扩展**: 未来可支持非 Android 后端(桌面自动化、远程设备)
- **测试友好**: 38 个单元测试无需真机,全部用 fake backend

### 为什么 TimerReached 用 contextKey + index?

早期版本用 `condition.hashCode()` 作为计时器 key,导致两个相同字段的 TimerReached(如不同 Event 都用 `TimerReached(5000)`)共享同一个计时器。现在用 `Event.id + condition index` 保证唯一性。

## 测试

```bash
# 运行所有 workflow 测试
./gradlew :workflow:testDebugUnitTest

# 运行特定测试类
./gradlew :workflow:testDebugUnitTest --tests "com.steve1316.automation_library.workflow.runtime.ScenarioExecutorTest"
```

**测试覆盖**(38 个测试):

| 测试类 | 数量 | 覆盖范围 |
|---|---|---|
| ProcessingStateTest | 6 | 计数器/计时器/事件状态 |
| ConditionsVerifierTest | 8 | AND/OR 短路 + TimerReached 隔离 |
| ActionExecutorTest | 13 | 所有 Action 类型 + 失败传播 |
| ScenarioExecutorTest | 6 | 主循环 + 取消 + keepEvaluating + pollInterval |
| WorkflowDslTest | 3 | DSL 构建 |
| ScenarioSerializationTest | 2 | JSON round-trip |

## 兼容性

- **minSdk**: 24(与主库一致)
- **Kotlin**: 1.9+
- **Java**: 17
- **ABI**: arm64-v8a, armeabi-v7a, x86_64(由主库提供)

## 相关文档

- [主库 README](../app/README.md)
- [Bugfix 计划](../docs/superpowers/plans/2026-07-25-workflow-bugfixes.md)
- [ECA 实施计划](../docs/superpowers/plans/2026-07-24-workflow-eca-module.md)
- [Smart-AutoClicker 参考](https://github.com/Nain57/Smart-AutoClicker)
