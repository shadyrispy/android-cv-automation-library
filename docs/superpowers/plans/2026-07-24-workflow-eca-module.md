# Workflow Module (ECA Orchestration) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在当前 android-cv-automation-library 仓库中新增独立的 `workflow` Gradle 模块,实现事件-条件-动作(ECA)编排引擎,作为可选 AAR 独立发布,供宿主 App(如 genshin-inventory-scanner-v2)按需引入。

**Architecture:** 三层职责分离——
1. **原子动作层**(`:app` 现有 AAR,不变):tap/swipe/findImage/findText/wait 等
2. **编排层**(新增 `:workflow` 模块):ECA 引擎(Scenario/Event/Condition/Action)+ Executor + State + JSON 序列化
3. **业务层**(未来宿主项目):具体场景定义、模板图、UI

`workflow` 模块编译期依赖 `:app`(仅依赖其公开 API,不依赖内部实现),但运行期通过接口解耦——`workflow` 定义 `AutomationBackend` 接口,由宿主 App 注入 `ImageUtils + MyAccessibilityService` 的桥接实现。

**Tech Stack:**
- Kotlin 2.2.0(与主项目一致)
- Android Library(`com.android.library` 插件)
- kotlinx-serialization-json 1.6.3(JSON 序列化,~150KB)
- kotlinx-coroutines-android 1.8.0(异步条件 + delay,~80KB)
- JUnit 4 + Robolectric(单元测试)
- minSdk 24 / compileSdk 35(与主项目一致)

---

## 文件结构

新增 `workflow/` 顶级 Gradle 模块,目录树如下:

```
workflow/
├── build.gradle.kts                       # 模块构建配置
├── consumer-rules.pro                     # 消费者 ProGuard 规则
├── proguard-rules.pro                     # 本模块 ProGuard 规则
└── src/
    ├── main/
    │   ├── AndroidManifest.xml            # 空清单(库模块占位)
    │   └── java/com/steve1316/automation_library/workflow/
    │       ├── AutomationBackend.kt        # 接口:桥接原子动作(tap/findImage/...)
    │       ├── model/
    │       │   ├── Scenario.kt             # data class Scenario
    │       │   ├── Event.kt                # sealed class Event { Image, Trigger }
    │       │   ├── Condition.kt            # sealed class Condition + 子类型
    │       │   ├── Action.kt               # sealed class Action + 子类型
    │       │   ├── ConditionOperator.kt    # enum AND/OR
    │       │   └── JsonSerializers.kt      # @Serializable 注解 + polymorphic serializer
    │       ├── runtime/
    │       │   ├── ProcessingState.kt       # 运行时状态(计数器/变量/已扫项)
    │       │   ├── ConditionsVerifier.kt   # AND/OR 短路求值器
    │       │   ├── ActionExecutor.kt       # Strategy 多态分发到 AutomationBackend
    │       │   ├── ScenarioExecutor.kt     # 主执行循环 + 中断检查 + 超时
    │       │   └── WorkflowContext.kt       # 每次执行上下文(backend + state + logger)
    │       ├── dsl/
    │       │   └── WorkflowDsl.kt           # @DslMarker + workflow { } 构建器
    │       └── logging/
    │           └── StepLogger.kt           # 步骤级日志接口(默认 no-op)
    └── test/
        └── java/com/steve1316/automation_library/workflow/
            ├── model/ScenarioSerializationTest.kt
            ├── runtime/ConditionsVerifierTest.kt
            ├── runtime/ActionExecutorTest.kt
            ├── runtime/ScenarioExecutorTest.kt
            └── dsl/WorkflowDslTest.kt
```

**修改的现有文件**:

| 文件 | 修改内容 |
|---|---|
| `settings.gradle.kts` | 添加 `include(":workflow")` |
| `gradle/libs.versions.toml` | 添加 `kotlinxSerializationJson` / `kotlinxCoroutines` / `junit` / `robolectric` 版本声明 |
| `app/build.gradle.kts` | 无需修改(workflow 依赖 app,反向不依赖) |
| `.learnings/LEARNINGS.md` | 完成后追加 LRN 条目记录架构决策 |

---

## Task 1: 创建 workflow Gradle 模块骨架

**目标**: 建立模块目录结构,确保 `./gradlew :workflow:build` 能跑空构建。

**Files:**
- Create: `workflow/build.gradle.kts`
- Create: `workflow/consumer-rules.pro`
- Create: `workflow/proguard-rules.pro`
- Create: `workflow/src/main/AndroidManifest.xml`
- Modify: `settings.gradle.kts`
- Modify: `gradle/libs.versions.toml`

- [ ] **Step 1.1: 在 settings.gradle.kts 中添加 workflow 模块**

修改 `/Users/esc/Documents/android-cv-automation-library/settings.gradle.kts`,在 `include(":app")` 后追加:

```kotlin
include(":app")
include(":workflow")
rootProject.name = "Automation Library"
```

- [ ] **Step 1.2: 在 libs.versions.toml 添加依赖版本声明**

修改 `/Users/esc/Documents/android-cv-automation-library/gradle/libs.versions.toml`,在 `[versions]` 末尾追加:

```toml
kotlinxSerializationJson = "1.6.3"
kotlinxCoroutines = "1.8.0"
junit = "4.13.2"
robolectric = "4.12.2"
```

在 `[libraries]` 末尾追加:

```toml
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "kotlinxSerializationJson" }
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "kotlinxCoroutines" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "kotlinxCoroutines" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
robolectric = { group = "org.robolectric", name = "robolectric", version.ref = "robolectric" }
```

在 `[plugins]` 末尾追加:

```toml
kotlinSerialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlinGradlePlugin" }
```

- [ ] **Step 1.3: 创建 workflow/build.gradle.kts**

创建 `/Users/esc/Documents/android-cv-automation-library/workflow/build.gradle.kts`:

```kotlin
import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "com.steve1316.automation_library.workflow"
    compileSdk = libs.versions.app.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.app.buildToolsVersion.get()

    defaultConfig {
        minSdk = libs.versions.app.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // 编排层依赖原子动作层(仅接口,不依赖实现)
    api(project(":app"))

    // JSON 序列化(Scenario 可选持久化到文件)
    api(libs.kotlinx.serialization.json)

    // 协程(用于异步条件求值和 delay)
    api(libs.kotlinx.coroutines.android)

    // 单元测试
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation("org.jetbrains.kotlin:kotlin-test")
}

version = libs.versions.app.versionName.get()

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components.getByName("release"))
                groupId = "com.github.shadyrispy"
                artifactId = "automation_library-workflow"
                version = libs.versions.app.versionName.get()
            }
        }
        repositories {
            mavenLocal()
        }
    }
}
```

- [ ] **Step 1.4: 创建 ProGuard 规则文件**

创建 `/Users/esc/Documents/android-cv-automation-library/workflow/consumer-rules.pro`:

```proguard
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# Keep serializable classes and their companion objects
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep all sealed class subclasses (ECA model)
-keep class com.steve1316.automation_library.workflow.model.** { *; }
```

创建 `/Users/esc/Documents/android-cv-automation-library/workflow/proguard-rules.pro`(空文件占位):

```proguard
# Add project-specific ProGuard rules here.
```

- [ ] **Step 1.5: 创建 AndroidManifest.xml**

创建 `/Users/esc/Documents/android-cv-automation-library/workflow/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" />
```

- [ ] **Step 1.6: 验证空构建**

运行:

```bash
cd /Users/esc/Documents/android-cv-automation-library && ./gradlew :workflow:build
```

Expected: BUILD SUCCESSFUL(可能 warn 空 source set,这是正常的)

- [ ] **Step 1.7: 提交**

```bash
cd /Users/esc/Documents/android-cv-automation-library
git add settings.gradle.kts gradle/libs.versions.toml workflow/
git commit -m "build(workflow): scaffold workflow Gradle module skeleton"
```

---

## Task 2: 定义 ECA 数据模型(Scenario/Event/Condition/Action)

**目标**: 创建不可变的 ECA 数据类,支持 kotlinx.serialization 序列化。

**Files:**
- Create: `workflow/src/main/java/com/steve1316/automation_library/workflow/model/ConditionOperator.kt`
- Create: `workflow/src/main/java/com/steve1316/automation_library/workflow/model/Condition.kt`
- Create: `workflow/src/main/java/com/steve1316/automation_library/workflow/model/Action.kt`
- Create: `workflow/src/main/java/com/steve1316/automation_library/workflow/model/Event.kt`
- Create: `workflow/src/main/java/com/steve1316/automation_library/workflow/model/Scenario.kt`

- [ ] **Step 2.1: 创建 ConditionOperator.kt**

```kotlin
package com.steve1316.automation_library.workflow.model

import kotlinx.serialization.Serializable

/**
 * 条件之间的逻辑组合方式。
 * - [AND]: 所有条件都满足才为 true(短路:遇到 false 立即返回)
 * - [OR]: 任一条件满足即为 true(短路:遇到 true 立即返回)
 */
@Serializable
enum class ConditionOperator {
    AND,
    OR,
}
```

- [ ] **Step 2.2: 创建 Condition.kt(sealed class + 子类型)**

