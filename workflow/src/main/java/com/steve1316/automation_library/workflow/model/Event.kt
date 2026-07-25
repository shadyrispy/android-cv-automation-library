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
 * ScenarioExecutor 按 [priority] 升序处理(数值小先处理),
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
     * ScenarioExecutor 会优先评估所有 Trigger 事件(廉价),再评估 Image 事件(昂贵)。
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
