package com.steve1316.automation_library.workflow.model

import kotlinx.serialization.json.Json

/**
 * 预配置的 [Json] 实例,用于 Scenario 序列化/反序列化。
 *
 * - [prettyPrint]: 输出可读的 JSON(便于人工编辑流程文件)
 * - [ignoreUnknownKeys]: 容忍字段缺失(向前兼容)
 * - [encodeDefaults]: 编码默认值(保证反序列化完整)
 */
val ScenarioJson: Json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "type"
}

/** 将 [Scenario] 序列化为 JSON 字符串。 */
fun Scenario.toJson(): String = ScenarioJson.encodeToString(Scenario.serializer(), this)

/** 从 JSON 字符串反序列化 [Scenario]。 */
fun scenarioFromJson(json: String): Scenario = ScenarioJson.decodeFromString(Scenario.serializer(), json)
