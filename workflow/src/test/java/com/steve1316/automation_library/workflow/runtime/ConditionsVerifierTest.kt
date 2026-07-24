package com.steve1316.automation_library.workflow.runtime

import android.graphics.PointF
import com.steve1316.automation_library.workflow.AutomationBackend
import com.steve1316.automation_library.workflow.logging.StepLogger
import com.steve1316.automation_library.workflow.model.Condition
import com.steve1316.automation_library.workflow.model.ConditionOperator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConditionsVerifierTest {
    private val noopLogger = object : StepLogger {}

    private fun backend(
        imageResult: PointF? = null,
        textResult: String? = null,
        customResult: Boolean = false,
    ): AutomationBackend =
        object : AutomationBackend {
            override val logger = noopLogger

            override fun findImage(templateName: String, confidence: Double, region: IntArray) = imageResult

            override fun findText(text: String, region: IntArray, similarity: Double) = textResult

            override fun tap(x: Double, y: Double, imageName: String?) = true

            override fun longPress(x: Double, y: Double, imageName: String?, durationMs: Long) = true

            override fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long) = true

            override fun scroll(scrollDown: Boolean, durationMs: Long) = true

            override fun wait(seconds: Double) {}

            override fun evaluateCustomCondition(id: String) = customResult
        }

    @Test
    fun `AND returns true when all conditions satisfied`() {
        val state = ProcessingState(nowMsProvider = { 0L })
        state.changeCounter("c1", 5)
        val verifier = ConditionsVerifier(state)
        val conditions =
            listOf(
                Condition.CounterReached("c1", 5),
                Condition.ImageAppears("btn", shouldBeDetected = true),
            )
        assertTrue(verifier.verify(conditions, ConditionOperator.AND, backend(imageResult = PointF(1f, 1f))))
    }

    @Test
    fun `AND short circuits on first false`() {
        val state = ProcessingState()
        val verifier = ConditionsVerifier(state)
        val conditions =
            listOf(
                Condition.CounterReached("missing", 100), // false (counter=0)
                Condition.ImageAppears("btn"), // 不会被求值
            )
        assertFalse(verifier.verify(conditions, ConditionOperator.AND, backend(imageResult = PointF(1f, 1f))))
    }

    @Test
    fun `OR returns true when any condition satisfied`() {
        val state = ProcessingState()
        val verifier = ConditionsVerifier(state)
        val conditions =
            listOf(
                Condition.CounterReached("missing", 100), // false
                Condition.ImageAppears("btn"), // true
            )
        assertTrue(verifier.verify(conditions, ConditionOperator.OR, backend(imageResult = PointF(1f, 1f))))
    }

    @Test
    fun `OR short circuits on first true`() {
        val state = ProcessingState()
        state.changeCounter("c1", 100)
        val verifier = ConditionsVerifier(state)
        val conditions =
            listOf(
                Condition.CounterReached("c1", 100), // true
                Condition.ImageAppears("btn"), // 不会被求值
            )
        assertTrue(verifier.verify(conditions, ConditionOperator.OR, backend(imageResult = null)))
    }

    @Test
    fun `ImageAppears with shouldBeDetected=false returns true when image absent`() {
        val state = ProcessingState()
        val verifier = ConditionsVerifier(state)
        val conditions = listOf(Condition.ImageAppears("popup", shouldBeDetected = false))
        assertTrue(verifier.verify(conditions, ConditionOperator.AND, backend(imageResult = null)))
    }

    @Test
    fun `TextMatches returns true when text found`() {
        val state = ProcessingState()
        val verifier = ConditionsVerifier(state)
        val conditions = listOf(Condition.TextMatches("已强化"))
        assertTrue(verifier.verify(conditions, ConditionOperator.AND, backend(textResult = "已强化+20")))
    }
}
