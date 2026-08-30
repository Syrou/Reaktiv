package io.github.syrou.reaktiv.devtools.ui

import io.github.syrou.reaktiv.introspection.network.NetworkBodyPart
import io.github.syrou.reaktiv.introspection.network.NetworkRequestCapture
import io.github.syrou.reaktiv.introspection.protocol.CapturedAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DevToolsUiReducerTest {

    private val reduce = DevToolsUiModule.reducer

    private fun action(index: Int) = CapturedAction(
        clientId = "device",
        timestamp = index.toLong(),
        actionType = "Action$index",
        actionData = "{}",
        stateDeltaJson = "{}"
    )

    private fun request(id: String) = NetworkEventRow(
        clientId = "device",
        event = NetworkRequestCapture(
            id = id,
            startedAtMs = 0,
            durationMs = 1,
            method = "GET",
            url = "https://example.com/$id"
        )
    )

    @Test
    fun `appending data advances the revision`() {
        val start = DevToolsUiState()
        val next = reduce(start, DevToolsUiAction.AddActionStateEvent(action(1)))

        assertEquals(start.dataRevision + 1, next.dataRevision)
    }

    @Test
    fun `view only changes leave the revision alone`() {
        val withData = reduce(DevToolsUiState(), DevToolsUiAction.AddActionStateEvent(action(1)))

        val toggled = reduce(withData, DevToolsUiAction.ToggleStateViewMode)
        val moded = reduce(toggled, DevToolsUiAction.SetMode(DevToolsMode.NETWORK))
        val searched = reduce(moded, DevToolsUiAction.SetSearchQuery("boom"))

        assertEquals(withData.dataRevision, searched.dataRevision)
    }

    @Test
    fun `a capped list that changes content without changing size still advances the revision`() {
        var state = DevToolsUiState()
        repeat(2000) { index ->
            state = reduce(state, DevToolsUiAction.AppendNetworkEvents(listOf(request("net-$index"))))
        }
        val atCap = state
        assertEquals(2000, atCap.networkEvents.size)

        val after = reduce(atCap, DevToolsUiAction.AppendNetworkEvents(listOf(request("net-new"))))

        assertEquals(2000, after.networkEvents.size, "The cap still holds")
        assertEquals("net-new", after.networkEvents.last().event.id, "Content changed")
        assertTrue(
            after.dataRevision > atCap.dataRevision,
            "Size is unchanged at the cap, so only a revision catches this"
        )
    }

    @Test
    fun `clearing history advances the revision`() {
        val withData = reduce(DevToolsUiState(), DevToolsUiAction.AddActionStateEvent(action(1)))
        val cleared = reduce(withData, DevToolsUiAction.ClearHistory)

        assertTrue(cleared.dataRevision > withData.dataRevision)
        assertTrue(cleared.actionStateHistory.isEmpty())
    }

    @Test
    fun `the stream follows the head until you scrub away from it`() {
        var state = DevToolsUiState()
        repeat(3) { state = reduce(state, DevToolsUiAction.AddActionStateEvent(action(it))) }

        assertTrue(state.followLatest, "A fresh session follows the head")
        assertEquals(2, state.selectedActionIndex, "The newest action is selected")

        state = reduce(state, DevToolsUiAction.SelectAction(0))
        assertFalse(state.followLatest, "Selecting an older action stops following")

        state = reduce(state, DevToolsUiAction.AddActionStateEvent(action(3)))
        assertEquals(0, state.selectedActionIndex, "A new action does not steal the selection")
        assertEquals(3, state.newEventsWhilePaused, "The gap to the head is offered as a count")
    }

    @Test
    fun `going back to the newest action re-arms following`() {
        var state = DevToolsUiState()
        repeat(3) { state = reduce(state, DevToolsUiAction.AddActionStateEvent(action(it))) }
        state = reduce(state, DevToolsUiAction.SelectAction(0))
        assertFalse(state.followLatest)

        state = reduce(state, DevToolsUiAction.SelectAction(state.latestSelectableIndex))
        assertTrue(state.followLatest, "Landing on the head follows again, with no separate toggle")

        state = reduce(state, DevToolsUiAction.AddActionStateEvent(action(3)))
        assertEquals(3, state.selectedActionIndex, "Following moves the selection to the new head")
        assertEquals(0, state.newEventsWhilePaused)
    }

    @Test
    fun `time travel and changing mode both stop the stream following`() {
        var state = DevToolsUiState()
        repeat(3) { state = reduce(state, DevToolsUiAction.AddActionStateEvent(action(it))) }

        val travelling = reduce(state, DevToolsUiAction.SetTimeTravelPosition(1))
        assertFalse(travelling.followLatest, "Scrubbing time travel is an explicit look at the past")

        val moded = reduce(state, DevToolsUiAction.SetMode(DevToolsMode.NETWORK))
        assertFalse(moded.followLatest, "Changing mode closes the inspector and stops following")
        assertEquals(Selection.None, moded.selection)

        val afterEvent = reduce(moded, DevToolsUiAction.AddActionStateEvent(action(3)))
        assertEquals(Selection.None, afterEvent.selection, "The inspector stays closed")
    }

    @Test
    fun `selecting one thing clears the other selections`() {
        var state = DevToolsUiState()
        state = reduce(state, DevToolsUiAction.AddActionStateEvent(action(1)))

        state = reduce(state, DevToolsUiAction.SelectAction(0))
        assertEquals(0, state.selectedActionIndex)

        state = reduce(state, DevToolsUiAction.SelectNetworkRequest("net-1"))
        assertEquals("net-1", state.selectedNetworkRequestId)
        assertNull(state.selectedActionIndex, "Selecting a request must clear the action selection")

        state = reduce(state, DevToolsUiAction.SelectLogicMethodEvent("call-1"))
        assertEquals("call-1", state.selectedLogicMethodCallId)
        assertNull(state.selectedNetworkRequestId, "Selecting logic must clear the request selection")

        state = reduce(state, DevToolsUiAction.SelectCrash(true))
        assertTrue(state.crashSelected)
        assertNull(state.selectedLogicMethodCallId, "Selecting the crash must clear the logic selection")
    }

    @Test
    fun `body chunks assemble in order and reject a gap`() {
        val key = networkBodyKey("net-1", NetworkBodyPart.RESPONSE)
        var state = reduce(
            DevToolsUiState(),
            DevToolsUiAction.NetworkBodyRequested("net-1", NetworkBodyPart.RESPONSE)
        )
        assertTrue(state.networkBodies.getValue(key).loading)

        state = reduce(state, chunk(content = "{\"a\":", offset = 0, nextOffset = 5, isLast = false))
        assertEquals("{\"a\":", state.networkBodies.getValue(key).text)

        val beforeGap = state
        state = reduce(state, chunk(content = "IGNORED", offset = 99, nextOffset = 120, isLast = false))
        assertEquals(
            beforeGap.networkBodies.getValue(key).text,
            state.networkBodies.getValue(key).text,
            "A chunk that does not start where the last one ended must be dropped"
        )

        state = reduce(state, chunk(content = "1}", offset = 5, nextOffset = 7, isLast = true))
        val load = state.networkBodies.getValue(key)
        assertEquals("{\"a\":1}", load.text)
        assertTrue(load.complete)
        assertFalse(load.loading)
    }

    @Test
    fun `an unavailable body stops the load and is marked`() {
        var state = reduce(
            DevToolsUiState(),
            DevToolsUiAction.NetworkBodyRequested("net-1", NetworkBodyPart.RESPONSE)
        )
        state = reduce(
            state,
            DevToolsUiAction.NetworkBodyChunkArrived(
                requestId = "net-1",
                part = NetworkBodyPart.RESPONSE,
                content = "",
                offset = 0,
                nextOffset = 0,
                totalBytes = 0,
                isLast = true,
                available = false
            )
        )

        val load = state.networkBodies.getValue(networkBodyKey("net-1", NetworkBodyPart.RESPONSE))
        assertTrue(load.unavailable)
        assertFalse(load.loading)
    }

    private fun chunk(content: String, offset: Int, nextOffset: Int, isLast: Boolean) =
        DevToolsUiAction.NetworkBodyChunkArrived(
            requestId = "net-1",
            part = NetworkBodyPart.RESPONSE,
            content = content,
            offset = offset,
            nextOffset = nextOffset,
            totalBytes = 7,
            isLast = isLast,
            available = true
        )
}