```kotlin
package com.steve1316.automation_library.workflow.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * ECA 中的 Condition:决定 Event 是否触发的判断单元。
 *
 * 所有子类型为 [Serializable],可序列化为 JSON 用于持久化。
 * 子类型按"求值代价"由低到高排列,便于 [ConditionsVerifier] 做短路优化。
 */
@Serializable
sealed class Condition {

    /**
     * 图像模板匹配条件:屏幕上是否出现指定模板。
     *
     * @property templateName 模板图名称(对应宿主 App assets 中的文件名,不含扩展名)
     * @property confidence 匹配置信度阈值,默认 0.8
     * @property region 搜索区域 [x, y, width, height],默认全屏
     * @property shouldBeDetected true=出现时满足;false=消失时满足
     */
    @Serializable
    @SerialName("image_appears")
    data class ImageAppears(
        val templateName: String,
        val confidence: Double = 0.8,
        val region: List<Int> = listOf(0, 0, 0, 0),
        val shouldBeDetected: Boolean = true,
    ) : Condition()

    /**
     * OCR 文本匹配条件:指定区域是否包含某文本。
     *
     * @property text 要匹配的文本(子串匹配,大小写不敏感)
     * @property region OCR 区域 [x, y, width, height]
     * @property similarity 文本相似度阈值(JaroWinkler),默认 0.8 容忍 OCR 噪声
     */
    @Serializable
    @SerialName("text_matches")
    data class TextMatches(
        val text: String,
        val region: List<Int> = listOf(0, 0, 0, 0),
        val similarity: Double = 0.8,
    ) : Condition()

    /**
     * 计数器条件:运行时计数器达到指定值。
     *
     * @property counterName 计数器名称(对应 [com.steve1316.automation_library.workflow.runtime.ProcessingState] 中的 key)
     * @property targetValue 目标值
     * @property comparison 比较方式
     */
    @Serializable
    @SerialName("counter_reached")
    data class CounterReached(
        val counterName: String,
        val targetValue: Long,
        val comparison: Comparison = Comparison.GREATER_OR_EQUAL,
    ) : Condition() {

        @Serializable
        enum class Comparison {
            EQUAL,
            GREATER_OR_EQUAL,
            LESS_OR_EQUAL,
        }
    }

    /**
     * 定时器条件:从激活开始计时,达到指定时长后满足。
     *
     * @property durationMs 时长(毫秒)
     * @property restartWhenReached 达到后是否重新计时(用于周期性触发)
     */
    @Serializable
    @SerialName("timer_reached")
    data class TimerReached(
        val durationMs: Long,
        val restartWhenReached: Boolean = false,
    ) : Condition()

    /**
     * 自定义条件:由宿主 App 提供的 lambda 求值。
     *
     * 注意:[Serializable] 标注但 lambda 不可序列化,
     * 序列化时会被丢弃(默认返回 false);仅用于代码内定义,不用于 JSON 持久化。
     *
     * @property id 自定义条件标识符(用于在宿主 App 中查找对应的求值函数)
     */
    @Serializable
    @SerialName("custom")
    data class Custom(
        val id: String,
    ) : Condition()
}
```

- [ ] **Step 2.3: 创建 Action.kt(sealed class + 子类型)**

```kotlin
package com.steve1316.automation_library.workflow.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * ECA 中的 Action:条件满足时执行的动作单元。
 *
 * 所有子类型为 [Serializable]。执行由
 * [com.steve1316.automation_library.workflow.runtime.ActionExecutor] 多态分发到
 * [com.steve1316.automation_library.workflow.AutomationBackend]。
 */
@Serializable
sealed class Action {

    /** 点击指定坐标或模板中心。 */
    @Serializable
    @SerialName("tap")
    data class Tap(
        val x: Double,
        val y: Double,
        val imageName: String? = null,
    ) : Action()

    /** 长按。 */
    @Serializable
    @SerialName("long_press")
    data class LongPress(
        val x: Double,
        val y: Double,
        val imageName: String? = null,
        val durationMs: Long = 1000,
    ) : Action()

    /** 滑动。 */
    @Serializable
    @SerialName("swipe")
    data class Swipe(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val durationMs: Long = 500,
    ) : Action()

    /** 滚动整屏。 */
    @Serializable
    @SerialName("scroll")
    data class Scroll(
        val scrollDown: Boolean = true,
        val durationMs: Long = 500,
    ) : Action()

    /** 等待固定时长。 */
    @Serializable
    @SerialName("wait")
    data class Wait(
        val seconds: Double,
    ) : Action()

    /** 修改运行时计数器。 */
    @Serializable
    @SerialName("change_counter")
    data class ChangeCounter(
        val counterName: String,
        val delta: Long,
    ) : Action()

    /** 启用或禁用指定 Event(对应 Smart-AutoClicker 的 ToggleEvent)。 */
    @Serializable
    @SerialName("toggle_event")
    data class ToggleEvent(
        val eventName: String,
        val enabled: Boolean,
    ) : Action()

    /** 完成整个 Scenario 执行。 */
    @Serializable
    @SerialName("complete")
    data object Complete : Action()

    /** 自定义动作:由宿主 App 通过 [AutomationBackend.executeCustomAction] 处理。 */
    @Serializable
    @SerialName("custom")
    data class Custom(
        val id: String,
    ) : Action()
}
```

- [ ] **Step 2.4: 创建 Event.kt(sealed class + 两种类型)**

```kotlin
package com.steve1316.automation_library.workflow.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * ECA 中的 Event:一个可被触发的逻辑单元,包含条件和动作列表。
 *
 * 分为两类:
 * - [Image]:需要屏幕图像分析(findImage/findText)的条件,代价较高
 * - [Trigger]:基于计数器/计时器/自定义,不需图像分析,代价较低
 *
 * [ScenarioExecutor] 按 [priority] 升序处理(数值小先处理),
 * 同帧内首个满足条件的 Event 执行其 actions,其余跳过(除非设置 [keepEvaluating])。
 */
@Serializable
sealed class Event {

    abstract val id: String
    abstract val name: String
    abstract val conditionOperator: ConditionOperator
    abstract val conditions: List<Condition>
    abstract val actions: List<Action>
    abstract val enabledOnStart: Boolean

    /**
     * 图像事件:包含图像/OCR 类条件。
     *
     * @property priority 优先级(数值小先评估)
     * @property keepEvaluating 当前 Event 触发后是否继续评估后续 Event
     *                           (对应 Smart-AutoClicker 的 keepDetecting)
     */
    @Serializable
    @SerialName("image_event")
    data class Image(
        override val id: String,
        override val name: String,
        override val conditionOperator: ConditionOperator = ConditionOperator.AND,
        override val conditions: List<Condition>,
        override val actions: List<Action>,
        override val enabledOnStart: Boolean = true,
        val priority: Int = 0,
        val keepEvaluating: Boolean = false,
    ) : Event()

    /**
     * 触发事件:仅基于计时器/计数器/自定义,不依赖图像分析。
     * [ScenarioExecutor] 会优先评估所有 Trigger 事件(廉价),再评估 Image 事件(昂贵)。
     */
    @Serializable
    @SerialName("trigger_event")
    data class Trigger(
        override val id: String,
        override val name: String,
        override val conditionOperator: ConditionOperator = ConditionOperator.AND,
        override val conditions: List<Condition>,
        override val actions: List<Action>,
        override val enabledOnStart: Boolean = true,
    ) : Event()
}
```

- [ ] **Step 2.5: 创建 Scenario.kt**

```kotlin
package com.steve1316.automation_library.workflow.model

import kotlinx.serialization.Serializable

/**
 * 一个完整的自动化场景:由多个 [Event] 组成。
 *
 * 执行模型(借鉴 Smart-AutoClicker 但简化):
 * - [ScenarioExecutor] 按事件优先级循环评估
 * - 首个满足条件的 Event 执行 actions,然后进入下一轮
 * - 当所有 Event 被 disabled 或收到 [Action.Complete] 时结束
 *
 * @property id 唯一标识
 * @property name 人类可读名称
 * @property events 事件列表(可混合 [Event.Image] 和 [Event.Trigger])
 * @property maxDurationMinutes 最长执行时长(分钟),0 表示不限
 */
@Serializable
data class Scenario(
    val id: String,
    val name: String,
    val events: List<Event>,
    val maxDurationMinutes: Int = 0,
)
```

- [ ] **Step 2.6: 验证编译**

```bash
cd /Users/esc/Documents/android-cv-automation-library && ./gradlew :workflow:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 2.7: 提交**

```bash
cd /Users/esc/Documents/android-cv-automation-library
git add workflow/src/main/java/com/steve1316/automation_library/workflow/model/
git commit -m "feat(workflow): add ECA data model (Scenario/Event/Condition/Action)"
```

---

## Task 3: 定义 AutomationBackend 接口(桥接原子动作)

**目标**: 定义编排层与原子动作层的解耦接口,宿主 App 实现此接口注入到 Executor。

**Files:**
- Create: `workflow/src/main/java/com/steve1316/automation_library/workflow/AutomationBackend.kt`
- Create: `workflow/src/main/java/com/steve1316/automation_library/workflow/logging/StepLogger.kt`

- [ ] **Step 3.1: 创建 StepLogger.kt**

```kotlin
package com.steve1316.automation_library.workflow.logging

/**
 * 步骤级日志接口。默认实现为 no-op,宿主可注入真实实现(如 MessageLog)。
 *
 * 所有方法都是同步的,调用方在合适的线程调用。
 */
interface StepLogger {

    /** 记录 Event 评估开始。 */
    fun onEventEvaluated(eventName: String, satisfied: Boolean) {}

    /** 记录 Action 执行。 */
    fun onActionExecuted(action: String, success: Boolean) {}

    /** 记录普通信息。 */
    fun info(message: String) {}

    /** 记录警告。 */
    fun warn(message: String) {}

    /** 记录错误。 */
    fun error(message: String, throwable: Throwable? = null) {}

    /** 空实现。 */
    object Noop : StepLogger
}
```

- [ ] **Step 3.2: 创建 AutomationBackend.kt**

```kotlin
package com.steve1316.automation_library.workflow

import android.graphics.Bitmap
import android.graphics.PointF
import com.steve1316.automation_library.workflow.logging.StepLogger

