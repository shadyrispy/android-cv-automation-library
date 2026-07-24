package com.steve1316.automation_library.workflow.model

import kotlinx.serialization.Serializable

/**
 * 条件之间的逻辑组合方式。
 * - [AND]: 所有条件都满足才为 true(短路:遇到 false 立即返回)
 * - [OR]: 任一条件满足即为 true(短路:遇到 true 立即返回)
 */
@Serializable
enum class ConditionOperator {
    AND,
    OR,
}
