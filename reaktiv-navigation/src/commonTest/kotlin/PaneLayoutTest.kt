import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.NavigationModule
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.BackstackLifecycle
import io.github.syrou.reaktiv.navigation.definition.Graph
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.PaneLayout
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.definition.WindowWidthClass
import io.github.syrou.reaktiv.navigation.extension.navigateBack
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class PaneLayoutTest {

    private fun screen(
        route: String,
        onCreated: (suspend (BackstackLifecycle) -> Unit)? = null
    ) = object : Screen {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        override suspend fun onLifecycleCreated(lifecycle: BackstackLifecycle) {
            onCreated?.invoke(lifecycle)
        }

        @Composable
        override fun Content(params: Params) {
            Text(route)
        }
    }

    private fun modal(route: String) = object : Modal {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Text(route)
        }
    }

    private var inboxLifecycle: BackstackLifecycle? = null

    private val outside = screen("outside")
    private val other = screen("other")
    private val inbox = screen("inbox") { inboxLifecycle = it }
    private val message = screen("message/{messageId}")
    private val thread = screen("message/{messageId}/thread")
    private val mailSettings = screen("settings")
    private val reply = modal("message/{messageId}/reply")
    private val stray = screen("stray")

    private val mailGraph = object : Graph {
        override val route = "mail"
        override val paneLayout = PaneLayout {
            expanded(column(inbox), column(message, thread)) { _, _ -> }
            large(column(inbox), column(message, thread), column(reply)) { _, _, _ -> }
        }
    }

    private fun mailModule() = createNavigationModule {
        rootGraph {
            start(outside)
            screens(outside, other)
            graph(mailGraph) {
                start(inbox)
                screens(inbox, message, thread, mailSettings)
                modals(reply)
            }
        }
    }

    private fun TestScope.storeWith(module: NavigationModule): Store = createStore {
        module(module)
        coroutineContext(StandardTestDispatcher(testScheduler))
    }

    private suspend fun Store.nav(): NavigationState = selectState<NavigationState>().first()

    private suspend fun Store.locations(): List<String> = nav().backStack.map { it.location }

    private suspend fun Store.columns(): List<String?> = nav().paneColumns.map { it?.location }

    private fun Store.widen(widthClass: WindowWidthClass) {
        dispatch(NavigationAction.SetWindowWidthClass(widthClass))
    }

    private suspend fun TestScope.openMessage(store: Store, id: String) {
        store.navigation { navigateTo(message, "messageId" to id) }
        advanceUntilIdle()
    }

    private suspend fun TestScope.openMail(store: Store) {
        store.navigation { navigateTo("mail") }
        advanceUntilIdle()
    }

    @Test
    fun `width classes follow the material breakpoints`() {
        assertEquals(WindowWidthClass.Compact, WindowWidthClass.fromWidthDp(411f))
        assertEquals(WindowWidthClass.Medium, WindowWidthClass.fromWidthDp(600f))
        assertEquals(WindowWidthClass.Expanded, WindowWidthClass.fromWidthDp(1000f))
        assertEquals(WindowWidthClass.Large, WindowWidthClass.fromWidthDp(1280f))
        assertEquals(WindowWidthClass.ExtraLarge, WindowWidthClass.fromWidthDp(1920f))
    }

    @Test
    fun `an expanded window shows the inbox beside the open message`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = storeWith(mailModule())
            store.widen(WindowWidthClass.Expanded)
            openMail(store)
            openMessage(store, "42")

            val state = store.nav()
            assertEquals("mail", state.paneGraph)
            assertEquals(listOf("mail/inbox", "mail/message/42"), store.columns())
            assertEquals(listOf("mail/inbox", "mail/message/42"), state.visibleLayers.map { it.location })
            assertTrue(state.showsEntry(state.backStack.first { it.route == "inbox" }))
        }

    @Test
    fun `a compact window keeps the single stack`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = storeWith(mailModule())
            store.widen(WindowWidthClass.Compact)
            openMail(store)
            openMessage(store, "42")

            val state = store.nav()
            assertNull(state.paneGraph)
            assertEquals(emptyList(), state.paneColumns)
            assertEquals(listOf("mail/message/42"), state.visibleLayers.map { it.location })
        }

    @Test
    fun `a width class without a block uses the nearest smaller one`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = storeWith(mailModule())
            openMail(store)
            openMessage(store, "42")

            store.widen(WindowWidthClass.ExtraLarge)
            advanceUntilIdle()
            assertEquals(listOf("mail/inbox", "mail/message/42", null), store.columns())

            store.widen(WindowWidthClass.Medium)
            advanceUntilIdle()
            assertNull(store.nav().paneGraph)
        }

    @Test
    fun `a screen sharing a column stacks inside it`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = storeWith(mailModule())
            store.widen(WindowWidthClass.Expanded)
            openMail(store)
            openMessage(store, "42")
            store.navigation { navigateTo(thread, "messageId" to "42") }
            advanceUntilIdle()

            assertEquals(listOf("mail/inbox", "mail/message/42/thread"), store.columns())

            store.navigateBack()
            advanceUntilIdle()
            assertEquals(listOf("mail/inbox", "mail/message/42"), store.columns())
        }

    @Test
    fun `picking another message replaces the open one and everything after it`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = storeWith(mailModule())
            store.widen(WindowWidthClass.Expanded)
            openMail(store)
            openMessage(store, "42")
            store.navigation { navigateTo(thread, "messageId" to "42") }
            advanceUntilIdle()

            openMessage(store, "43")

            assertEquals(listOf("outside", "mail/inbox", "mail/message/43"), store.locations())
            assertEquals(listOf("mail/inbox", "mail/message/43"), store.columns())
        }

    @Test
    fun `the back stack is the same on a phone`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = storeWith(mailModule())
            openMail(store)
            openMessage(store, "42")
            store.navigation { navigateTo(thread, "messageId" to "42") }
            advanceUntilIdle()

            openMessage(store, "43")

            assertEquals(listOf("outside", "mail/inbox", "mail/message/43"), store.locations())
        }

    @Test
    fun `reopening the open message closes what was opened after it`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = storeWith(mailModule())
            store.widen(WindowWidthClass.Expanded)
            openMail(store)
            openMessage(store, "42")
            store.navigation { navigateTo(thread, "messageId" to "42") }
            advanceUntilIdle()

            openMessage(store, "42")

            assertEquals(listOf("outside", "mail/inbox", "mail/message/42"), store.locations())
        }

    @Test
    fun `a modal placed in a column is inline at that size`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = storeWith(mailModule())
            store.widen(WindowWidthClass.Large)
            openMail(store)
            openMessage(store, "42")
            store.navigation { navigateTo(reply, "messageId" to "42") }
            advanceUntilIdle()

            val state = store.nav()
            assertEquals(listOf("mail/inbox", "mail/message/42", "mail/message/42/reply"), store.columns())
            assertEquals(
                listOf("mail/inbox", "mail/message/42", "mail/message/42/reply"),
                state.contentLayerEntries.map { it.location }
            )
            assertEquals(emptyList(), state.globalOverlayEntries)
            assertTrue(state.showsNavigationChrome)
            assertTrue(state.showsEntry(state.backStack.first { it.route == "inbox" }))
        }

    @Test
    fun `a modal the size does not place shows on top of the panes`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = storeWith(mailModule())
            store.widen(WindowWidthClass.Expanded)
            openMail(store)
            openMessage(store, "42")
            store.navigation { navigateTo(reply, "messageId" to "42") }
            advanceUntilIdle()

            val state = store.nav()
            assertEquals(listOf("mail/inbox", "mail/message/42"), store.columns())
            assertEquals(listOf("mail/inbox", "mail/message/42"), state.contentLayerEntries.map { it.location })
            assertEquals(listOf("mail/message/42/reply"), state.globalOverlayEntries.map { it.location })
            assertFalse(state.showsEntry(state.backStack.first { it.route == "inbox" }))
            assertTrue(state.showsEntry(state.currentEntry))
        }

    @Test
    fun `resizing never changes the back stack`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = storeWith(mailModule())
            store.widen(WindowWidthClass.Large)
            openMail(store)
            openMessage(store, "42")
            store.navigation { navigateTo(reply, "messageId" to "42") }
            advanceUntilIdle()
            val before = store.locations()

            store.widen(WindowWidthClass.Expanded)
            advanceUntilIdle()
            assertEquals(before, store.locations())
            assertEquals(listOf("mail/message/42/reply"), store.nav().globalOverlayEntries.map { it.location })

            store.widen(WindowWidthClass.Compact)
            advanceUntilIdle()
            assertEquals(before, store.locations())
            assertEquals(emptyList(), store.nav().paneColumns)
        }

    @Test
    fun `a screen the layout does not place covers the panes`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = storeWith(mailModule())
            store.widen(WindowWidthClass.Expanded)
            openMail(store)
            openMessage(store, "42")
            store.navigation { navigateTo(mailSettings) }
            advanceUntilIdle()

            val state = store.nav()
            assertNull(state.paneGraph)
            assertEquals(listOf("mail/settings"), state.visibleLayers.map { it.location })
        }

    @Test
    fun `leaving the graph leaves the panes`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = storeWith(mailModule())
            store.widen(WindowWidthClass.Expanded)
            openMail(store)
            openMessage(store, "42")
            store.navigation { navigateTo(other) }
            advanceUntilIdle()

            assertNull(store.nav().paneGraph)

            openMessage(store, "43")
            assertEquals(
                listOf("outside", "mail/inbox", "mail/message/42", "other", "mail/message/43"),
                store.locations()
            )
        }

    @Test
    fun `an entry beside the current one is visible to its lifecycle`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = storeWith(mailModule())
            store.widen(WindowWidthClass.Expanded)
            openMail(store)
            openMessage(store, "42")

            assertTrue(inboxLifecycle!!.visibility.value)

            store.widen(WindowWidthClass.Compact)
            advanceUntilIdle()
            assertFalse(inboxLifecycle!!.visibility.value)
        }

    @Test
    fun `a pane layout cannot place a screen registered elsewhere`() {
        val graph = object : Graph {
            override val route = "mail"
            override val paneLayout = PaneLayout {
                expanded(column(inbox), column(stray)) { _, _ -> }
            }
        }
        val module = createNavigationModule {
            rootGraph {
                start(outside)
                screens(outside, stray)
                graph(graph) {
                    start(inbox)
                    screens(inbox)
                }
            }
        }

        assertFailsWith<IllegalStateException> { module.getAllFullPaths() }
    }

    @Test
    fun `a navigatable cannot sit in two columns of one size`() {
        assertFailsWith<IllegalArgumentException> {
            PaneLayout {
                expanded(column(inbox, message), column(message)) { _, _ -> }
            }
        }
    }

    @Test
    fun `compact windows cannot declare panes`() {
        assertFailsWith<IllegalArgumentException> {
            PaneLayout {
                at(WindowWidthClass.Compact, listOf(column(inbox), column(message))) { }
            }
        }
    }
}
