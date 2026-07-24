package com.steve1316.automation_library.workflow.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingStateTest {

    @Test
    fun `counter starts at zero and increments by delta`() {
        val state = ProcessingState()
        assertEquals(0L, state.getCounter("clicks"))
        state.changeCounter("clicks", 3)
        assertEquals(3L, state.getCounter("clicks"))
        state.changeCounter("clicks", -1)
        assertEquals(2L, state.getCounter("clicks"))
    }

    @Test
    fun `counter reached returns true when value meets comparison`() {
        val state = ProcessingState()
        state.changeCounter("items", 5)

        assertTrue(state.isCounterReached("items", 5, ProcessingState.Comparison.EQUAL))
        assertTrue(state.isCounterReached("items", 5, ProcessingState.Comparison.GREATER_OR_EQUAL))
        assertTrue(state.isCounterReached("items", 10, ProcessingState.Comparison.LESS_OR_EQUAL))
        assertFalse(state.isCounterReached("items", 10, ProcessingState.Comparison.GREATER_OR_EQUAL))
    }

    @Test
    fun `timer starts fresh and reports reached after duration`() {
        val state = ProcessingState()
        state.startTimer("t1", durationMs = 100, restartWhenReached = false)

        // 未到时间
        assertFalse(state.isTimerReached("t1", nowMs = 50))
        // 到达时间
        assertTrue(state.isTimerReached("t1", nowMs = 100))
    }

    @Test
    fun `timer with restart resets start time after reached`() {
        val state = ProcessingState()
        state.startTimer("t2", durationMs = 100, restartWhenReached = true)

        // 100ms 时触发,重置 start = 100
        assertTrue(state.isTimerReached("t2", nowMs = 100))
        // 199ms 时未到(从 100 起算)
        assertFalse(state.isTimerReached("t2", nowMs = 199))
        // 200ms 时再次到
        assertTrue(state.isTimerReached("t2", nowMs = 200))
    }

    @Test
    fun `event enabled state defaults to enabledOnStart and can be toggled`() {
        val state = ProcessingState()
        state.initEvents(listOf("e1" to true, "e2" to false))

        assertTrue(state.isEventEnabled("e1"))
        assertFalse(state.isEventEnabled("e2"))

        state.setEventEnabled("e1", false)
        assertFalse(state.isEventEnabled("e1"))
    }

    @Test
    fun `all events disabled returns true when every event is off`() {
        val state = ProcessingState()
        state.initEvents(listOf("e1" to true, "e2" to true))

        assertFalse(state.allEventsDisabled())

        state.setEventEnabled("e1", false)
        state.setEventEnabled("e2", false)
        assertTrue(state.allEventsDisabled())
    }
}
