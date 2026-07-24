package com.steve1316.automation_library.workflow.model

import kotlinx.serialization.Serializable

/**
 * 一个完整的自动化场景:由多个 [Event] 组成。
 *
 * 执行模型(借鉴 Smart-AutoClicker 但简化):
 * - ScenarioExecutor 按事件优先级循环评估
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
