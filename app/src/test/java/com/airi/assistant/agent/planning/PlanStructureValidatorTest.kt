package com.airi.assistant.agent.planning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanStructureValidatorTest {

    @Test
    fun validate_returnsPlannerOrderForIndependentNodesAndDependencyOrderForDependentNodes() {
        val result = PlanStructureValidator.validate(
            listOf(
                node("second", "first"),
                node("first"),
                node("third"),
            )
        )

        assertTrue(result.isValid)
        assertEquals(listOf("first", "second", "third"), result.dependencyOrder)
    }

    @Test
    fun validate_rejectsEmptyPlanAndStepLimitOverflow() {
        val empty = PlanStructureValidator.validate(emptyList())
        val overLimit = PlanStructureValidator.validate((1..17).map { node("step-$it") })

        assertFalse(empty.isValid)
        assertTrue(PlanValidationError.EMPTY_PLAN in empty.errors)
        assertFalse(overLimit.isValid)
        assertTrue(PlanValidationError.STEP_LIMIT_EXCEEDED in overLimit.errors)
    }

    @Test
    fun validate_rejectsBlankAndDuplicateStepIds() {
        val blank = PlanStructureValidator.validate(listOf(node("")))
        val duplicate = PlanStructureValidator.validate(listOf(node("same"), node("same")))

        assertTrue(PlanValidationError.BLANK_STEP_ID in blank.errors)
        assertTrue(PlanValidationError.DUPLICATE_STEP_ID in duplicate.errors)
    }

    @Test
    fun validate_rejectsMissingAndSelfDependencies() {
        val missing = PlanStructureValidator.validate(listOf(node("one", "missing")))
        val self = PlanStructureValidator.validate(listOf(node("one", "one")))

        assertTrue(PlanValidationError.MISSING_DEPENDENCY in missing.errors)
        assertTrue(PlanValidationError.SELF_DEPENDENCY in self.errors)
    }

    @Test
    fun validate_rejectsIndirectDependencyCycles() {
        val cycle = PlanStructureValidator.validate(
            listOf(
                node("one", "three"),
                node("two", "one"),
                node("three", "two"),
            )
        )

        assertFalse(cycle.isValid)
        assertTrue(PlanValidationError.CYCLIC_DEPENDENCY in cycle.errors)
        assertTrue(cycle.dependencyOrder.isEmpty())
    }

    private fun node(id: String, vararg dependsOn: String) =
        PlanNodeReference(id = id, dependsOn = dependsOn.toList())
}
