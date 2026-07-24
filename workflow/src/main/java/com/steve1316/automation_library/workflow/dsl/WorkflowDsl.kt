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
