import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.selectLogic
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.NavigationLogic
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.ScrubState
import io.github.syrou.reaktiv.navigation.ScrubType
import io.github.syrou.reaktiv.navigation.TraverseDirection
import io.github.syrou.reaktiv.navigation.TraversePresentation
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.BackstackLifecycle
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.util.impliesBackNavigation
import io.github.syrou.reaktiv.navigation.util.wasAlreadyPresented
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class TraverseTest {

    private val created = mutableMapOf<String, Int>()
    private val removed = mutableListOf<String>()

    private fun screen(route: String) = object : Screen {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        override suspend fun onLifecycleCreated(lifecycle: BackstackLifecycle) {
            created[route] = (created[route] ?: 0) + 1
            lifecycle.invokeOnRemoval { removed += route }
        }

        @Composable
        override fun Content(params: Params) {
            Text(route)
        }
    }

    private val alert = object : Modal {
        override val route = "alert"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None
        override val renderLayer = RenderLayer.SYSTEM

        @Composable
        override fun Content(params: Params) {
            Text("alert")
        }
    }

    private val home = screen("home")
    private val first = screen("first")
    private val second = screen("second")

    private fun TestScope.store() = createStore {
        module(
            createNavigationModule {
                rootGraph {
                    start(home)
                    screens(home, first, second)
                    modals(alert)
                }
            }
        )
        coroutineContext(StandardTestDispatcher(testScheduler))
    }

    @Test
    fun `a traverse sets the stack and keeps a system entry on top`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            store.navigation { navigateTo("first") }
            store.navigation { navigateTo("alert") }
            advanceUntilIdle()
            val homeEntry = store.selectState<NavigationState>().first().backStack.first()
            val secondEntry = homeEntry.copy(navigatable = second, path = "second")

            val landed = store.selectLogic<NavigationLogic>().commitTraverse(
                listOf(homeEntry, secondEntry), TraverseDirection.Forward, TraversePresentation.Animate, null
            )
            advanceUntilIdle()

            assertTrue(landed)
            assertEquals(
                listOf("home", "second", "alert"),
                store.selectState<NavigationState>().first().backStack.map { it.route }
            )
        }

    @Test
    fun `a traverse whose expected top is no longer on top changes nothing`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            store.navigation { navigateTo("first") }
            advanceUntilIdle()
            val homeEntry = store.selectState<NavigationState>().first().backStack.first()

            val landed = store.selectLogic<NavigationLogic>().commitTraverse(
                listOf(homeEntry), TraverseDirection.Back, TraversePresentation.Animate, "not-the-top"
            )
            advanceUntilIdle()

            assertFalse(landed)
            assertEquals(listOf("home", "first"), store.selectState<NavigationState>().first().backStack.map { it.route })
        }

    @Test
    fun `entries a traverse keeps keep their lifecycle and only the dropped ones are removed`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            store.navigation { navigateTo("first") }
            store.navigation { navigateTo("second") }
            advanceUntilIdle()
            val kept = store.selectState<NavigationState>().first().backStack.take(2)

            store.selectLogic<NavigationLogic>().commitTraverse(
                kept, TraverseDirection.Back, TraversePresentation.Animate, null
            )
            advanceUntilIdle()

            assertEquals(listOf("second"), removed)
            assertEquals(1, created["home"])
            assertEquals(1, created["first"])
        }

    @Test
    fun `direction and presentation reach the renderer and a traverse clears a scrub`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            store.navigation { navigateTo("first") }
            advanceUntilIdle()
            store.dispatch(NavigationAction.ScrubUpdate(ScrubState(ScrubType.Back, "first", "home", 0.4f)))
            advanceUntilIdle()
            val homeEntry = store.selectState<NavigationState>().first().backStack.first()

            store.selectLogic<NavigationLogic>().commitTraverse(
                listOf(homeEntry), TraverseDirection.Back, TraversePresentation.AlreadyPresented, null
            )
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertTrue(state.lastNavigationAction.impliesBackNavigation())
            assertTrue(state.lastNavigationAction.wasAlreadyPresented())
            assertNull(state.activeScrub)
        }
}
