package com.steve1316.automation_library.workflow.logging

/**
 * 步骤级日志接口。默认实现为 no-op,宿主可注入真实实现(如 MessageLog)。
 *
 * 所有方法都是同步的,调用方在合适的线程调用。
 */
interface StepLogger {

    /** 记录 Event 评估开始。 */
    fun onEventEvaluated(eventName: String, satisfied: Boolean) {}

    /** 记录 Action 执行。 */
    fun onActionExecuted(action: String, success: Boolean) {}

    /** 记录普通信息。 */
    fun info(message: String) {}

    /** 记录警告。 */
    fun warn(message: String) {}

    /** 记录错误。 */
    fun error(message: String, throwable: Throwable? = null) {}

    /** 空实现。 */
    object Noop : StepLogger
}
