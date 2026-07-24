package com.steve1316.automation_library.workflow.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * ECA 中的 Condition:决定 Event 是否触发的判断单元。
 *
 * 所有子类型为 [Serializable],可序列化为 JSON 用于持久化。
 * 子类型按"求值代价"由低到高排列,便于 [ConditionsVerifier] 做短路优化。
 */
@Serializable
sealed class Condition {

    /**
     * 图像模板匹配条件:屏幕上是否出现指定模板。
     *
     * @property templateName 模板图名称(对应宿主 App assets 中的文件名,不含扩展名)
     * @property confidence 匹配置信度阈值,默认 0.8
     * @property region 搜索区域 [x, y, width, height],默认全屏
     * @property shouldBeDetected true=出现时满足;false=消失时满足
     */
    @Serializable
    @SerialName("image_appears")
    data class ImageAppears(
        val templateName: String,
        val confidence: Double = 0.8,
        val region: List<Int> = listOf(0, 0, 0, 0),
        val shouldBeDetected: Boolean = true,
    ) : Condition()

    /**
     * OCR 文本匹配条件:指定区域是否包含某文本。
     *
     * @property text 要匹配的文本(子串匹配,大小写不敏感)
     * @property region OCR 区域 [x, y, width, height]
     * @property similarity 文本相似度阈值(JaroWinkler),默认 0.8 容忍 OCR 噪声
     */
    @Serializable
    @SerialName("text_matches")
    data class TextMatches(
        val text: String,
        val region: List<Int> = listOf(0, 0, 0, 0),
        val similarity: Double = 0.8,
    ) : Condition()

    /**
     * 计数器条件:运行时计数器达到指定值。
     *
     * @property counterName 计数器名称(对应 ProcessingState 中的 key)
     * @property targetValue 目标值
     * @property comparison 比较方式
     */
    @Serializable
    @SerialName("counter_reached")
    data class CounterReached(
        val counterName: String,
        val targetValue: Long,
        val comparison: Comparison = Comparison.GREATER_OR_EQUAL,
    ) : Condition() {

        @Serializable
        enum class Comparison {
            EQUAL,
            GREATER_OR_EQUAL,
            LESS_OR_EQUAL,
        }
    }

    /**
     * 定时器条件:从激活开始计时,达到指定时长后满足。
     *
     * @property durationMs 时长(毫秒)
     * @property restartWhenReached 达到后是否重新计时(用于周期性触发)
     */
    @Serializable
    @SerialName("timer_reached")
    data class TimerReached(
        val durationMs: Long,
        val restartWhenReached: Boolean = false,
    ) : Condition()

    /**
     * 自定义条件:由宿主 App 提供的 lambda 求值。
     *
     * 注意:[Serializable] 标注但 lambda 不可序列化,
     * 序列化时会被丢弃(默认返回 false);仅用于代码内定义,不用于 JSON 持久化。
     *
     * @property id 自定义条件标识符(用于在宿主 App 中查找对应的求值函数)
     */
    @Serializable
    @SerialName("custom")
    data class Custom(
        val id: String,
    ) : Condition()
}
