package com.steve1316.automation_library.workflow.runtime

import android.graphics.PointF
import com.steve1316.automation_library.workflow.AutomationBackend
import com.steve1316.automation_library.workflow.logging.StepLogger
import com.steve1316.automation_library.workflow.model.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionExecutorTest {

    private val noopLogger = object : StepLogger {}

    private inner class FakeBackend : AutomationBackend {
        override val logger = noopLogger
        var lastTap: Pair<Double, Double>? = null
        var lastSwipe: List<Float>? = null
        var lastScrollDown: Boolean? = null
        var lastWaitSeconds: Double? = null
        var counterChanges = mutableMapOf<String, Long>()
        var toggledEvents = mutableMapOf<String, Boolean>()
        var customActionsExecuted = mutableListOf<String>()
        var cancelled = false

        override fun findImage(templateName: String, confidence: Double, region: IntArray) = PointF(0f, 0f)
        override fun findText(text: String, region: IntArray, similarity: Double) = text
        override fun tap(x: Double, y: Double, imageName: String?): Boolean {
            lastTap = x to y
            return true
        }
        override fun longPress(x: Double, y: Double, imageName: String?, durationMs: Long) = true
        override fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long): Boolean {
            lastSwipe = listOf(startX, startY, endX, endY)
            return true
        }
        override fun scroll(scrollDown: Boolean, durationMs: Long): Boolean {
            lastScrollDown = scrollDown
            return true
        }
        override fun wait(seconds: Double) { lastWaitSeconds = seconds }
        override fun executeCustomAction(id: String): Boolean {
            customActionsExecuted.add(id)
            return true
        }
        override fun isCancelled() = cancelled
    }

    @Test
    fun `tap action calls backend tap`() {
        val backend = FakeBackend()
        val executor = ActionExecutor()
        executor.execute(Action.Tap(100.0, 200.0), backend, ProcessingState())
        assertEquals(100.0 to 200.0, backend.lastTap)
    }

    @Test
    fun `scroll action passes scrollDown flag`() {
        val backend = FakeBackend()
        val executor = ActionExecutor()
        executor.execute(Action.Scroll(scrollDown = false), backend, ProcessingState())
        assertEquals(false, backend.lastScrollDown)
    }

    @Test
    fun `wait action delegates to backend`() {
        val backend = FakeBackend()
        val executor = ActionExecutor()
        executor.execute(Action.Wait(2.5), backend, ProcessingState())
        assertEquals(2.5, backend.lastWaitSeconds)
    }

    @Test
    fun `change_counter updates processing state`() {
        val state = ProcessingState()
        val executor = ActionExecutor()
        executor.execute(Action.ChangeCounter("clicks", 3), FakeBackend(), state)
        assertEquals(3L, state.getCounter("clicks"))
    }

    @Test
    fun `toggle_event updates event enabled state`() {
        val state = ProcessingState()
        state.initEvents(listOf("e1" to true))
        val executor = ActionExecutor()
        executor.execute(Action.ToggleEvent("e1", enabled = false), FakeBackend(), state)
        assertFalse(state.isEventEnabled("e1"))
    }

    @Test
    fun `custom action delegates to backend`() {
        val backend = FakeBackend()
        val executor = ActionExecutor()
        executor.execute(Action.Custom("scan_row"), backend, ProcessingState())
        assertEquals(listOf("scan_row"), backend.customActionsExecuted)
    }

    @Test
    fun `complete action returns false to signal executor stop`() {
        val executor = ActionExecutor()
        val shouldContinue = executor.execute(Action.Complete, FakeBackend(), ProcessingState())
        assertFalse(shouldContinue)
    }

    @Test
    fun `normal action returns true to continue`() {
        val executor = ActionExecutor()
        val shouldContinue = executor.execute(Action.Tap(0.0, 0.0), FakeBackend(), ProcessingState())
        assertTrue(shouldContinue)
    }
}
