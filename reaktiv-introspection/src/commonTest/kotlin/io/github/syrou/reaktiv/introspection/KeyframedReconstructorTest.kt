package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.introspection.protocol.CapturedAction
import io.github.syrou.reaktiv.introspection.protocol.KeyframedReconstructor
import kotlin.test.Test
import kotlin.test.assertEquals

class KeyframedReconstructorTest {

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

    private val initial = """{"Counter":{"count":0},"Auth":{"loggedIn":false}}"""

    private val actions = listOf(
        capturedAction("Counter", """{"count":1}""", 0),
        capturedAction("Counter", """{"count":2}""", 1),
        capturedAction("Auth", """{"loggedIn":true}""", 2),
        capturedAction("Counter", """{"count":3}""", 3)
    )

    private val expected = listOf(
        """{"Counter":{"count":1},"Auth":{"loggedIn":false}}""",
        """{"Counter":{"count":2},"Auth":{"loggedIn":false}}""",
        """{"Counter":{"count":2},"Auth":{"loggedIn":true}}""",
        """{"Counter":{"count":3},"Auth":{"loggedIn":true}}"""
    )

    @Test
    fun `stateAt applies deltas sequentially`() {
        val reconstructor = KeyframedReconstructor(initial, actions)

        expected.forEachIndexed { index, state -> assertEquals(state, reconstructor.stateAt(index)) }
    }

    @Test
    fun `stateAt gives the same states across keyframe boundaries`() {
        val reconstructor = KeyframedReconstructor(initial, actions, interval = 2)

        expected.forEachIndexed { index, state -> assertEquals(state, reconstructor.stateAt(index)) }
    }

    @Test
    fun `stateAt with no actions returns the initial state`() {
        val reconstructor = KeyframedReconstructor(initial, emptyList())

        assertEquals(initial, reconstructor.stateAt(0))
    }

    @Test
    fun `stateAt clamps an index past the end to the last action`() {
        val reconstructor = KeyframedReconstructor(initial, actions)

        assertEquals(expected.last(), reconstructor.stateAt(100))
    }

    @Test
    fun `stateAt with a negative index returns the initial state`() {
        val reconstructor = KeyframedReconstructor(initial, actions)

        assertEquals(initial, reconstructor.stateAt(-5))
    }
}
