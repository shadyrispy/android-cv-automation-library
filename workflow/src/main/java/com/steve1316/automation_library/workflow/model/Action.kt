package com.steve1316.automation_library.workflow.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * ECA 中的 Action:条件满足时执行的动作单元。
 *
 * 所有子类型为 [Serializable]。执行由
 * ActionExecutor 多态分发到 AutomationBackend。
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
        val eventId: String,
        val enabled: Boolean,
    ) : Action()

    /** 完成整个 Scenario 执行。 */
    @Serializable
    @SerialName("complete")
    data object Complete : Action()

    /** 自定义动作:由宿主 App 通过 AutomationBackend.executeCustomAction 处理。 */
    @Serializable
    @SerialName("custom")
    data class Custom(
        val id: String,
    ) : Action()
}
