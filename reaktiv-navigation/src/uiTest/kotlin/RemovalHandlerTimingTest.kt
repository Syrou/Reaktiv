import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilExactlyOneExists
import io.github.syrou.reaktiv.compose.StoreProvider
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.BackstackLifecycle
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigateBack
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.ui.NavigationRender
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

class RemovalHandlerTimingTest {

    private fun homeScreen() = object : Screen {
        override val route = "timing-home"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) { Text(route) }
    }

    @Test
    fun `removal handlers fire only after the pop exit animation duration`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            var removedAtMs = -1L
            val detailScreen = object : Screen {
                override val route = "timing-detail"
                override val enterTransition = NavTransition.SlideInRight
                override val exitTransition = NavTransition.None

                override suspend fun onLifecycleCreated(lifecycle: BackstackLifecycle) {
                    lifecycle.invokeOnRemoval {
                        removedAtMs = testScheduler.currentTime
                    }
                }

                @Composable
                override fun Content(params: Params) { Text(route) }
            }
            val home = homeScreen()
            val store = createStore {
                module(createNavigationModule {
                    rootGraph {
                        start(home)
                        screens(home, detailScreen)
                    }
                })
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceUntilIdle()

            store.navigation { navigateTo("timing-detail") }
            advanceUntilIdle()

            val backStartedAtMs = testScheduler.currentTime
            store.navigateBack()
            advanceUntilIdle()

            assertTrue(removedAtMs >= 0, "removal handler should have run")
            assertTrue(
                removedAtMs - backStartedAtMs >= NavTransition.DEFAULT_ANIMATION_DURATION,
                "handler ran ${removedAtMs - backStartedAtMs}ms after back, expected at least " +
                    "${NavTransition.DEFAULT_ANIMATION_DURATION}ms (the pop exit duration)"
            )
        }

    @Test
    fun `transitionless screens run removal handlers without delay`() =
        runTest(timeout = 10.toDuration(DurationUnit.SECONDS)) {
            var removedAtMs = -1L
            val detailScreen = object : Screen {
                override val route = "timing-plain-detail"
                override val enterTransition = NavTransition.None
                override val exitTransition = NavTransition.None

                override suspend fun onLifecycleCreated(lifecycle: BackstackLifecycle) {
                    lifecycle.invokeOnRemoval {
                        removedAtMs = testScheduler.currentTime
                    }
                }

                @Composable
                override fun Content(params: Params) { Text(route) }
            }
            val home = homeScreen()
            val store = createStore {
                module(createNavigationModule {
                    rootGraph {
                        start(home)
                        screens(home, detailScreen)
                    }
                })
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceUntilIdle()

            store.navigation { navigateTo("timing-plain-detail") }
            advanceUntilIdle()

            val backStartedAtMs = testScheduler.currentTime
            store.navigateBack()
            advanceUntilIdle()

            assertTrue(removedAtMs >= 0, "removal handler should have run")
            assertEquals(backStartedAtMs, removedAtMs)
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun removalHandlerFiresOnlyAfterExitingScreenLeftComposition() = runComposeUiTest {
        var handlerFired = false
        val detailScreen = object : Screen {
            override val route = "timing-ui-detail"
            override val enterTransition = NavTransition.Custom(
                durationMillis = 2000,
                translationX = { progress -> (1f - progress) * 800f }
            )
            override val exitTransition = NavTransition.None

            override suspend fun onLifecycleCreated(lifecycle: BackstackLifecycle) {
                lifecycle.invokeOnRemoval {
                    handlerFired = true
                }
            }

            @Composable
            override fun Content(params: Params) { Text("Timing Detail") }
        }
        val home = object : Screen {
            override val route = "timing-ui-home"
            override val enterTransition = NavTransition.None
            override val exitTransition = NavTransition.None

            @Composable
            override fun Content(params: Params) { Text("Timing Home") }
        }
        val store = createStore {
            module(createNavigationModule {
                rootGraph {
                    start(home)
                    screens(home, detailScreen)
                }
            })
        }
        setContent {
            StoreProvider(store) {
                NavigationRender()
            }
        }
        waitUntilExactlyOneExists(hasText("Timing Home"), timeoutMillis = UI_TEST_WAIT_MS)
        store.launch { store.navigation { navigateTo("timing-ui-detail") } }
        awaitCurrentScreen(store, "timing-ui-detail")
        waitUntilExactlyOneExists(hasText("Timing Detail"), timeoutMillis = UI_TEST_WAIT_MS)

        store.launch { store.navigateBack() }
        awaitCurrentScreen(store, "timing-ui-home")

        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) {
            onAllNodesWithText("Timing Detail").fetchSemanticsNodes().isEmpty()
        }
        assertFalse(
            handlerFired,
            "removal handler fired before the exiting screen had left composition"
        )

        waitUntil(timeoutMillis = UI_TEST_WAIT_MS) { handlerFired }
    }
}
