package io.github.syrou.reaktiv.devtools.ui

import io.github.syrou.reaktiv.devtools.ui.navmap.NAVIGATION_LINKS_EXTENSION
import io.github.syrou.reaktiv.introspection.protocol.CapturedAction
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigationMapReducerTest {

    private val reduce = DevToolsUiModule.reducer

    private fun action(index: Int) = CapturedAction(
        clientId = "device",
        timestamp = index.toLong(),
        actionType = "Action$index",
        actionData = "{}",
        stateDeltaJson = "{}"
    )

    private fun attempt(id: String) = LinkAttempt("news/feed", "news/feed", ServiceCall(id, 0L))

    private fun DevToolsUiState.apply(vararg actions: DevToolsUiAction): DevToolsUiState =
        actions.fold(this) { state, action -> reduce(state, action) }

    @Test
    fun `live actions never pull the user away from a selected route`() {
        val state = DevToolsUiState().apply(
            DevToolsUiAction.AddActionStateEvent(action(0)),
            DevToolsUiAction.SelectRoute("news/feed"),
            DevToolsUiAction.AddActionStateEvent(action(1)),
            DevToolsUiAction.AddActionStateEvent(action(2))
        )

        assertEquals(Selection.Route("news/feed"), state.selection)
        assertFalse(state.followLatest)
    }

    @Test
    fun `a history resync keeps a route or graph selection`() {
        val route = DevToolsUiState().apply(
            DevToolsUiAction.SelectRoute("news/feed"),
            DevToolsUiAction.ClearSession,
            DevToolsUiAction.AddActionStateEvent(action(0))
        )
        val graph = DevToolsUiState().apply(
            DevToolsUiAction.SelectGraph("news"),
            DevToolsUiAction.ResetHistoryForSync
        )

        assertEquals(Selection.Route("news/feed"), route.selection)
        assertEquals(Selection.Graph("news"), graph.selection)
    }

    @Test
    fun `an action selection is still cleared by a resync`() {
        val state = DevToolsUiState().apply(
            DevToolsUiAction.AddActionStateEvent(action(0)),
            DevToolsUiAction.SelectAction(0),
            DevToolsUiAction.ResetHistoryForSync
        )

        assertEquals(Selection.None, state.selection)
        assertTrue(state.followLatest)
    }

    @Test
    fun `a late answer still wins over giving up`() {
        val state = DevToolsUiState().apply(
            DevToolsUiAction.LinkAttemptStarted(attempt("a")),
            DevToolsUiAction.ServiceRequestUnanswered("a"),
            DevToolsUiAction.ServiceReplyReceived("a", JsonPrimitive("landed"), null)
        )

        assertEquals(RequestStatus.ANSWERED, state.linkAttempts.single().status)
        assertEquals(JsonPrimitive("landed"), state.linkAttempts.single().result)
    }

    @Test
    fun `giving up never overwrites an answer`() {
        val state = DevToolsUiState().apply(
            DevToolsUiAction.LinkAttemptStarted(attempt("a")),
            DevToolsUiAction.ServiceReplyReceived("a", null, "turned off"),
            DevToolsUiAction.ServiceRequestUnanswered("a")
        )

        assertEquals(RequestStatus.ANSWERED, state.linkAttempts.single().status)
        assertEquals("turned off", state.linkAttempts.single().error)
    }

    @Test
    fun `an app links reply fills the pending call and a late timeout leaves it alone`() {
        val state = DevToolsUiState().apply(
            DevToolsUiAction.AppLinksRequested(ServiceCall("files", 0L)),
            DevToolsUiAction.ServiceReplyReceived("files", JsonPrimitive("generated"), null),
            DevToolsUiAction.ServiceRequestUnanswered("files")
        )

        assertEquals(RequestStatus.ANSWERED, state.appLinksCall?.status)
        assertEquals(JsonPrimitive("generated"), state.appLinksCall?.result)
    }

    @Test
    fun `an unanswered app links call is marked as such`() {
        val state = DevToolsUiState().apply(
            DevToolsUiAction.AppLinksRequested(ServiceCall("files", 0L)),
            DevToolsUiAction.ServiceRequestUnanswered("files")
        )

        assertEquals(RequestStatus.UNANSWERED, state.appLinksCall?.status)
    }

    @Test
    fun `the app links form splits ids and fingerprints on commas spaces and lines`() {
        val form = AppLinksForm(appleAppIds = "A.one, B.two\nC.three", androidCertFingerprints = " AA:BB ,CC:DD ")

        assertEquals(listOf("A.one", "B.two", "C.three"), form.appleAppIdList)
        assertEquals(listOf("AA:BB", "CC:DD"), form.fingerprintList)
    }

    @Test
    fun `the app links dialog opens on the routes and keeps the chosen file tab`() {
        val state = DevToolsUiState().apply(
            DevToolsUiAction.SetAppLinksTab(AppLinksTab.ASSET_LINKS),
            DevToolsUiAction.AppLinksRequested(ServiceCall("files", 0L)),
            DevToolsUiAction.ServiceReplyReceived("files", JsonPrimitive("generated"), null)
        )

        assertEquals(AppLinksTab.ROUTES, DevToolsUiState().appLinksTab)
        assertEquals(AppLinksTab.ASSET_LINKS, state.appLinksTab)
    }

    @Test
    fun `only the most recent attempts are kept`() {
        val state = (0 until MAX_LINK_ATTEMPTS + 5).fold(DevToolsUiState()) { acc, index ->
            reduce(acc, DevToolsUiAction.LinkAttemptStarted(attempt("a$index")))
        }

        assertEquals(MAX_LINK_ATTEMPTS, state.linkAttempts.size)
        assertEquals("a${MAX_LINK_ATTEMPTS + 4}", state.linkAttempts.last().requestId)
    }

    @Test
    fun `collapsing a graph toggles`() {
        val collapsed = DevToolsUiState().apply(DevToolsUiAction.ToggleGraphCollapsed("news"))
        val expanded = collapsed.apply(DevToolsUiAction.ToggleGraphCollapsed("news"))

        assertEquals(setOf("news"), collapsed.collapsedGraphs)
        assertEquals(emptySet(), expanded.collapsedGraphs)
    }

    @Test
    fun `the nav tab has content once a map arrives even before any action`() {
        val empty = DevToolsUiState()
        val withMap = empty.apply(
            DevToolsUiAction.SetExtensions(mapOf(NAVIGATION_LINKS_EXTENSION to buildJsonObject { }))
        )

        assertFalse(empty.hasContentFor(DevToolsDestination.NAVIGATION))
        assertTrue(withMap.hasContentFor(DevToolsDestination.NAVIGATION))
        assertFalse(withMap.hasContentFor(DevToolsDestination.STREAM))
    }

    @Test
    fun `the live position follows the selected action then time travel then the newest action`() {
        val history = DevToolsUiState().apply(
            DevToolsUiAction.AddActionStateEvent(action(0)),
            DevToolsUiAction.AddActionStateEvent(action(1)),
            DevToolsUiAction.AddActionStateEvent(action(2))
        )

        assertEquals(1, history.apply(DevToolsUiAction.SelectAction(1)).navigationPositionIndex)
        assertEquals(2, history.apply(DevToolsUiAction.SelectRoute("news/feed")).navigationPositionIndex)
    }
}
