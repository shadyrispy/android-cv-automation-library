package com.steve1316.automation_library.workflow

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
 * 所有方法都是 **synchronous**(同步)的,在 ScenarioExecutor 的执行线程上调用。
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
     * ScenarioExecutor 在每个 Event 评估前调用此方法。
     */
    fun isCancelled(): Boolean = false
}
