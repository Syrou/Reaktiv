package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.introspection.protocol.CapturedAction
import io.github.syrou.reaktiv.introspection.protocol.StateReconstructor
import kotlin.test.Test
import kotlin.test.assertEquals

class StateReconstructorTest {

    private fun capturedAction(
        moduleName: String,
        deltaJson: String,
        index: Int = 0
    ) = CapturedAction(
        clientId = "test-client",
        timestamp = 1000L + index,
        actionType = "TestAction",
        actionData = "data",
        stateDeltaJson = deltaJson,
        moduleName = moduleName
    )

    @Test
    fun `applyDelta replaces existing module key`() {
        val current = """{"ModuleA":{"count":0},"ModuleB":{"name":"test"}}"""
        val result = StateReconstructor.applyDelta(current, "ModuleA", """{"count":5}""")

        assertEquals(
            """{"ModuleA":{"count":5},"ModuleB":{"name":"test"}}""",
            result
        )
    }

    @Test
    fun `applyDelta adds new module key`() {
        val current = """{"ModuleA":{"count":0}}"""
        val result = StateReconstructor.applyDelta(current, "ModuleB", """{"name":"new"}""")

        assertEquals(
            """{"ModuleA":{"count":0},"ModuleB":{"name":"new"}}""",
            result
        )
    }

    @Test
    fun `applyDelta with blank moduleName returns unchanged state`() {
        val current = """{"ModuleA":{"count":0}}"""
        val result = StateReconstructor.applyDelta(current, "", """{"count":5}""")

        assertEquals(current, result)
    }

    @Test
    fun `applyDelta with unparseable delta JSON returns unchanged state`() {
        val current = """{"ModuleA":{"count":0}}"""
        val result = StateReconstructor.applyDelta(current, "ModuleA", "{invalid json!!!")

        assertEquals(current, result)
    }

    @Test
    fun `applyDelta with invalid current state JSON returns unchanged`() {
        val result = StateReconstructor.applyDelta("not-json", "ModuleA", """{"count":5}""")

        assertEquals("not-json", result)
    }

    @Test
    fun `reconstructAtIndex applies deltas sequentially`() {
        val initial = """{"Counter":{"count":0},"Auth":{"loggedIn":false}}"""
        val actions = listOf(
            capturedAction("Counter", """{"count":1}""", 0),
            capturedAction("Counter", """{"count":2}""", 1),
            capturedAction("Auth", """{"loggedIn":true}""", 2),
            capturedAction("Counter", """{"count":3}""", 3)
        )

        val atIndex0 = StateReconstructor.reconstructAtIndex(initial, actions, 0)
        assertEquals("""{"Counter":{"count":1},"Auth":{"loggedIn":false}}""", atIndex0)

        val atIndex1 = StateReconstructor.reconstructAtIndex(initial, actions, 1)
        assertEquals("""{"Counter":{"count":2},"Auth":{"loggedIn":false}}""", atIndex1)

        val atIndex2 = StateReconstructor.reconstructAtIndex(initial, actions, 2)
        assertEquals("""{"Counter":{"count":2},"Auth":{"loggedIn":true}}""", atIndex2)

        val atIndex3 = StateReconstructor.reconstructAtIndex(initial, actions, 3)
        assertEquals("""{"Counter":{"count":3},"Auth":{"loggedIn":true}}""", atIndex3)
    }

    @Test
    fun `reconstructAtIndex with empty actions returns initial state`() {
        val initial = """{"Counter":{"count":0}}"""
        val result = StateReconstructor.reconstructAtIndex(initial, emptyList(), 0)

        assertEquals(initial, result)
    }

    @Test
    fun `reconstructAtIndex clamps index to valid range`() {
        val initial = """{"Counter":{"count":0}}"""
        val actions = listOf(
            capturedAction("Counter", """{"count":1}""", 0),
            capturedAction("Counter", """{"count":2}""", 1)
        )

        val result = StateReconstructor.reconstructAtIndex(initial, actions, 100)
        assertEquals("""{"Counter":{"count":2}}""", result)
    }

    @Test
    fun `reconstructAtIndex with negative index clamps to zero`() {
        val initial = """{"Counter":{"count":0}}"""
        val actions = listOf(
            capturedAction("Counter", """{"count":1}""", 0)
        )

        val result = StateReconstructor.reconstructAtIndex(initial, actions, -5)
        assertEquals("""{"Counter":{"count":1}}""", result)
    }
}
