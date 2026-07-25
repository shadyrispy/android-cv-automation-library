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
 *
 * @param state 运行时状态
 */
class ConditionsVerifier(private val state: ProcessingState) {
    /**
     * 求值所有条件。
     *
     * @param conditions 条件列表
     * @param operator 组合方式
     * @param backend 原子动作桥接
     * @param contextKey 调用方传入的唯一标识(通常为 Event.id),用于隔离不同 Event 的 TimerReached 计时器
     * @return true=条件满足(Event 可触发),false=不满足
     */
    fun verify(
        conditions: List<Condition>,
        operator: ConditionOperator,
        backend: AutomationBackend,
        contextKey: String = "default",
    ): Boolean {
        if (conditions.isEmpty()) return true // 无条件视为满足

        for ((index, condition) in conditions.withIndex()) {
            // 取消检查:避免昂贵的 OCR/图像匹配在用户停止后继续执行
            if (backend.isCancelled()) return false

            val satisfied = evaluateOne(condition, backend, contextKey, index)
            when (operator) {
                ConditionOperator.AND -> if (!satisfied) return false // 短路:遇 false 立即返回
                ConditionOperator.OR -> if (satisfied) return true // 短路:遇 true 立即返回
            }
        }
        // 循环结束:AND 全 true → true;OR 全 false → false
        return operator == ConditionOperator.AND
    }

    private fun evaluateOne(
        condition: Condition,
        backend: AutomationBackend,
        contextKey: String,
        index: Int,
    ): Boolean {
        return when (condition) {
            is Condition.ImageAppears -> {
                val hit =
                    backend.findImage(
                        templateName = condition.templateName,
                        confidence = condition.confidence,
                        region = condition.region.toIntArray(),
                    )
                if (condition.shouldBeDetected) hit != null else hit == null
            }

            is Condition.TextMatches -> {
                val found =
                    backend.findText(
                        text = condition.text,
                        region = condition.region.toIntArray(),
                        similarity = condition.similarity,
                    )
                found != null
            }

            is Condition.CounterReached ->
                state.isCounterReached(
                    condition.counterName,
                    condition.targetValue,
                    when (condition.comparison) {
                        Condition.CounterReached.Comparison.EQUAL -> ProcessingState.Comparison.EQUAL
                        Condition.CounterReached.Comparison.GREATER_OR_EQUAL -> ProcessingState.Comparison.GREATER_OR_EQUAL
                        Condition.CounterReached.Comparison.LESS_OR_EQUAL -> ProcessingState.Comparison.LESS_OR_EQUAL
                    },
                )

            is Condition.TimerReached -> {
                // 用 contextKey + index 作为唯一 timer name,避免相同字段的 TimerReached 共享计时器
                val timerName = "${contextKey}_timer_$index"
                if (!state.timerExists(timerName)) {
                    state.startTimer(
                        timerName,
                        condition.durationMs,
                        condition.restartWhenReached,
                    )
                }
                state.isTimerReached(timerName)
            }

            is Condition.Custom -> backend.evaluateCustomCondition(condition.id)
        }
    }

    private fun List<Int>.toIntArray(): IntArray {
        require(size == 4) { "region must have 4 elements [x, y, w, h], got $size" }
        return IntArray(4) { this[it] }
    }
}