/**
 * 编排层与原子动作层的桥接接口。
 *
 * **宿主 App 责任**:实现此接口,将 ECA 的 [model.Condition] 和 [model.Action]
 * 翻译为对 `ImageUtils` / `MyAccessibilityService` 的具体调用。
 *
 * **为什么用接口而不是直接依赖 ImageUtils**:
 * 1. 编排层与原子动作层解耦,可独立测试(测试时注入 mock backend)
 * 2. 未来可支持非 Android 后端(如桌面自动化、远程设备)
 * 3. 避免 workflow 模块编译期强依赖 app 模块的内部实现细节
 *
 * 所有方法都是 **synchronous**(同步)的,在 [ScenarioExecutor] 的执行线程上调用。
 * 实现方可使用 `runBlocking` 包裹协程代码(与现有 ImageUtils.wait() 一致)。
 */
interface AutomationBackend {

    /** 日志器,由 Executor 注入。 */
    val logger: StepLogger

    // ====== 条件求值(对应 Condition 子类型) ======

    /**
     * 检查屏幕上是否出现指定模板。
     *
     * @return 命中点的中心坐标,未命中返回 null
     */
    fun findImage(
        templateName: String,
        confidence: Double = 0.8,
        region: IntArray = intArrayOf(0, 0, 0, 0),
    ): PointF?

    /**
     * 检查指定区域是否包含指定文本(OCR + 相似度匹配)。
     *
     * @return 命中的文本,未命中返回 null
     */
    fun findText(
        text: String,
        region: IntArray = intArrayOf(0, 0, 0, 0),
        similarity: Double = 0.8,
    ): String?

    // ====== 动作执行(对应 Action 子类型) ======

    /** 单击/多击。 */
    fun tap(x: Double, y: Double, imageName: String? = null): Boolean

    /** 长按。 */
    fun longPress(x: Double, y: Double, imageName: String? = null, durationMs: Long = 1000): Boolean

    /** 滑动。 */
    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 500): Boolean

    /** 滚动整屏。 */
    fun scroll(scrollDown: Boolean = true, durationMs: Long = 500): Boolean

    /** 等待固定时长(秒)。 */
    fun wait(seconds: Double)

    // ====== 自定义扩展点 ======

    /**
     * 求值自定义条件。宿主 App 按 [id] 查找对应的 lambda 并返回结果。
     * 默认返回 false(未知条件不满足)。
     */
    fun evaluateCustomCondition(id: String): Boolean = false

    /**
     * 执行自定义动作。宿主 App 按 [id] 查找对应的逻辑。
     * 默认返回 true(视为成功)。
     */
    fun executeCustomAction(id: String): Boolean = true

    /**
     * 检查执行是否应被中断(对应 BotService.isRunning 检查)。
     * [ScenarioExecutor] 在每个 Event 评估前调用此方法。
     */
    fun isCancelled(): Boolean = false
}
```

- [ ] **Step 3.3: 验证编译**

```bash
cd /Users/esc/Documents/android-cv-automation-library && ./gradlew :workflow:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3.4: 提交**

```bash
cd /Users/esc/Documents/android-cv-automation-library
git add workflow/src/main/java/com/steve1316/automation_library/workflow/AutomationBackend.kt \
        workflow/src/main/java/com/steve1316/automation_library/workflow/logging/
git commit -m "feat(workflow): define AutomationBackend interface for decoupling"
```

---

## Task 4: 实现 ProcessingState(运行时状态)

**目标**: 管理运行时计数器、计时器、事件启用状态。

**Files:**
- Create: `workflow/src/main/java/com/steve1316/automation_library/workflow/runtime/ProcessingState.kt`
- Test: `workflow/src/test/java/com/steve1316/automation_library/workflow/runtime/ProcessingStateTest.kt`

- [ ] **Step 4.1: 先写失败测试**

```kotlin
package com.steve1316.automation_library.workflow.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingStateTest {

    @Test
    fun `counter starts at zero and increments by delta`() {
        val state = ProcessingState()
        assertEquals(0L, state.getCounter("clicks"))
        state.changeCounter("clicks", 3)
        assertEquals(3L, state.getCounter("clicks"))
        state.changeCounter("clicks", -1)
        assertEquals(2L, state.getCounter("clicks"))
    }

    @Test
    fun `counter reached returns true when value meets comparison`() {
        val state = ProcessingState()
        state.changeCounter("items", 5)

        assertTrue(state.isCounterReached("items", 5, ProcessingState.Comparison.EQUAL))
        assertTrue(state.isCounterReached("items", 5, ProcessingState.Comparison.GREATER_OR_EQUAL))
        assertTrue(state.isCounterReached("items", 10, ProcessingState.Comparison.LESS_OR_EQUAL))
        assertFalse(state.isCounterReached("items", 10, ProcessingState.Comparison.GREATER_OR_EQUAL))
    }

    @Test
    fun `timer starts fresh and reports reached after duration`() {
        val state = ProcessingState()
        state.startTimer("t1", durationMs = 100, restartWhenReached = false)

        // 未到时间
        assertFalse(state.isTimerReached("t1", nowMs = 50))
        // 到达时间
        assertTrue(state.isTimerReached("t1", nowMs = 100))
    }

    @Test
    fun `timer with restart resets start time after reached`() {
        val state = ProcessingState()
        state.startTimer("t2", durationMs = 100, restartWhenReached = true)

        // 100ms 时触发,重置 start = 100
        assertTrue(state.isTimerReached("t2", nowMs = 100))
        // 199ms 时未到(从 100 起算)
        assertFalse(state.isTimerReached("t2", nowMs = 199))
        // 200ms 时再次到
        assertTrue(state.isTimerReached("t2", nowMs = 200))
    }

    @Test
    fun `event enabled state defaults to enabledOnStart and can be toggled`() {
        val state = ProcessingState()
        state.initEvents(listOf("e1" to true, "e2" to false))

        assertTrue(state.isEventEnabled("e1"))
        assertFalse(state.isEventEnabled("e2"))

        state.setEventEnabled("e1", false)
        assertFalse(state.isEventEnabled("e1"))
    }

    @Test
    fun `all events disabled returns true when every event is off`() {
        val state = ProcessingState()
        state.initEvents(listOf("e1" to true, "e2" to true))

        assertFalse(state.allEventsDisabled())

        state.setEventEnabled("e1", false)
        state.setEventEnabled("e2", false)
        assertTrue(state.allEventsDisabled())
    }
}
```

- [ ] **Step 4.2: 运行测试验证失败**

```bash
cd /Users/esc/Documents/android-cv-automation-library && ./gradlew :workflow:testDebugUnitTest --tests "com.steve1316.automation_library.workflow.runtime.ProcessingStateTest"
```

Expected: FAIL with `Unresolved reference: ProcessingState`

- [ ] **Step 4.3: 实现 ProcessingState**

```kotlin
package com.steve1316.automation_library.workflow.runtime

import java.util.concurrent.ConcurrentHashMap

/**
 * 运行时状态:管理计数器、计时器、事件启用状态。
 *
 * 线程安全:所有可变状态用 [ConcurrentHashMap] 存储,[ScenarioExecutor] 单线程写,
 * UI 线程可并发读(用于进度展示)。
 *
 * @param nowMsProvider 当前时间戳提供者(毫秒),测试时可注入 fake clock
 */
class ProcessingState(
    private val nowMsProvider: () -> Long = System::currentTimeMillis,
) {

    enum class Comparison { EQUAL, GREATER_OR_EQUAL, LESS_OR_EQUAL }

    // ====== 计数器 ======
    private val counters = ConcurrentHashMap<String, java.lang.Long>()

    /** 获取计数器当前值,不存在返回 0。 */
    fun getCounter(name: String): Long = counters[name]?.toLong() ?: 0L

    /** 增减计数器。 */
    fun changeCounter(name: String, delta: Long) {
        counters.compute(name) { _, v ->
            java.lang.Long.valueOf((v?.toLong() ?: 0L) + delta)
        }
    }

    /** 判断计数器是否达到目标值。 */
    fun isCounterReached(name: String, target: Long, comparison: Comparison): Boolean {
        val current = getCounter(name)
        return when (comparison) {
            Comparison.EQUAL -> current == target
            Comparison.GREATER_OR_EQUAL -> current >= target
            Comparison.LESS_OR_EQUAL -> current <= target
        }
    }

    // ====== 计时器 ======
    private data class TimerState(var startMs: Long, val durationMs: Long, val restart: Boolean)
    private val timers = ConcurrentHashMap<String, TimerState>()

    /** 启动一个计时器。 */
    fun startTimer(name: String, durationMs: Long, restartWhenReached: Boolean) {
        timers[name] = TimerState(nowMsProvider(), durationMs, restartWhenReached)
    }

    /** 判断计时器是否到达。 */
    fun isTimerReached(name: String, nowMs: Long = nowMsProvider()): Boolean {
        val timer = timers[name] ?: return false
        val elapsed = nowMs - timer.startMs
        val reached = elapsed >= timer.durationMs
        if (reached && timer.restart) {
            timer.startMs = nowMs
        }
        return reached
    }

    // ====== 事件启用状态 ======
    private val eventEnabled = ConcurrentHashMap<String, Boolean>()

    /** 初始化所有事件的启用状态(按 enabledOnStart)。 */
    fun initEvents(events: List<Pair<String, Boolean>>) {
        events.forEach { (name, enabled) -> eventEnabled[name] = enabled }
    }

    /** 事件是否启用。 */
    fun isEventEnabled(eventName: String): Boolean = eventEnabled[eventName] ?: false

    /** 设置事件启用状态(对应 Action.ToggleEvent)。 */
    fun setEventEnabled(eventName: String, enabled: Boolean) {
        eventEnabled[eventName] = enabled
    }

    /** 是否所有事件都被禁用(Executor 据此退出)。 */
    fun allEventsDisabled(): Boolean = eventEnabled.values.all { !it }
}
```

