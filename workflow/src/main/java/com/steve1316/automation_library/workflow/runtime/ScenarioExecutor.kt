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
 * 4. 任一 action 返回 false(Action.Complete 或 backend 返回 false)→ 退出
 * 5. 所有事件被 disabled → 退出
 *
 * 线程模型:同步阻塞调用方线程(与 BotService 单线程模型一致)。
 * 中断检查在每个 Event 评估前调用 [AutomationBackend.isCancelled]。
 *
 * @param backend 原子动作桥接
 * @param initialState 运行时状态(可预置计数器/计时器)
 * @param pollIntervalMs 每轮循环之间的休眠时间(毫秒),避免忙循环占用 CPU
 */
class ScenarioExecutor(
    private val backend: AutomationBackend,
    private val initialState: ProcessingState = ProcessingState(),
    private val pollIntervalMs: Long = 10L,
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
        /** 是否已结束(正常完成、取消、超时或异常退出,只要 run() 返回即为 true)。 */
        val finished: Boolean get() = completedNormally || cancelled || timedOut || errorMessage != null
    }

    /**
     * 同步执行 [scenario],阻塞直到完成、取消或超时。
     */
    fun run(scenario: Scenario): Result {
        val state = initialState
        val logger = backend.logger

        // 初始化事件启用状态(用 Event.id 作为唯一 key,而非 name)
        state.initEvents(scenario.events.map { it.id to it.enabledOnStart })
        logger.info("Scenario '${scenario.name}' started with ${scenario.events.size} events")

        val startTimeMs = System.currentTimeMillis()
        val maxDurationMs = if (scenario.maxDurationMinutes > 0) scenario.maxDurationMinutes * 60_000L else Long.MAX_VALUE
        var eventsProcessed = 0

        try {
            while (true) {
                // 1. 取消检查
                if (backend.isCancelled()) {
                    logger.info("Scenario cancelled by backend")
                    return Result(completedNormally = false, cancelled = true, eventsProcessed = eventsProcessed)
                }

                // 2. 超时检查
                if (System.currentTimeMillis() - startTimeMs > maxDurationMs) {
                    logger.info("Scenario timed out after ${scenario.maxDurationMinutes} minutes")
                    return Result(completedNormally = false, timedOut = true, eventsProcessed = eventsProcessed)
                }

                // 3. 所有事件被禁用 → 完成
                if (state.allEventsDisabled()) {
                    logger.info("Scenario completed: all events disabled")
                    return Result(completedNormally = true, eventsProcessed = eventsProcessed)
                }

                // 4. 评估事件:先 Trigger(廉价),后 Image(昂贵)
                val (triggerEvents, imageEvents) = scenario.events.partition { it is Event.Trigger }
                val sortedImageEvents =
                    imageEvents
                        .filterIsInstance<Event.Image>()
                        .sortedBy { it.priority }

                var actionExecuted = false
                var shouldContinue = true

                // 4a. 评估 Trigger events
                for (event in triggerEvents) {
                    if (!state.isEventEnabled(event.id)) continue
                    if (backend.isCancelled()) {
                        return Result(completedNormally = false, cancelled = true, eventsProcessed = eventsProcessed)
                    }

                    val satisfied =
                        conditionsVerifier.verify(
                            event.conditions,
                            event.conditionOperator,
                            backend,
                            contextKey = event.id,
                        )
                    logger.onEventEvaluated(event.name, satisfied)
                    if (satisfied) {
                        eventsProcessed++
                        for (action in event.actions) {
                            shouldContinue = actionExecutor.execute(action, backend, state)
                            logger.onActionExecuted(action.toString(), shouldContinue)
                            if (!shouldContinue) break
                        }
                        if (!shouldContinue) {
                            logger.info("Scenario completed: action returned false")
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
                        if (!state.isEventEnabled(event.id)) continue
                        if (backend.isCancelled()) {
                            return Result(completedNormally = false, cancelled = true, eventsProcessed = eventsProcessed)
                        }

                        val satisfied =
                            conditionsVerifier.verify(
                                event.conditions,
                                event.conditionOperator,
                                backend,
                                contextKey = event.id,
                            )
                        logger.onEventEvaluated(event.name, satisfied)
                        if (satisfied) {
                            eventsProcessed++
                            for (action in event.actions) {
                                shouldContinue = actionExecutor.execute(action, backend, state)
                                logger.onActionExecuted(action.toString(), shouldContinue)
                                if (!shouldContinue) break
                            }
                            if (!shouldContinue) {
                                logger.info("Scenario completed: action returned false")
                                return Result(completedNormally = true, eventsProcessed = eventsProcessed)
                            }
                            if (!event.keepEvaluating) break
                        }
                    }
                }

                // 5. 避免忙循环:短暂让出 CPU
                Thread.sleep(pollIntervalMs)
            }
        } catch (e: InterruptedException) {
            logger.info("Scenario interrupted")
            return Result(completedNormally = false, cancelled = true, eventsProcessed = eventsProcessed)
        } catch (e: Exception) {
            logger.error("Scenario failed with exception", e)
            return Result(completedNormally = false, eventsProcessed = eventsProcessed, errorMessage = e.message)
        }
    }
}
