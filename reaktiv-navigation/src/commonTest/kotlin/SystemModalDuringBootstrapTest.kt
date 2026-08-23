import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.LoadingModal
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigateBack
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * A system-layer navigatable is meant to reach the screen above anything else, including while the
 * app is still deciding where to start.
 *
 * Bootstrap holds the navigation lock for the whole of its start-destination evaluation, so a slow
 * auth guard used to leave an alert raised during it waiting for the very thing it was supposed to
 * cover. Skipping the bootstrap await was not enough on its own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SystemModalDuringBootstrapTest {

    private val home = object : Screen {
        override val route = "home"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Text("Home")
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

    private val plainModal = object : Modal {
        override val route = "plain-modal"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Text("Plain")
        }
    }

    /** Covers the screen while the start destination is resolved, as a real app would. */
    private val loadingScreen = object : LoadingModal {
        override val route = "loading"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Text("loading")
        }
    }

    /** Start resolution takes a while, standing in for an auth guard. */
    private fun slowModule() = createNavigationModule {
        loadingModal(loadingScreen)
        rootGraph {
            start(route = { _ ->
                delay(5_000)
                home
            })
            screens(home)
            modals(systemAlert, plainModal)
        }
    }

    @Test
    fun a_system_modal_appears_while_bootstrap_is_still_resolving() =
        runTest(timeout = 20.toDuration(DurationUnit.SECONDS)) {
            val navModule = slowModule()
            val store = createStore {
                module(navModule)
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceTimeBy(1_000)

            launch { store.navigation { navigateTo("system-alert") } }
            advanceTimeBy(1_000)

            val duringBootstrap = store.selectState<NavigationState>().first()
            assertTrue(
                duringBootstrap.systemLayerEntries.any { it.navigatable.route == "system-alert" },
                "the alert must reach the system layer without waiting for the start lambda"
            )
            assertTrue(
                duringBootstrap.isBootstrapping,
                "bootstrap should still be running, otherwise this proves nothing"
            )
        }

    @Test
    fun a_normal_modal_still_waits_for_bootstrap() =
        runTest(timeout = 20.toDuration(DurationUnit.SECONDS)) {
            val navModule = slowModule()
            val store = createStore {
                module(navModule)
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceTimeBy(1_000)

            launch { store.navigation { navigateTo("plain-modal") } }
            advanceTimeBy(1_000)

            val duringBootstrap = store.selectState<NavigationState>().first()
            assertTrue(
                duringBootstrap.globalOverlayEntries.none { it.navigatable.route == "plain-modal" },
                "only the system layer bypasses bootstrap"
            )
        }

    @Test
    fun bootstrap_still_completes_and_reaches_the_start_destination() =
        runTest(timeout = 20.toDuration(DurationUnit.SECONDS)) {
            val navModule = slowModule()
            val store = createStore {
                module(navModule)
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceTimeBy(1_000)
            launch { store.navigation { navigateTo("system-alert") } }
            advanceUntilIdle()

            val settled = store.selectState<NavigationState>().first()
            assertEquals(
                listOf("home"),
                settled.contentLayerEntries.map { it.navigatable.route },
                "raising an alert mid-bootstrap must not derail where the app starts"
            )
            assertEquals(
                "system-alert",
                settled.currentEntry.navigatable.route,
                "the alert stays on top of the start destination rather than being replaced by it"
            )
        }

    @Test
    fun the_alert_outlives_the_loader_it_was_raised_over() =
        runTest(timeout = 20.toDuration(DurationUnit.SECONDS)) {
            val navModule = slowModule()
            val store = createStore {
                module(navModule)
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceTimeBy(1_000)
            launch { store.navigation { navigateTo("system-alert") } }
            advanceUntilIdle()

            val settled = store.selectState<NavigationState>().first()
            assertTrue(
                settled.systemLayerEntries.any { it.navigatable.route == "system-alert" },
                "the alert is the user's to dismiss, so finishing the start destination must not " +
                    "take it away with the loader"
            )
        }

    @Test
    fun the_alert_goes_away_when_it_is_dismissed() =
        runTest(timeout = 20.toDuration(DurationUnit.SECONDS)) {
            val navModule = slowModule()
            val store = createStore {
                module(navModule)
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceTimeBy(1_000)
            launch { store.navigation { navigateTo("system-alert") } }
            advanceUntilIdle()

            store.navigateBack()
            advanceUntilIdle()

            val afterDismiss = store.selectState<NavigationState>().first()
            assertTrue(
                afterDismiss.systemLayerEntries.none { it.navigatable.route == "system-alert" },
                "dismissing is what removes it"
            )
            assertEquals(
                "home",
                afterDismiss.currentEntry.navigatable.route,
                "and dismissing it reveals where bootstrap landed"
            )
        }
}