- [ ] **Step 4.4: 运行测试验证通过**

```bash
cd /Users/esc/Documents/android-cv-automation-library && ./gradlew :workflow:testDebugUnitTest --tests "com.steve1316.automation_library.workflow.runtime.ProcessingStateTest"
```

Expected: BUILD SUCCESSFUL, 6 tests passed

- [ ] **Step 4.5: 提交**

```bash
cd /Users/esc/Documents/android-cv-automation-library
git add workflow/src/main/java/com/steve1316/automation_library/workflow/runtime/ProcessingState.kt \
        workflow/src/test/java/com/steve1316/automation_library/workflow/runtime/ProcessingStateTest.kt
git commit -m "feat(workflow): implement ProcessingState with tests"
```

---

## Task 5: 实现 ConditionsVerifier(条件求值器)

**目标**: AND/OR 短路求值,委托给 AutomationBackend 做具体检测。

**Files:**
- Create: `workflow/src/main/java/com/steve1316/automation_library/workflow/runtime/ConditionsVerifier.kt`
- Test: `workflow/src/test/java/com/steve1316/automation_library/workflow/runtime/ConditionsVerifierTest.kt`

- [ ] **Step 5.1: 先写失败测试**

```kotlin
package com.steve1316.automation_library.workflow.runtime

import android.graphics.PointF
import com.steve1316.automation_library.workflow.AutomationBackend
import com.steve1316.automation_library.workflow.logging.StepLogger
import com.steve1316.automation_library.workflow.model.Condition
import com.steve1316.automation_library.workflow.model.ConditionOperator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConditionsVerifierTest {

    private val noopLogger = object : StepLogger {}

    private fun backend(
        imageResult: PointF? = null,
        textResult: String? = null,
        customResult: Boolean = false,
    ): AutomationBackend = object : AutomationBackend {
        override val logger = noopLogger
        override fun findImage(templateName: String, confidence: Double, region: IntArray) = imageResult
        override fun findText(text: String, region: IntArray, similarity: Double) = textResult
        override fun tap(x: Double, y: Double, imageName: String?) = true
        override fun longPress(x: Double, y: Double, imageName: String?, durationMs: Long) = true
        override fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long) = true
        override fun scroll(scrollDown: Boolean, durationMs: Long) = true
        override fun wait(seconds: Double) {}
        override fun evaluateCustomCondition(id: String) = customResult
    }

    @Test
    fun `AND returns true when all conditions satisfied`() {
        val state = ProcessingState(nowMsProvider = { 0L })
        state.changeCounter("c1", 5)
        val verifier = ConditionsVerifier(state)
        val conditions = listOf(
            Condition.CounterReached("c1", 5),
            Condition.ImageAppears("btn", shouldBeDetected = true),
        )
        assertTrue(verifier.verify(conditions, ConditionOperator.AND, backend(imageResult = PointF(1f, 1f))))
    }

    @Test
    fun `AND short circuits on first false`() {
        val state = ProcessingState()
        val verifier = ConditionsVerifier(state)
        val conditions = listOf(
            Condition.CounterReached("missing", 100),  // false (counter=0)
            Condition.ImageAppears("btn"),               // 不会被求值
        )
        assertFalse(verifier.verify(conditions, ConditionOperator.AND, backend(imageResult = PointF(1f, 1f))))
    }

    @Test
    fun `OR returns true when any condition satisfied`() {
        val state = ProcessingState()
        val verifier = ConditionsVerifier(state)
        val conditions = listOf(
            Condition.CounterReached("missing", 100),  // false
            Condition.ImageAppears("btn"),               // true
        )
        assertTrue(verifier.verify(conditions, ConditionOperator.OR, backend(imageResult = PointF(1f, 1f))))
    }

    @Test
    fun `OR short circuits on first true`() {
        val state = ProcessingState()
        state.changeCounter("c1", 100)
        val verifier = ConditionsVerifier(state)
        val conditions = listOf(
            Condition.CounterReached("c1", 100),  // true
            Condition.ImageAppears("btn"),               // 不会被求值
        )
        assertTrue(verifier.verify(conditions, ConditionOperator.OR, backend(imageResult = null)))
    }

    @Test
    fun `ImageAppears with shouldBeDetected=false returns true when image absent`() {
        val state = ProcessingState()
        val verifier = ConditionsVerifier(state)
        val conditions = listOf(Condition.ImageAppears("popup", shouldBeDetected = false))
        assertTrue(verifier.verify(conditions, ConditionOperator.AND, backend(imageResult = null)))
    }

    @Test
    fun `TextMatches returns true when text found`() {
        val state = ProcessingState()
        val verifier = ConditionsVerifier(state)
        val conditions = listOf(Condition.TextMatches("已强化"))
        assertTrue(verifier.verify(conditions, ConditionOperator.AND, backend(textResult = "已强化+20")))
    }
}
```

- [ ] **Step 5.2: 运行测试验证失败**

```bash
cd /Users/esc/Documents/android-cv-automation-library && ./gradlew :workflow:testDebugUnitTest --tests "com.steve1316.automation_library.workflow.runtime.ConditionsVerifierTest"
```

Expected: FAIL with `Unresolved reference: ConditionsVerifier`

- [ ] **Step 5.3: 实现 ConditionsVerifier**

```kotlin
package com.steve1316.automation_library.workflow.runtime

import com.steve1316.automation_library.workflow.AutomationBackend
import com.steve1316.automation_library.workflow.model.Condition
import com.steve1316.automation_library.workflow.model.ConditionOperator

/**
 * 条件求值器:按 [ConditionOperator] 对 [Condition] 列表做短路求值。
 *
 * 设计原则(借鉴 Smart-AutoClicker 的两阶段处理):
 * - 廉价条件(Counter/Timer/Custom)先求值
 * - 昂贵条件(Image/Text)后求值,可被廉价条件短路跳过
 *
 * 当前实现按 conditions 列表顺序求值,调用方应自行按"廉价在前"排列。
 */
class ConditionsVerifier(private val state: ProcessingState) {

    /**
     * 求值所有条件。
     *
     * @param conditions 条件列表
     * @param operator 组合方式
     * @param backend 原子动作桥接
     * @return true=条件满足(Event 可触发),false=不满足
     */
    fun verify(
        conditions: List<Condition>,
        operator: ConditionOperator,
        backend: AutomationBackend,
    ): Boolean {
        if (conditions.isEmpty()) return true  // 无条件视为满足

        for (condition in conditions) {
            val satisfied = evaluateOne(condition, backend)
            when (operator) {
                ConditionOperator.AND -> if (!satisfied) return false  // 短路:遇 false 立即返回
                ConditionOperator.OR -> if (satisfied) return true     // 短路:遇 true 立即返回
            }
        }
        // 循环结束:AND 全 true → true;OR 全 false → false
        return operator == ConditionOperator.AND
    }

    private fun evaluateOne(condition: Condition, backend: AutomationBackend): Boolean {
        return when (condition) {
            is Condition.ImageAppears -> {
                val hit = backend.findImage(
                    templateName = condition.templateName,
                    confidence = condition.confidence,
                    region = condition.region.toIntArray(),
                )
                if (condition.shouldBeDetected) hit != null else hit == null
            }

            is Condition.TextMatches -> {
                val found = backend.findText(
                    text = condition.text,
                    region = condition.region.toIntArray(),
                    similarity = condition.similarity,
                )
                found != null
            }

            is Condition.CounterReached -> state.isCounterReached(
                condition.counterName,
                condition.targetValue,
                when (condition.comparison) {
                    Condition.CounterReached.Comparison.EQUAL -> ProcessingState.Comparison.EQUAL
                    Condition.CounterReached.Comparison.GREATER_OR_EQUAL -> ProcessingState.Comparison.GREATER_OR_EQUAL
                    Condition.CounterReached.Comparison.LESS_OR_EQUAL -> ProcessingState.Comparison.LESS_OR_EQUAL
                },
            )

            is Condition.TimerReached -> {
                // 首次遇到时自动启动计时器
                if (!state.hasTimer(condition.hashCode().toString())) {
                    state.startTimer(
                        condition.hashCode().toString(),
                        condition.durationMs,
                        condition.restartWhenReached,
                    )
                }
                state.isTimerReached(condition.hashCode().toString())
            }

            is Condition.Custom -> backend.evaluateCustomCondition(condition.id)
        }
    }

    private fun List<Int>.toIntArray(): IntArray = if (size == 4) IntArray(4) { this[it] } else intArrayOf(0, 0, 0, 0)
}

// 扩展 ProcessingState 暴露 hasTimer(供 ConditionsVerifier 内部使用)
internal fun ProcessingState.hasTimer(name: String): Boolean = timerExists(name)
```

- [ ] **Step 5.4: 在 ProcessingState 中补充 timerExists 方法**

修改 `/Users/esc/Documents/android-cv-automation-library/workflow/src/main/java/com/steve1316/automation_library/workflow/runtime/ProcessingState.kt`,在计时器区域追加:

```kotlin
    /** 检查计时器是否已存在(用于 ConditionsVerifier 判断是否需要自动启动)。 */
    internal fun timerExists(name: String): Boolean = timers.containsKey(name)
```

- [ ] **Step 5.5: 运行测试验证通过**

```bash
cd /Users/esc/Documents/android-cv-automation-library && ./gradlew :workflow:testDebugUnitTest --tests "com.steve1316.automation_library.workflow.runtime.ConditionsVerifierTest"
```

Expected: BUILD SUCCESSFUL, 6 tests passed

- [ ] **Step 5.6: 提交**

```bash
cd /Users/esc/Documents/android-cv-automation-library
git add workflow/src/main/java/com/steve1316/automation_library/workflow/runtime/ConditionsVerifier.kt \
        workflow/src/main/java/com/steve1316/automation_library/workflow/runtime/ProcessingState.kt \
        workflow/src/test/java/com/steve1316/automation_library/workflow/runtime/ConditionsVerifierTest.kt
git commit -m "feat(workflow): implement ConditionsVerifier with short-circuit evaluation"
```

