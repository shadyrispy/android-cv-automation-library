package com.steve1316.automation_library.workflow.runtime

import com.steve1316.automation_library.workflow.AutomationBackend
import com.steve1316.automation_library.workflow.model.Event
import com.steve1316.automation_library.workflow.model.Scenario

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
                        // Trigger 事件命中后本轮不再评估后续 trigger
                        break
                    }
                }

                // 4b. 评估 Image events(仅当本轮没有 trigger action 执行)
                if (!actionExecuted) {
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

                // 5. 避免忙循环:短暂让出 CPU
                Thread.sleep(10)
            }
        } catch (e: InterruptedException) {
            return Result(completedNormally = false, cancelled = true, eventsProcessed = eventsProcessed)
        } catch (e: Exception) {
            return Result(completedNormally = false, eventsProcessed = eventsProcessed, errorMessage = e.message)
        }
    }
}
