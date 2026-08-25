import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.LoadingModal
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigateDeepLink
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class DeepLinkWithSystemAlertTest {

    private fun screen(name: String) = object : Screen {
        override val route = name
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Text(name)
        }
    }

    private val splash = screen("splash")
    private val workspaceHome = screen("home")
    private val workspaceDetail = screen("detail")

    private val loadingScreen = object : LoadingModal {
        override val route = "loading"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Text("loading")
        }
    }

    private val systemAlert = object : Modal {
        override val route = "system-alert"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None
        override val renderLayer = RenderLayer.SYSTEM

        @Composable
        override fun Content(params: Params) {
            Text("Alert")
        }
    }

    private fun createModule() = createNavigationModule {
        loadingModal(loadingScreen)
        rootGraph {
            start(splash)
            screens(splash)
            modals(systemAlert)
            graph("workspace") {
                start(workspaceHome)
                screens(workspaceHome, workspaceDetail)
            }
        }
    }

    @Test
    fun a_deep_link_synthesizes_its_history_underneath_a_raised_alert() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val store = createStore {
                module(createModule())
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceUntilIdle()
            store.navigation { navigateTo("system-alert") }
            advanceUntilIdle()

            store.navigateDeepLink("workspace/detail")
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals(
                listOf("splash", "home", "detail"),
                state.backStack.filter { it.navigatable.renderLayer != RenderLayer.SYSTEM }
                    .map { it.route },
                "the synthesized history is exactly what it would be with no alert raised"
            )
            assertTrue(
                state.systemLayerEntries.any { it.route == "system-alert" },
                "the alert outlives the clear that synthesis performs"
            )
            assertEquals(
                "system-alert",
                state.currentEntry.route,
                "and it stays on top of the route that was linked to"
            )
        }

    @Test
    fun a_deep_link_with_no_alert_raised_is_completely_unaffected() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val store = createStore {
                module(createModule())
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceUntilIdle()

            store.navigateDeepLink("workspace/detail")
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("detail", state.currentEntry.route)
            assertEquals(3, state.backStack.size)
            assertEquals(listOf("splash", "home", "detail"), state.backStack.map { it.route })
        }

    @Test
    fun the_loading_placeholder_never_survives_synthesis() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            val store = createStore {
                module(createModule())
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceUntilIdle()
            store.navigation { navigateTo("system-alert") }
            advanceUntilIdle()

            store.navigateDeepLink("workspace/detail")
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertTrue(
                state.backStack.none { it.navigatable is LoadingModal },
                "keeping app overlays must not also keep navigation's own placeholder"
            )
        }
}