---

## Task 6: 实现 ActionExecutor(动作执行器)

**目标**: Strategy 模式多态分发 Action 到 AutomationBackend。

**Files:**
- Create: `workflow/src/main/java/com/steve1316/automation_library/workflow/runtime/ActionExecutor.kt`
- Test: `workflow/src/test/java/com/steve1316/automation_library/workflow/runtime/ActionExecutorTest.kt`

- [ ] **Step 6.1: 先写失败测试**

```kotlin
package com.steve1316.automation_library.workflow.runtime

import android.graphics.PointF
import com.steve1316.automation_library.workflow.AutomationBackend
import com.steve1316.automation_library.workflow.logging.StepLogger
import com.steve1316.automation_library.workflow.model.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionExecutorTest {

    private val noopLogger = object : StepLogger {}

    private class FakeBackend : AutomationBackend {
        override val logger = noopLogger
        var lastTap: Pair<Double, Double>? = null
        var lastSwipe: List<Float>? = null
        var lastScrollDown: Boolean? = null
        var lastWaitSeconds: Double? = null
        var counterChanges = mutableMapOf<String, Long>()
        var toggledEvents = mutableMapOf<String, Boolean>()
        var customActionsExecuted = mutableListOf<String>()
        var cancelled = false

        override fun findImage(templateName: String, confidence: Double, region: IntArray) = PointF(0f, 0f)
        override fun findText(text: String, region: IntArray, similarity: Double) = text
        override fun tap(x: Double, y: Double, imageName: String?): Boolean {
            lastTap = x to y
            return true
        }
        override fun longPress(x: Double, y: Double, imageName: String?, durationMs: Long) = true
        override fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long): Boolean {
            lastSwipe = listOf(startX, startY, endX, endY)
            return true
        }
        override fun scroll(scrollDown: Boolean, durationMs: Long): Boolean {
            lastScrollDown = scrollDown
            return true
        }
        override fun wait(seconds: Double) { lastWaitSeconds = seconds }
        override fun executeCustomAction(id: String): Boolean {
            customActionsExecuted.add(id)
            return true
        }
        override fun isCancelled() = cancelled
    }

    @Test
    fun `tap action calls backend tap`() {
        val backend = FakeBackend()
        val executor = ActionExecutor()
        executor.execute(Action.Tap(100.0, 200.0), backend, ProcessingState())
        assertEquals(100.0 to 200.0, backend.lastTap)
    }

    @Test
    fun `scroll action passes scrollDown flag`() {
        val backend = FakeBackend()
        val executor = ActionExecutor()
        executor.execute(Action.Scroll(scrollDown = false), backend, ProcessingState())
        assertEquals(false, backend.lastScrollDown)
    }

    @Test
    fun `wait action delegates to backend`() {
        val backend = FakeBackend()
        val executor = ActionExecutor()
        executor.execute(Action.Wait(2.5), backend, ProcessingState())
        assertEquals(2.5, backend.lastWaitSeconds)
    }

    @Test
    fun `change_counter updates processing state`() {
        val state = ProcessingState()
        val executor = ActionExecutor()
        executor.execute(Action.ChangeCounter("clicks", 3), FakeBackend(), state)
        assertEquals(3L, state.getCounter("clicks"))
    }

    @Test
    fun `toggle_event updates event enabled state`() {
        val state = ProcessingState()
        state.initEvents(listOf("e1" to true))
        val executor = ActionExecutor()
        executor.execute(Action.ToggleEvent("e1", enabled = false), FakeBackend(), state)
        assertFalse(state.isEventEnabled("e1"))
    }

    @Test
    fun `custom action delegates to backend`() {
        val backend = FakeBackend()
        val executor = ActionExecutor()
        executor.execute(Action.Custom("scan_row"), backend, ProcessingState())
        assertEquals(listOf("scan_row"), backend.customActionsExecuted)
    }

    @Test
    fun `complete action returns false to signal executor stop`() {
        val executor = ActionExecutor()
        val shouldContinue = executor.execute(Action.Complete, FakeBackend(), ProcessingState())
        assertFalse(shouldContinue)
    }

    @Test
    fun `normal action returns true to continue`() {
        val executor = ActionExecutor()
        val shouldContinue = executor.execute(Action.Tap(0.0, 0.0), FakeBackend(), ProcessingState())
        assertTrue(shouldContinue)
    }
}
```

- [ ] **Step 6.2: 运行测试验证失败**

```bash
cd /Users/esc/Documents/android-cv-automation-library && ./gradlew :workflow:testDebugUnitTest --tests "com.steve1316.automation_library.workflow.runtime.ActionExecutorTest"
```

Expected: FAIL with `Unresolved reference: ActionExecutor`

- [ ] **Step 6.3: 实现 ActionExecutor**

```kotlin
package com.steve1316.automation_library.workflow.runtime

import com.steve1316.automation_library.workflow.AutomationBackend
import com.steve1316.automation_library.workflow.model.Action

/**
 * 动作执行器:Strategy 模式多态分发 [Action] 到 [AutomationBackend]。
 *
 * 每次执行返回 Boolean:
 * - true: 继续执行下一个 action
 * - false: 收到 [Action.Complete],Executor 应结束整个 Scenario
 */
class ActionExecutor {

    fun execute(action: Action, backend: AutomationBackend, state: ProcessingState): Boolean {
        return when (action) {
            is Action.Tap -> {
                backend.tap(action.x, action.y, action.imageName)
                true
            }

            is Action.LongPress -> {
                backend.longPress(action.x, action.y, action.imageName, action.durationMs)
                true
            }

            is Action.Swipe -> {
                backend.swipe(action.startX, action.startY, action.endX, action.endY, action.durationMs)
                true
            }

            is Action.Scroll -> {
                backend.scroll(action.scrollDown, action.durationMs)
                true
            }

            is Action.Wait -> {
                backend.wait(action.seconds)
                true
            }

            is Action.ChangeCounter -> {
                state.changeCounter(action.counterName, action.delta)
                true
            }

            is Action.ToggleEvent -> {
                state.setEventEnabled(action.eventName, action.enabled)
                true
            }

            is Action.Complete -> false

            is Action.Custom -> {
                backend.executeCustomAction(action.id)
                true
            }
        }
    }
}
```

- [ ] **Step 6.4: 运行测试验证通过**

```bash
cd /Users/esc/Documents/android-cv-automation-library && ./gradlew :workflow:testDebugUnitTest --tests "com.steve1316.automation_library.workflow.runtime.ActionExecutorTest"
```

Expected: BUILD SUCCESSFUL, 8 tests passed

- [ ] **Step 6.5: 提交**

```bash
cd /Users/esc/Documents/android-cv-automation-library
git add workflow/src/main/java/com/steve1316/automation_library/workflow/runtime/ActionExecutor.kt \
        workflow/src/test/java/com/steve1316/automation_library/workflow/runtime/ActionExecutorTest.kt
git commit -m "feat(workflow): implement ActionExecutor with Strategy pattern"
```

---

## Task 7: 实现 ScenarioExecutor(主执行循环)

**目标**: 协调 ConditionsVerifier + ActionExecutor,提供主循环 + 中断检查 + 超时。

**Files:**
- Create: `workflow/src/main/java/com/steve1316/automation_library/workflow/runtime/ScenarioExecutor.kt`
- Test: `workflow/src/test/java/com/steve1316/automation_library/workflow/runtime/ScenarioExecutorTest.kt`

- [ ] **Step 7.1: 先写失败测试**

