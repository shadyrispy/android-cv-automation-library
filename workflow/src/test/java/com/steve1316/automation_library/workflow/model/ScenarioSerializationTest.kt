package com.steve1316.automation_library.workflow.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenarioSerializationTest {

    @Test
    fun `scenario with image event round trips through json`() {
        val original = Scenario(
            id = "scan",
            name = "Test Scan",
            events = listOf(
                Event.Image(
                    id = "e1",
                    name = "find-button",
                    conditionOperator = ConditionOperator.AND,
                    conditions = listOf(
                        Condition.ImageAppears("btn", confidence = 0.9),
                        Condition.CounterReached("clicks", 5),
                    ),
                    actions = listOf(
                        Action.Tap(100.0, 200.0, imageName = "btn"),
                        Action.Complete,
                    ),
                    priority = 1,
                    keepEvaluating = false,
                ),
            ),
            maxDurationMinutes = 30,
        )

        val jsonString = original.toJson()
        val restored = scenarioFromJson(jsonString)

        assertEquals(original.id, restored.id)
        assertEquals(original.name, restored.name)
        assertEquals(original.maxDurationMinutes, restored.maxDurationMinutes)
        assertEquals(1, restored.events.size)

        val event = restored.events[0] as Event.Image
        assertEquals("e1", event.id)
        assertEquals(1, event.priority)
        assertEquals(2, event.conditions.size)
        assertTrue(event.conditions[0] is Condition.ImageAppears)
        assertTrue(event.conditions[1] is Condition.CounterReached)
        assertEquals(2, event.actions.size)
        assertTrue(event.actions[0] is Action.Tap)
        assertTrue(event.actions[1] is Action.Complete)
    }

    @Test
    fun `scenario with mixed trigger and image events round trips`() {
        val original = Scenario(
            id = "mixed",
            name = "Mixed",
            events = listOf(
                Event.Trigger(
                    id = "t1",
                    name = "timer",
                    conditions = listOf(Condition.TimerReached(5000L, restartWhenReached = true)),
                    actions = listOf(Action.Wait(0.5)),
                ),
                Event.Image(
                    id = "i1",
                    name = "image",
                    conditions = listOf(Condition.TextMatches("hello")),
                    actions = listOf(Action.Scroll(scrollDown = true)),
                ),
            ),
        )

        val jsonString = original.toJson()
        val restored = scenarioFromJson(jsonString)

        assertEquals(2, restored.events.size)
        assertTrue(restored.events[0] is Event.Trigger)
        assertTrue(restored.events[1] is Event.Image)
    }
}
