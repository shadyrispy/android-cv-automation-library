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