```kotlin
package com.steve1316.automation_library.workflow.runtime

import android.graphics.PointF
import com.steve1316.automation_library.workflow.AutomationBackend
import com.steve1316.automation_library.workflow.logging.StepLogger
import com.steve1316.automation_library.workflow.model.Action
import com.steve1316.automation_library.workflow.model.Condition
import com.steve1316.automation_library.workflow.model.ConditionOperator
import com.steve1316.automation_library.workflow.model.Event
import com.steve1316.automation_library.workflow.model.Scenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenarioExecutorTest {

    private val noopLogger = object : StepLogger {}

    private class FakeBackend(
        val imageHit: PointF? = null,
        val customConditionResult: Boolean = false,
    ) : AutomationBackend {
        override val logger = noopLogger
        val taps = mutableListOf<Pair<Double, Double>>()
        var waitCalled = false
        var cancelled = false

        override fun findImage(templateName: String, confidence: Double, region: IntArray) = imageHit
        override fun findText(text: String, region: IntArray, similarity: Double) = null
        override fun tap(x: Double, y: Double, imageName: String?): Boolean {
            taps += x to y
            return true
        }
        override fun longPress(x: Double, y: Double, imageName: String?, durationMs: Long) = true
        override fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long) = true
        override fun scroll(scrollDown: Boolean, durationMs: Long) = true
        override fun wait(seconds: Double) { waitCalled = true }
        override fun evaluateCustomCondition(id: String) = customConditionResult
        override fun isCancelled() = cancelled
    }

    @Test
    fun `scenario completes when action complete is executed`() {
        val scenario = Scenario(
            id = "s1",
            name = "test",
            events = listOf(
                Event.Image(
                    id = "e1",
                    name = "click-once",
                    conditions = listOf(Condition.ImageAppears("btn")),
                    actions = listOf(Action.Tap(100.0, 200.0), Action.Complete),
                ),
            ),
        )
        val backend = FakeBackend(imageHit = PointF(1f, 1f))
        val executor = ScenarioExecutor(backend)

        val result = executor.run(scenario)

        assertTrue(result.completedNormally)
        assertEquals(listOf(100.0 to 200.0), backend.taps)
    }

    @Test
    fun `scenario stops when backend reports cancelled`() {
        val scenario = Scenario(
            id = "s2",
            name = "test",
            events = listOf(
                Event.Image(
                    id = "e1",
                    name = "click-forever",
                    conditions = listOf(Condition.ImageAppears("btn")),
                    actions = listOf(Action.Tap(0.0, 0.0)),
                ),
            ),
        )
        val backend = FakeBackend(imageHit = PointF(1f, 1f))
        backend.cancelled = true  // 已取消
        val executor = ScenarioExecutor(backend)

        val result = executor.run(scenario)

        assertTrue(result.cancelled)
        assertEquals(0, backend.taps.size)  // 未执行任何 tap
    }

    @Test
    fun `scenario completes when all events disabled`() {
        val scenario = Scenario(
            id = "s3",
            name = "test",
            events = listOf(
                Event.Image(
                    id = "e1",
                    name = "self-disabling",
                    conditions = listOf(Condition.ImageAppears("btn")),
                    actions = listOf(
                        Action.ToggleEvent("e1", enabled = false),  // 关闭自己
                    ),
                ),
            ),
        )
        val backend = FakeBackend(imageHit = PointF(1f, 1f))
        val executor = ScenarioExecutor(backend)

        val result = executor.run(scenario)

        assertTrue(result.completedNormally)
    }

    @Test
    fun `trigger events evaluated before image events`() {
        val scenario = Scenario(
            id = "s4",
            name = "test",
            events = listOf(
                Event.Image(
                    id = "img",
                    name = "image-event",
                    conditions = listOf(Condition.ImageAppears("btn")),
                    actions = listOf(Action.Tap(1.0, 1.0)),
                ),
                Event.Trigger(
                    id = "trig",
                    name = "trigger-event",
                    conditions = listOf(Condition.CounterReached("c", 1, Condition.CounterReached.Comparison.GREATER_OR_EQUAL)),
                    actions = listOf(Action.Tap(2.0, 2.0), Action.Complete),
                ),
            ),
        )
        val backend = FakeBackend(imageHit = PointF(1f, 1f))
        // 预置计数器到 1,使 trigger event 先满足
        val executor = ScenarioExecutor(backend, initialState = ProcessingState().apply { changeCounter("c", 1) })

        val result = executor.run(scenario)

        assertTrue(result.completedNormally)
        // trigger 先执行,tap(2,2) 后 Complete,image event 没机会执行
        assertEquals(listOf(2.0 to 2.0), backend.taps)
    }
}
```

- [ ] **Step 7.2: 运行测试验证失败**

```bash
cd /Users/esc/Documents/android-cv-automation-library && ./gradlew :workflow:testDebugUnitTest --tests "com.steve1316.automation_library.workflow.runtime.ScenarioExecutorTest"
```

Expected: FAIL with `Unresolved reference: ScenarioExecutor`

- [ ] **Step 7.3: 实现 ScenarioExecutor**

```kotlin
package com.steve1316.automation_library.workflow.runtime

import com.steve1316.automation_library.workflow.AutomationBackend
import com.steve1316.automation_library.workflow.model.Event
import com.steve1316.automation_library.workflow.model.Scenario
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Scenario 主执行循环。
 *
 * 执行流程(借鉴 Smart-AutoClicker 简化版):
 * 1. 初始化 ProcessingState(事件启用状态)
 * 2. 循环:检查取消 → 检查超时 → 求值所有 Trigger Event → 求值所有 Image Event
 * 3. 首个满足条件的 Event 执行其 actions(若 keepEvaluating=false,跳过后续)
 * 4. 任一 action 返回 false(Action.Complete)→ 退出
 * 5. 所有事件被 disabled → 退出
 *
 * 线程模型:同步阻塞调用方线程(与 BotService 单线程模型一致)。
 * 中断检查在每个 Event 评估前调用 [AutomationBackend.isCancelled]。
 */
class ScenarioExecutor(
    private val backend: AutomationBackend,
    private val initialState: ProcessingState = ProcessingState(),
) {
    private val conditionsVerifier = ConditionsVerifier(initialState)
    private val actionExecutor = ActionExecutor()

    /** 执行结果。 */
    data class Result(
        val completedNormally: Boolean,
        val cancelled: Boolean = false,
        val timedOut: Boolean = false,
        val eventsProcessed: Int = 0,
        val errorMessage: String? = null,
    ) {
        /** 是否成功结束(无论是正常完成、取消还是超时,只要没抛异常即为 true)。 */
        val finished: Boolean get() = completedNormally || cancelled || timedOut
    }

    /**
     * 同步执行 [scenario],阻塞直到完成、取消或超时。
     */
    fun run(scenario: Scenario): Result {
        val state = initialState
        // 初始化事件启用状态
        state.initEvents(scenario.events.map { it.name to it.enabledOnStart })

        val startTimeMs = System.currentTimeMillis()
        val maxDurationMs = if (scenario.maxDurationMinutes > 0) scenario.maxDurationMinutes * 60_000L else Long.MAX_VALUE
        var eventsProcessed = 0

        try {
            while (true) {
                // 1. 取消检查
                if (backend.isCancelled()) {
                    return Result(completedNormally = false, cancelled = true, eventsProcessed = eventsProcessed)
                }

                // 2. 超时检查
                if (System.currentTimeMillis() - startTimeMs > maxDurationMs) {
                    return Result(completedNormally = false, timedOut = true, eventsProcessed = eventsProcessed)
                }

                // 3. 所有事件被禁用 → 完成
                if (state.allEventsDisabled()) {
                    return Result(completedNormally = true, eventsProcessed = eventsProcessed)
                }

                // 4. 评估事件:先 Trigger(廉价),后 Image(昂贵)
                val (triggerEvents, imageEvents) = scenario.events.partition { it is Event.Trigger }
                val sortedImageEvents = imageEvents
                    .filterIsInstance<Event.Image>()
                    .sortedBy { it.priority }

                var actionExecuted = false
                var shouldContinue = true

                // 4a. 评估 Trigger events
                for (event in triggerEvents) {
                    if (!state.isEventEnabled(event.name)) continue
                    if (backend.isCancelled()) {
                        return Result(completedNormally = false, cancelled = true, eventsProcessed = eventsProcessed)
                    }

                    if (conditionsVerifier.verify(event.conditions, event.conditionOperator, backend)) {
                        eventsProcessed++
                        for (action in event.actions) {
                            shouldContinue = actionExecutor.execute(action, backend, state)
                            if (!shouldContinue) break
                        }
                        if (!shouldContinue) {
                            return Result(completedNormally = true, eventsProcessed = eventsProcessed)
                        }
                        actionExecuted = true
                        if (event !is Event.Image || !(event as Event.Image).keepEvaluating) break
                    }
                }

                // 4b. 评估 Image events
                if (!actionExecuted || triggerEvents.none { state.isEventEnabled(it.name) }) {
                    for (event in sortedImageEvents) {
                        if (!state.isEventEnabled(event.name)) continue
                        if (backend.isCancelled()) {
                            return Result(completedNormally = false, cancelled = true, eventsProcessed = eventsProcessed)
                        }

                        if (conditionsVerifier.verify(event.conditions, event.conditionOperator, backend)) {
                            eventsProcessed++
                            for (action in event.actions) {
                                shouldContinue = actionExecutor.execute(action, backend, state)
                                if (!shouldContinue) break
                            }
                            if (!shouldContinue) {
                                return Result(completedNormally = true, eventsProcessed = eventsProcessed)
                            }
                            if (!event.keepEvaluating) break
                        }
                    }
                }

                // 5. 避免忙循环:短暂让出 CPU(默认 10ms,可由调用方覆盖)
                Thread.sleep(10)
            }
        } catch (e: InterruptedException) {
            return Result(completedNormally = false, cancelled = true, eventsProcessed = eventsProcessed)
        } catch (e: Exception) {
            return Result(completedNormally = false, eventsProcessed = eventsProcessed, errorMessage = e.message)
        }
    }
}
```

- [ ] **Step 7.4: 运行测试验证通过**

```bash
cd /Users/esc/Documents/android-cv-automation-library && ./gradlew :workflow:testDebugUnitTest --tests "com.steve1316.automation_library.workflow.runtime.ScenarioExecutorTest"
```

Expected: BUILD SUCCESSFUL, 4 tests passed

- [ ] **Step 7.5: 提交**

```bash
cd /Users/esc/Documents/android-cv-automation-library
git add workflow/src/main/java/com/steve1316/automation_library/workflow/runtime/ScenarioExecutor.kt \
        workflow/src/test/java/com/steve1316/automation_library/workflow/runtime/ScenarioExecutorTest.kt
git commit -m "feat(workflow): implement ScenarioExecutor main loop with timeout and cancellation"
```

---

## Task 8: 实现 DSL 构建器(Kotlin 友好 API)

**目标**: 提供 `workflow { }` DSL,让开发者用类型安全的方式定义 Scenario。

**Files:**
- Create: `workflow/src/main/java/com/steve1316/automation_library/workflow/dsl/WorkflowDsl.kt`
- Test: `workflow/src/test/java/com/steve1316/automation_library/workflow/dsl/WorkflowDslTest.kt`

- [ ] **Step 8.1: 先写失败测试**

