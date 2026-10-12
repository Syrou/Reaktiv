import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilDoesNotExist
import androidx.compose.ui.test.waitUntilExactlyOneExists
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.syrou.reaktiv.compose.StoreProvider
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Graph
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.PaneLayout
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.dsl.NavigationBuilder
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.ui.NavigationRender
import io.github.syrou.reaktiv.navigation.ui.currentPaneColumn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PaneLayoutUiTest {

    private fun screen(route: String, label: String) = object : Screen {
        override val route = route
        override val enterTransition = NavTransition.SlideInRight
        override val exitTransition = NavTransition.SlideOutLeft

        @Composable
        override fun Content(params: Params) {
            Box(Modifier.fillMaxSize().testTag("pane-$label")) {
                Text("$label ${params.getString("messageId").orEmpty()}".trim())
            }
        }
    }

    private val home = screen("home", "Home")
    private val inbox = screen("inbox", "Inbox")
    private val message = screen("message/{messageId}", "Message")
    private val thread = screen("message/{messageId}/thread", "Thread")

    private val reply = object : Modal {
        override val route = "message/{messageId}/reply"
        override val enterTransition = NavTransition.SlideUpBottom
        override val exitTransition = NavTransition.SlideOutBottom

        @Composable
        override fun Content(params: Params) {
            Text(if (currentPaneColumn() == null) "Reply on top" else "Reply in column ${currentPaneColumn()}")
        }
    }

    private fun mailGraph(detailOnDemand: Boolean) = object : Graph {
        override val route = "mail"
        override val paneLayout = PaneLayout {
            expanded(column(inbox), column(message, thread)) { inboxSlot, messageSlot ->
                Row(Modifier.fillMaxSize()) {
                    inboxSlot(Modifier.weight(0.4f))
                    if (!detailOnDemand) {
                        messageSlot(Modifier.weight(0.6f)) { Text("Select a message") }
                    } else if (messageSlot.isShowing) {
                        messageSlot(Modifier.weight(0.6f))
                    }
                }
            }
            large(column(inbox), column(message, thread), column(reply)) { inboxSlot, messageSlot, replySlot ->
                Row(Modifier.fillMaxSize()) {
                    inboxSlot(Modifier.weight(0.25f))
                    messageSlot(Modifier.weight(0.45f)) { Text("Select a message") }
                    if (replySlot.isOpen) {
                        replySlot(Modifier.weight(0.3f))
                    }
                }
            }
        }
    }

    private fun store(detailOnDemand: Boolean = false): Store = createStore {
        module(
            createNavigationModule {
                rootGraph {
                    start(home)
                    screens(home)
                    graph(mailGraph(detailOnDemand)) {
                        start(inbox)
                        screens(inbox, message, thread)
                        modals(reply)
                    }
                }
            }
        )
    }

    private fun ComposeUiTest.render(store: Store, width: Dp) {
        setContent {
            StoreProvider(store) {
                Box(Modifier.requiredSize(width, 700.dp).testTag("frame")) {
                    NavigationRender()
                }
            }
        }
    }

    private fun ComposeUiTest.open(
        store: Store,
        block: suspend NavigationBuilder.() -> Unit,
        shows: String
    ) {
        store.launch { store.navigation(block) }
        waitUntilExactlyOneExists(hasText(shows), timeoutMillis = UI_TEST_WAIT_MS)
        waitForIdle()
    }

    @Test
    fun an_expanded_window_shows_the_list_beside_the_open_message() = runComposeUiTest {
        val store = store()
        render(store, 1000.dp)

        open(store, { navigateTo("mail") }, shows = "Inbox")
        onNodeWithText("Select a message").assertExists()

        open(store, { navigateTo(message, "messageId" to "42") }, shows = "Message 42")
        waitUntilDoesNotExist(hasText("Select a message"), timeoutMillis = UI_TEST_WAIT_MS)
        onNodeWithText("Inbox").assertExists()

        val inboxLeft = onNodeWithText("Inbox").fetchSemanticsNode().boundsInRoot.left
        val messageLeft = onNodeWithText("Message 42").fetchSemanticsNode().boundsInRoot.left
        assertTrue(inboxLeft < messageLeft, "the message column sits right of the inbox")
    }

    private fun ComposeUiTest.widthOf(tag: String): Float =
        onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.width

    private fun ComposeUiTest.awaitInboxShare(share: Float) {
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) {
            abs(widthOf("pane-Inbox") - widthOf("frame") * share) < 2f
        }
    }

    @Test
    fun a_detail_column_shown_on_demand_opens_with_the_message_and_closes_after_back() = runComposeUiTest {
        val store = store(detailOnDemand = true)
        render(store, 1000.dp)

        open(store, { navigateTo("mail") }, shows = "Inbox")
        awaitInboxShare(1f)

        open(store, { navigateTo(message, "messageId" to "42") }, shows = "Message 42")
        awaitInboxShare(0.4f)

        mainClock.autoAdvance = false
        store.launch { store.navigation { navigateBack() } }
        val navigationState = runBlocking { store.selectState<NavigationState>() }
        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) { navigationState.value.currentEntry.route == "inbox" }
        mainClock.advanceTimeByFrame()
        waitForIdle()
        onNodeWithText("Message 42").assertExists()
        assertTrue(abs(widthOf("pane-Inbox") - widthOf("frame") * 0.4f) < 2f, "the column stays while the message leaves")

        mainClock.autoAdvance = true
        waitUntilDoesNotExist(hasText("Message 42"), timeoutMillis = UI_TEST_WAIT_MS)
        awaitInboxShare(1f)
    }

    @Test
    fun a_compact_window_keeps_one_screen_at_a_time() = runComposeUiTest {
        val store = store()
        render(store, 400.dp)

        open(store, { navigateTo("mail") }, shows = "Inbox")
        open(store, { navigateTo(message, "messageId" to "42") }, shows = "Message 42")
        waitUntilDoesNotExist(hasText("Inbox"), timeoutMillis = UI_TEST_WAIT_MS)
    }

    @Test
    fun a_screen_sharing_the_column_replaces_the_message_but_not_the_list() = runComposeUiTest {
        val store = store()
        render(store, 1000.dp)

        open(store, { navigateTo("mail") }, shows = "Inbox")
        open(store, { navigateTo(message, "messageId" to "42") }, shows = "Message 42")
        open(store, { navigateTo(thread, "messageId" to "42") }, shows = "Thread 42")

        waitUntilDoesNotExist(hasText("Message 42"), timeoutMillis = UI_TEST_WAIT_MS)
        onNodeWithText("Inbox").assertExists()
    }

    @Test
    fun a_modal_floats_on_top_unless_the_size_places_it() = runComposeUiTest {
        val store = store()
        render(store, 1000.dp)

        open(store, { navigateTo("mail") }, shows = "Inbox")
        open(store, { navigateTo(message, "messageId" to "42") }, shows = "Message 42")
        open(store, { navigateTo(reply, "messageId" to "42") }, shows = "Reply on top")
        onNodeWithText("Inbox").assertExists()
    }

    @Test
    fun a_large_window_places_the_modal_in_its_own_column() = runComposeUiTest {
        val store = store()
        render(store, 1300.dp)

        open(store, { navigateTo("mail") }, shows = "Inbox")
        open(store, { navigateTo(message, "messageId" to "42") }, shows = "Message 42")
        open(store, { navigateTo(reply, "messageId" to "42") }, shows = "Reply in column 2")

        onNodeWithText("Inbox").assertExists()
        onNodeWithText("Message 42").assertExists()
        val messageLeft = onNodeWithText("Message 42").fetchSemanticsNode().boundsInRoot.left
        val replyLeft = onNodeWithText("Reply in column 2").fetchSemanticsNode().boundsInRoot.left
        assertTrue(messageLeft < replyLeft, "the reply column sits right of the message")
        assertEquals(1, onAllNodesWithText("Inbox").fetchSemanticsNodes().size)
    }
}
