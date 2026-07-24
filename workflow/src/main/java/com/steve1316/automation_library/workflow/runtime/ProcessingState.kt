package com.steve1316.automation_library.workflow.runtime

import java.util.concurrent.ConcurrentHashMap

/**
 * 运行时状态:管理计数器、计时器、事件启用状态。
 *
 * 线程安全:所有可变状态用 [ConcurrentHashMap] 存储,ScenarioExecutor 单线程写,
 * UI 线程可并发读(用于进度展示)。
 *
 * @param nowMsProvider 当前时间戳提供者(毫秒),测试时可注入 fake clock
 */
class ProcessingState(
    private val nowMsProvider: () -> Long = System::currentTimeMillis,
) {

    enum class Comparison { EQUAL, GREATER_OR_EQUAL, LESS_OR_EQUAL }

    // ====== 计数器 ======
    private val counters = ConcurrentHashMap<String, Long>()

    /** 获取计数器当前值,不存在返回 0。 */
    fun getCounter(name: String): Long = counters[name] ?: 0L

    /** 增减计数器。 */
    fun changeCounter(name: String, delta: Long) {
        counters.compute(name) { _, v ->
            (v ?: 0L) + delta
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

    /** 启动一个计时器,以 [nowMsProvider] 当前值作为起始基线。 */
    fun startTimer(name: String, durationMs: Long, restartWhenReached: Boolean) {
        timers[name] = TimerState(nowMsProvider(), durationMs, restartWhenReached)
    }

    /** 判断计时器是否到达。默认用 [nowMsProvider] 取当前时间,测试可注入 fake clock。 */
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