```kotlin
package com.steve1316.automation_library.workflow.dsl

import com.steve1316.automation_library.workflow.model.Action
import com.steve1316.automation_library.workflow.model.Condition
import com.steve1316.automation_library.workflow.model.ConditionOperator
import com.steve1316.automation_library.workflow.model.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowDslTest {

    @Test
    fun `scenario dsl builds scenario with events`() {
        val scenario = scenario(id = "test", name = "Test") {
            imageEvent(id = "e1", name = "click-btn") {
                conditionOperator = ConditionOperator.AND
                condition { imageAppears("button", confidence = 0.9) }
                action { tap(100.0, 200.0) }
                action { complete() }
            }
        }

        assertEquals("test", scenario.id)
        assertEquals(1, scenario.events.size)

        val event = scenario.events[0]
        assertTrue(event is Event.Image)
        assertEquals("e1", event.id)
        assertEquals(1, event.conditions.size)
        assertTrue(event.conditions[0] is Condition.ImageAppears)
        assertEquals(0.9, (event.conditions[0] as Condition.ImageAppears).confidence, 0.001)

        assertEquals(2, event.actions.size)
        assertTrue(event.actions[0] is Action.Tap)
        assertTrue(event.actions[1] is Action.Complete)
    }

    @Test
    fun `dsl supports trigger events`() {
        val scenario = scenario(id = "s", name = "n") {
            triggerEvent(id = "t1", name = "timer") {
                condition { timerReached(5000L) }
                action { wait(0.5) }
            }
        }

        assertEquals(1, scenario.events.size)
        assertTrue(scenario.events[0] is Event.Trigger)
    }

    @Test
    fun `dsl supports or conditions`() {
        val scenario = scenario(id = "s", name = "n") {
            imageEvent(id = "e1", name = "test") {
                conditionOperator = ConditionOperator.OR
                condition { counterReached("c1", 10) }
                condition { imageAppears("rare") }
                action { tap(0.0, 0.0) }
            }
        }

        assertEquals(ConditionOperator.OR, scenario.events[0].conditionOperator)
        assertEquals(2, scenario.events[0].conditions.size)
    }
}
```

- [ ] **Step 8.2: 运行测试验证失败**

```bash
cd /Users/esc/Documents/android-cv-automation-library && ./gradlew :workflow:testDebugUnitTest --tests "com.steve1316.automation_library.workflow.dsl.WorkflowDslTest"
```

Expected: FAIL with `Unresolved reference: scenario`

- [ ] **Step 8.3: 实现 WorkflowDsl**

```kotlin
package com.steve1316.automation_library.workflow.dsl

import com.steve1316.automation_library.workflow.model.Action
import com.steve1316.automation_library.workflow.model.Condition
import com.steve1316.automation_library.workflow.model.ConditionOperator
import com.steve1316.automation_library.workflow.model.Event
import com.steve1316.automation_library.workflow.model.Scenario

/**
 * DSL 标记:防止嵌套作用域污染(避免外层 builder 的方法在内层被误调用)。
 */
@DslMarker
annotation class WorkflowDslMarker

/**
 * Scenario DSL 入口。
 *
 * 示例:
 * ```kotlin
 * val s = scenario(id = "scan_artifacts", name = "扫圣遗物") {
 *     imageEvent(id = "nav", name = "navigate") {
 *         condition { imageAppears("tab_artifacts") }
 *         action { tap(500.0, 200.0) }
 *     }
 * }
 * ```
 */
@WorkflowDslMarker
class ScenarioBuilder(private val id: String, private val name: String) {
    private val events = mutableListOf<Event>()
    private var maxDurationMinutes: Int = 0

    fun maxDuration(minutes: Int) {
        maxDurationMinutes = minutes
    }

    fun imageEvent(
        id: String,
        name: String,
        priority: Int = 0,
        keepEvaluating: Boolean = false,
        enabledOnStart: Boolean = true,
        block: ImageEventBuilder.() -> Unit,
    ) {
        events += ImageEventBuilder(id, name, priority, keepEvaluating, enabledOnStart).apply(block).build()
    }

    fun triggerEvent(
        id: String,
        name: String,
        enabledOnStart: Boolean = true,
        block: TriggerEventBuilder.() -> Unit,
    ) {
        events += TriggerEventBuilder(id, name, enabledOnStart).apply(block).build()
    }

    fun build(): Scenario = Scenario(id = id, name = name, events = events.toList(), maxDurationMinutes = maxDurationMinutes)
}

@WorkflowDslMarker
abstract class EventBuilder(protected val id: String, protected val name: String) {
    var conditionOperator: ConditionOperator = ConditionOperator.AND
    var enabledOnStart: Boolean = true

    protected val conditions = mutableListOf<Condition>()
    protected val actions = mutableListOf<Action>()

    fun condition(block: ConditionBuilder.() -> Condition) {
        conditions += ConditionBuilder().block()
    }

    fun action(block: ActionBuilder.() -> Action) {
        actions += ActionBuilder().block()
    }
}

@WorkflowDslMarker
class ImageEventBuilder(
    id: String,
    name: String,
    private val priority: Int = 0,
    private val keepEvaluating: Boolean = false,
    enabledOnStart: Boolean,
) : EventBuilder(id, name) {
    init { this.enabledOnStart = enabledOnStart }

    fun build(): Event.Image = Event.Image(
        id = id,
        name = name,
        conditionOperator = conditionOperator,
        conditions = conditions.toList(),
        actions = actions.toList(),
        enabledOnStart = enabledOnStart,
        priority = priority,
        keepEvaluating = keepEvaluating,
    )
}

@WorkflowDslMarker
class TriggerEventBuilder(
    id: String,
    name: String,
    enabledOnStart: Boolean,
) : EventBuilder(id, name) {
    init { this.enabledOnStart = enabledOnStart }

    fun build(): Event.Trigger = Event.Trigger(
        id = id,
        name = name,
        conditionOperator = conditionOperator,
        conditions = conditions.toList(),
        actions = actions.toList(),
        enabledOnStart = enabledOnStart,
    )
}

@WorkflowDslMarker
class ConditionBuilder {
    fun imageAppears(
        templateName: String,
        confidence: Double = 0.8,
        region: List<Int> = listOf(0, 0, 0, 0),
        shouldBeDetected: Boolean = true,
    ): Condition = Condition.ImageAppears(templateName, confidence, region, shouldBeDetected)

    fun textMatches(text: String, region: List<Int> = listOf(0, 0, 0, 0), similarity: Double = 0.8): Condition =
        Condition.TextMatches(text, region, similarity)

    fun counterReached(
        counterName: String,
        targetValue: Long,
        comparison: Condition.CounterReached.Comparison = Condition.CounterReached.Comparison.GREATER_OR_EQUAL,
    ): Condition = Condition.CounterReached(counterName, targetValue, comparison)

    fun timerReached(durationMs: Long, restartWhenReached: Boolean = false): Condition =
        Condition.TimerReached(durationMs, restartWhenReached)

    fun custom(id: String): Condition = Condition.Custom(id)
}

@WorkflowDslMarker
class ActionBuilder {
    fun tap(x: Double, y: Double, imageName: String? = null): Action = Action.Tap(x, y, imageName)
    fun longPress(x: Double, y: Double, imageName: String? = null, durationMs: Long = 1000): Action =
        Action.LongPress(x, y, imageName, durationMs)
    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 500): Action =
        Action.Swipe(startX, startY, endX, endY, durationMs)
    fun scroll(scrollDown: Boolean = true, durationMs: Long = 500): Action = Action.Scroll(scrollDown, durationMs)
    fun wait(seconds: Double): Action = Action.Wait(seconds)
    fun changeCounter(name: String, delta: Long): Action = Action.ChangeCounter(name, delta)
    fun toggleEvent(eventName: String, enabled: Boolean): Action = Action.ToggleEvent(eventName, enabled)
    fun complete(): Action = Action.Complete
    fun custom(id: String): Action = Action.Custom(id)
}

/** DSL 顶级入口。 */
fun scenario(id: String, name: String, block: ScenarioBuilder.() -> Unit): Scenario =
    ScenarioBuilder(id, name).apply(block).build()
```

- [ ] **Step 8.4: 运行测试验证通过**

```bash
cd /Users/esc/Documents/android-cv-automation-library && ./gradlew :workflow:testDebugUnitTest --tests "com.steve1316.automation_library.workflow.dsl.WorkflowDslTest"
```

Expected: BUILD SUCCESSFUL, 3 tests passed

- [ ] **Step 8.5: 提交**

```bash
cd /Users/esc/Documents/android-cv-automation-library
git add workflow/src/main/java/com/steve1316/automation_library/workflow/dsl/ \
        workflow/src/test/java/com/steve1316/automation_library/workflow/dsl/
git commit -m "feat(workflow): add Kotlin DSL builder for scenario definition"
```

---

## Task 9: 实现 JSON 序列化(可选持久化)

**目标**: Scenario 可序列化为 JSON 文件,支持热更新。

**Files:**
- Create: `workflow/src/main/java/com/steve1316/automation_library/workflow/model/JsonSerializers.kt`
- Test: `workflow/src/test/java/com/steve1316/automation_library/workflow/model/ScenarioSerializationTest.kt`

- [ ] **Step 9.1: 先写失败测试**

