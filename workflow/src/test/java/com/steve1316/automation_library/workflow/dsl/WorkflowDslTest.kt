package com.steve1316.automation_library.workflow.dsl

import com.steve1316.automation_library.workflow.model.Action
import com.steve1316.automation_library.workflow.model.Condition
import com.steve1316.automation_library.workflow.model.ConditionOperator
import com.steve1316.automation_library.workflow.model.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowDslTest {
    @Test
    fun `scenario dsl builds scenario with events`() {
        val scenario =
            scenario(id = "test", name = "Test") {
                imageEvent(id = "e1", name = "click-btn") {
                    conditionOperator = ConditionOperator.AND
                    condition { imageAppears("button", confidence = 0.9) }
                    action { tap(100.0, 200.0) }
                    action { complete() }
                }
            }

        assertEquals("test", scenario.id)
        assertEquals(1, scenario.events.size)

        val event = scenario.events[0]
        assertTrue(event is Event.Image)
        assertEquals("e1", event.id)
        assertEquals(1, event.conditions.size)
        assertTrue(event.conditions[0] is Condition.ImageAppears)
        assertEquals(0.9, (event.conditions[0] as Condition.ImageAppears).confidence, 0.001)

        assertEquals(2, event.actions.size)
        assertTrue(event.actions[0] is Action.Tap)
        assertTrue(event.actions[1] is Action.Complete)
    }

    @Test
    fun `dsl supports trigger events`() {
        val scenario =
            scenario(id = "s", name = "n") {
                triggerEvent(id = "t1", name = "timer") {
                    condition { timerReached(5000L) }
                    action { wait(0.5) }
                }
            }

        assertEquals(1, scenario.events.size)
        assertTrue(scenario.events[0] is Event.Trigger)
    }

    @Test
    fun `dsl supports or conditions`() {
        val scenario =
            scenario(id = "s", name = "n") {
                imageEvent(id = "e1", name = "test") {
                    conditionOperator = ConditionOperator.OR
                    condition { counterReached("c1", 10) }
                    condition { imageAppears("rare") }
                    action { tap(0.0, 0.0) }
                }
            }

        assertEquals(ConditionOperator.OR, scenario.events[0].conditionOperator)
        assertEquals(2, scenario.events[0].conditions.size)
    }
}
