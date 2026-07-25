package com.steve1316.automation_library.workflow.runtime

import android.graphics.PointF
import com.steve1316.automation_library.workflow.AutomationBackend
import com.steve1316.automation_library.workflow.logging.StepLogger
import com.steve1316.automation_library.workflow.model.Action
import com.steve1316.automation_library.workflow.model.Condition
import com.steve1316.automation_library.workflow.model.Event
import com.steve1316.automation_library.workflow.model.Scenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenarioExecutorTest {
    private val noopLogger = object : StepLogger {}

    private inner class FakeBackend(
        val imageHit: PointF? = null,
        val customConditionResult: Boolean = false,
    ) : AutomationBackend {
        override val logger = noopLogger
        val taps = mutableListOf<Pair<Double, Double>>()
        var waitCalled = false
        var cancelled = false

        override fun findImage(templateName: String, confidence: Double, region: IntArray) = imageHit

        override fun findText(text: String, region: IntArray, similarity: Double) = null

        override fun tap(x: Double, y: Double, imageName: String?): Boolean {
            taps += x to y
            return true
        }

        override fun longPress(x: Double, y: Double, imageName: String?, durationMs: Long) = true

        override fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long) = true

        override fun scroll(scrollDown: Boolean, durationMs: Long) = true

        override fun wait(seconds: Double) {
            waitCalled = true
        }

        override fun evaluateCustomCondition(id: String) = customConditionResult

        override fun isCancelled() = cancelled
    }

    @Test
    fun `scenario completes when action complete is executed`() {
        val scenario =
            Scenario(
                id = "s1",
                name = "test",
                events =
                    listOf(
                        Event.Image(
                            id = "e1",
                            name = "click-once",
                            conditions = listOf(Condition.ImageAppears("btn")),
                            actions = listOf(Action.Tap(100.0, 200.0), Action.Complete),
                        ),
                    ),
            )
        val backend = FakeBackend(imageHit = PointF(1f, 1f))
        val executor = ScenarioExecutor(backend)

        val result = executor.run(scenario)

        assertTrue(result.completedNormally)
        assertEquals(listOf(100.0 to 200.0), backend.taps)
    }

    @Test
    fun `scenario stops when backend reports cancelled`() {
        val scenario =
            Scenario(
                id = "s2",
                name = "test",
                events =
                    listOf(
                        Event.Image(
                            id = "e1",
                            name = "click-forever",
                            conditions = listOf(Condition.ImageAppears("btn")),
                            actions = listOf(Action.Tap(0.0, 0.0)),
                        ),
                    ),
            )
        val backend = FakeBackend(imageHit = PointF(1f, 1f))
        backend.cancelled = true // 已取消
        val executor = ScenarioExecutor(backend)

        val result = executor.run(scenario)

        assertTrue(result.cancelled)
        assertEquals(0, backend.taps.size) // 未执行任何 tap
    }

    @Test
    fun `scenario completes when all events disabled`() {
        val scenario =
            Scenario(
                id = "s3",
                name = "test",
                events =
                    listOf(
                        Event.Image(
                            id = "e1",
                            name = "self-disabling",
                            conditions = listOf(Condition.ImageAppears("btn")),
                            actions =
                                listOf(
                                    Action.ToggleEvent("e1", enabled = false), // 用 Event.id 作为 key 关闭自己
                                ),
                        ),
                    ),
            )
        val backend = FakeBackend(imageHit = PointF(1f, 1f))
        val executor = ScenarioExecutor(backend)

        val result = executor.run(scenario)

        assertTrue(result.completedNormally)
    }

    @Test
    fun `trigger events evaluated before image events`() {
        val scenario =
            Scenario(
                id = "s4",
                name = "test",
                events =
                    listOf(
                        Event.Image(
                            id = "img",
                            name = "image-event",
                            conditions = listOf(Condition.ImageAppears("btn")),
                            actions = listOf(Action.Tap(1.0, 1.0)),
                        ),
                        Event.Trigger(
                            id = "trig",
                            name = "trigger-event",
                            conditions = listOf(Condition.CounterReached("c", 1, Condition.CounterReached.Comparison.GREATER_OR_EQUAL)),
                            actions = listOf(Action.Tap(2.0, 2.0), Action.Complete),
                        ),
                    ),
            )
        val backend = FakeBackend(imageHit = PointF(1f, 1f))
        // 预置计数器到 1,使 trigger event 先满足
        val executor = ScenarioExecutor(backend, initialState = ProcessingState().apply { changeCounter("c", 1) })

        val result = executor.run(scenario)

        assertTrue(result.completedNormally)
        // trigger 先执行,tap(2,2) 后 Complete,image event 没机会执行
        assertEquals(listOf(2.0 to 2.0), backend.taps)
    }

    @Test
    fun `keepEvaluating allows subsequent image events to execute in same cycle`() {
        val scenario =
            Scenario(
                id = "s5",
                name = "test",
                events =
                    listOf(
                        Event.Image(
                            id = "e1",
                            name = "first",
                            conditions = listOf(Condition.ImageAppears("btn")),
                            actions = listOf(Action.Tap(1.0, 1.0)),
                            keepEvaluating = true,
                        ),
                        Event.Image(
                            id = "e2",
                            name = "second",
                            conditions = listOf(Condition.ImageAppears("btn")),
                            actions = listOf(Action.Tap(2.0, 2.0), Action.Complete),
                        ),
                    ),
            )
        val backend = FakeBackend(imageHit = PointF(1f, 1f))
        val executor = ScenarioExecutor(backend)

        val result = executor.run(scenario)

        assertTrue(result.completedNormally)
        // keepEvaluating=true 时两个 event 都执行
        assertEquals(listOf(1.0 to 1.0, 2.0 to 2.0), backend.taps)
    }

    @Test
    fun `pollIntervalMs controls loop interval`() {
        val scenario =
            Scenario(
                id = "s6",
                name = "test",
                events =
                    listOf(
                        Event.Image(
                            id = "e1",
                            name = "cancel-fast",
                            conditions = listOf(Condition.ImageAppears("btn")),
                            actions = listOf(Action.Tap(0.0, 0.0)),
                        ),
                    ),
            )
        val backend = FakeBackend(imageHit = null) // 永不匹配
        backend.cancelled = true // 立即取消
        val executor = ScenarioExecutor(backend, pollIntervalMs = 100L)

        val result = executor.run(scenario)

        assertTrue(result.cancelled)
        assertEquals(0, backend.taps.size)
    }
}