```kotlin
package com.steve1316.automation_library.workflow.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenarioSerializationTest {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; classDiscriminator = "type" }

    @Test
    fun `scenario with image event round trips through json`() {
        val original = Scenario(
            id = "scan",
            name = "Test Scan",
            events = listOf(
                Event.Image(
                    id = "e1",
                    name = "find-button",
                    conditionOperator = ConditionOperator.AND,
                    conditions = listOf(
                        Condition.ImageAppears("btn", confidence = 0.9),
                        Condition.CounterReached("clicks", 5),
                    ),
                    actions = listOf(
                        Action.Tap(100.0, 200.0, imageName = "btn"),
                        Action.Complete,
                    ),
                    priority = 1,
                    keepEvaluating = false,
                ),
            ),
            maxDurationMinutes = 30,
        )

        val jsonString = json.encodeToString(Scenario.serializer(), original)
        val restored = json.decodeFromString(Scenario.serializer(), jsonString)

        assertEquals(original.id, restored.id)
        assertEquals(original.name, restored.name)
        assertEquals(original.maxDurationMinutes, restored.maxDurationMinutes)
        assertEquals(1, restored.events.size)

        val event = restored.events[0] as Event.Image
        assertEquals("e1", event.id)
        assertEquals(1, event.priority)
        assertEquals(2, event.conditions.size)
        assertTrue(event.conditions[0] is Condition.ImageAppears)
        assertTrue(event.conditions[1] is Condition.CounterReached)
        assertEquals(2, event.actions.size)
        assertTrue(event.actions[0] is Action.Tap)
        assertTrue(event.actions[1] is Action.Complete)
    }

    @Test
    fun `scenario with mixed trigger and image events round trips`() {
        val original = Scenario(
            id = "mixed",
            name = "Mixed",
            events = listOf(
                Event.Trigger(
                    id = "t1",
                    name = "timer",
                    conditions = listOf(Condition.TimerReached(5000L, restartWhenReached = true)),
                    actions = listOf(Action.Wait(0.5)),
                ),
                Event.Image(
                    id = "i1",
                    name = "image",
                    conditions = listOf(Condition.TextMatches("hello")),
                    actions = listOf(Action.Scroll(scrollDown = true)),
                ),
            ),
        )

        val jsonString = json.encodeToString(Scenario.serializer(), original)
        val restored = json.decodeFromString(Scenario.serializer(), jsonString)

        assertEquals(2, restored.events.size)
        assertTrue(restored.events[0] is Event.Trigger)
        assertTrue(restored.events[1] is Event.Image)
    }
}
```

- [ ] **Step 9.2: 运行测试验证失败**

```bash
cd /Users/esc/Documents/android-cv-automation-library && ./gradlew :workflow:testDebugUnitTest --tests "com.steve1316.automation_library.workflow.model.ScenarioSerializationTest"
```

Expected: 可能 PASS(sealed class 已经有 @Serializable + @SerialName),也可能 FAIL(取决于 kotlinx.serialization polymorphic 配置)。若 PASS,跳过 Step 9.3。

- [ ] **Step 9.3: 实现 JsonSerializers.kt(如有需要)**

```kotlin
package com.steve1316.automation_library.workflow.model

import kotlinx.serialization.json.Json

/**
 * 预配置的 [Json] 实例,用于 Scenario 序列化/反序列化。
 *
 * - [prettyPrint]: 输出可读的 JSON(便于人工编辑流程文件)
 * - [ignoreUnknownKeys]: 容忍字段缺失(向前兼容)
 * - [classDiscriminator]: 使用 "type" 字段区分 sealed class 子类
 */
val ScenarioJson: Json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "type"
}

/** 将 [Scenario] 序列化为 JSON 字符串。 */
fun Scenario.toJson(): String = ScenarioJson.encodeToString(Scenario.serializer(), this)

/** 从 JSON 字符串反序列化 [Scenario]。 */
fun scenarioFromJson(json: String): Scenario = ScenarioJson.decodeFromString(Scenario.serializer(), json)
```

- [ ] **Step 9.4: 运行测试验证通过**

```bash
cd /Users/esc/Documents/android-cv-automation-library && ./gradlew :workflow:testDebugUnitTest --tests "com.steve1316.automation_library.workflow.model.ScenarioSerializationTest"
```

Expected: BUILD SUCCESSFUL, 2 tests passed

- [ ] **Step 9.5: 提交**

```bash
cd /Users/esc/Documents/android-cv-automation-library
git add workflow/src/main/java/com/steve1316/automation_library/workflow/model/JsonSerializers.kt \
        workflow/src/test/java/com/steve1316/automation_library/workflow/model/ScenarioSerializationTest.kt
git commit -m "feat(workflow): add JSON serialization for Scenario persistence"
```

---

## Task 10: 全量构建验证 + 文档更新

**目标**: 确认整个项目编译通过,所有测试通过,记录学习。

**Files:**
- Modify: `.learnings/LEARNINGS.md`
- Run: `./gradlew build`

- [ ] **Step 10.1: 运行 workflow 模块全部测试**

```bash
cd /Users/esc/Documents/android-cv-automation-library && ./gradlew :workflow:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL, 共 25 个测试通过(6+6+8+4+3+2 ≈ 29,实际以计数为准)

- [ ] **Step 10.2: 运行全项目构建**

```bash
cd /Users/esc/Documents/android-cv-automation-library && ./gradlew build
```

Expected: BUILD SUCCESSFUL,确认 app AAR 和 workflow AAR 都能正常打包

- [ ] **Step 10.3: 确认 workflow AAR 产出**

```bash
ls -lh /Users/esc/Documents/android-cv-automation-library/workflow/build/outputs/aar/
```

Expected: 看到 `workflow-debug.aar`,体积应在 50-200KB 之间

- [ ] **Step 10.4: 更新 LEARNINGS.md**

在 `/Users/esc/Documents/android-cv-automation-library/.learnings/LEARNINGS.md` 末尾追加:

```markdown
## [LRN-20260724-016] best_practice

**Logged**: 2026-07-24T23:30:00+08:00
**Priority**: high
**Status**: resolved
**Area**: backend

### Summary
新增独立 `workflow` Gradle 模块,实现 ECA(事件-条件-动作)编排引擎

### Details
**架构决策**:
- 编排层不打包进主 AAR(`:app`),作为独立 Gradle 模块 `:workflow` 按需引入
- 三层职责分离:原子动作层(:app) → 编排层(:workflow) → 业务层(宿主)
- 编排层通过 `AutomationBackend` 接口与原子动作层解耦,宿主 App 实现接口注入

**ECA 模型**(借鉴 Smart-AutoClicker 简化版):
- `Scenario` = `List<Event>`
- `Event` 分两类:`Image`(需图像分析,代价高) + `Trigger`(基于计数器/计时器,代价低)
- `Condition` sealed class:ImageAppears / TextMatches / CounterReached / TimerReached / Custom
- `Action` sealed class:Tap / LongPress / Swipe / Scroll / Wait / ChangeCounter / ToggleEvent / Complete / Custom

**关键设计**:
1. **两阶段求值**:先评估 Trigger(廉价)后评估 Image(昂贵),Trigger 命中可跳过 Image
2. **短路求值**:AND 遇 false 立即返回,OR 遇 true 立即返回
3. **Strategy 模式**:ActionExecutor 多态分发,新增 Action 类型不影响现有代码
4. **单线程执行**:与 BotService 模型一致,通过 `AutomationBackend.isCancelled()` 协作式中断
5. **JSON 持久化**:kotlinx.serialization,Scenario 可序列化为文件,支持热更新

**测试覆盖**:29 个单元测试,覆盖 ProcessingState/ConditionsVerifier/ActionExecutor/ScenarioExecutor/DSL/JSON 序列化

**新增依赖**:
- kotlinx-serialization-json 1.6.3(~150KB)
- kotlinx-coroutines-android 1.8.0(~80KB)

**AAR 体积**:workflow-debug.aar ~150KB(独立于主 AAR 的 6.5MB)

### Suggested Action
1. 在 JitPack 配置中确认 `:workflow` 模块发布 artifactId = `automation_library-workflow`
2. 未来在宿主项目(如 genshin-inventory-scanner-v2)中按 `com.github.shadyrispy:automation_library-workflow:<version>` 引入
3. 后续可考虑增加可视化调试器(Scenario AST 转 Mermaid 图)

### Metadata
- Source: research
- Related Files: workflow/, settings.gradle.kts, gradle/libs.versions.toml
- Tags: workflow, eca, orchestration, dsl, serialization
- See Also: LRN-20260724-015

---
```

- [ ] **Step 10.5: 最终提交**

```bash
cd /Users/esc/Documents/android-cv-automation-library
git add .learnings/LEARNINGS.md
git commit -m "docs(learnings): record workflow module architecture decision (LRN-20260724-016)"
```

---

## 自我审查清单(实施时核对)

### 1. 任务覆盖检查

| 需求 | 对应 Task |
|---|---|
| ECA 数据模型 | Task 2 |
| 与原子动作解耦 | Task 3 (AutomationBackend) |
| 运行时状态管理 | Task 4 (ProcessingState) |
| 条件求值 | Task 5 (ConditionsVerifier) |
| 动作执行 | Task 6 (ActionExecutor) |
| 主执行循环 | Task 7 (ScenarioExecutor) |
| 类型安全 DSL | Task 8 |
| JSON 持久化 | Task 9 |
| 测试覆盖 | Task 4/5/6/7/8/9 各自独立测试 |
| 模块化构建 | Task 1 |
| 文档 | Task 10 |

### 2. 类型一致性检查

- `Condition.CounterReached.Comparison` 在 Task 2 定义,Task 5 映射到 `ProcessingState.Comparison`,Task 8 在 `counterReached()` 中使用 `Condition.CounterReached.Comparison` - ✅ 一致
- `Action.Complete` 是 `data object`(Task 2),ActionExecutor 用 `is Action.Complete` 判断(Task 6)- ✅ 一致
- `Event.Image` / `Event.Trigger` 的字段名在 Task 2/4/7/8 中保持一致 - ✅ 已核对
- `AutomationBackend.findImage` 签名在 Task 3 定义,ConditionsVerifier 在 Task 5 调用 - ✅ 参数名/类型一致

### 3. Placeholder 检查

- 所有代码步骤都有完整代码 ✅
- 所有测试步骤都有完整测试代码 ✅
- 没有 "TBD"/"TODO"/"similar to" ✅

---

## 执行方式

**Plan complete and saved to `docs/superpowers/plans/2026-07-24-workflow-eca-module.md`**. 两种执行方式:

1. **Subagent-Driven(推荐)** - 每个 Task 派发 fresh subagent,任务间两阶段 review,快速迭代
2. **Inline Execution** - 当前 session 直接执行,带 checkpoint review

请选择执行方式。
