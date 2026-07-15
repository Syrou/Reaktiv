import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.util.canArmInteractiveBackGesture
import io.github.syrou.reaktiv.navigation.util.canArmSwipeDismiss
import io.github.syrou.reaktiv.navigation.util.canHandleBack
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class BackGestureArmingTest {

    private val homeScreen = object : Screen {
        override val route = "home"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Text("Home")
        }
    }

    private val profileScreen = object : Screen {
        override val route = "profile"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Text("Profile")
        }
    }

    private val lockedScreen = object : Screen {
        override val route = "locked"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None
        override val backGestureEnabled = false

        @Composable
        override fun Content(params: Params) {
            Text("Locked")
        }
    }

    private val sheetScreen = object : Screen {
        override val route = "sheet"
        override val enterTransition = NavTransition.SlideUpBottom
        override val exitTransition = NavTransition.SlideOutBottom

        @Composable
        override fun Content(params: Params) {
            Text("Sheet")
        }
    }

    private val testModal = object : Modal {
        override val route = "test-modal"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Text("Modal")
        }
    }

    private fun createModule() = createNavigationModule {
        rootGraph {
            startScreen(homeScreen)
            screens(homeScreen, profileScreen, lockedScreen, sheetScreen)
            modals(testModal)
        }
    }

    @Test
    fun `gesture cannot arm at the root of the backstack`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val navModule = createModule()
            val store = createStore {
                module(navModule)
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceUntilIdle()
            val state = store.selectState<NavigationState>().first()
            assertFalse(canHandleBack(state, navModule))
            assertFalse(canArmInteractiveBackGesture(state, navModule))
        }

    @Test
    fun `gesture arms for a pushed content screen`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val navModule = createModule()
            val store = createStore {
                module(navModule)
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            store.navigation { navigateTo("profile") }
            advanceUntilIdle()
            val state = store.selectState<NavigationState>().first()
            assertTrue(canHandleBack(state, navModule))
            assertTrue(canArmInteractiveBackGesture(state, navModule))
        }

    @Test
    fun `gesture cannot arm while a modal is on top`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val navModule = createModule()
            val store = createStore {
                module(navModule)
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            store.navigation { navigateTo("test-modal") }
            advanceUntilIdle()
            val state = store.selectState<NavigationState>().first()
            assertTrue(canHandleBack(state, navModule))
            assertFalse(canArmInteractiveBackGesture(state, navModule))
        }

    @Test
    fun `gesture cannot arm when the top screen opts out`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val navModule = createModule()
            val store = createStore {
                module(navModule)
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            store.navigation { navigateTo("locked") }
            advanceUntilIdle()
            val state = store.selectState<NavigationState>().first()
            assertTrue(canHandleBack(state, navModule))
            assertFalse(canArmInteractiveBackGesture(state, navModule))
        }

    @Test
    fun `gesture cannot arm while navigation is being evaluated`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val navModule = createModule()
            val store = createStore {
                module(navModule)
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            store.navigation { navigateTo("profile") }
            advanceUntilIdle()
            store.dispatch(NavigationAction.SetEvaluating(true))
            advanceUntilIdle()
            val state = store.selectState<NavigationState>().first()
            assertFalse(canHandleBack(state, navModule))
            assertFalse(canArmInteractiveBackGesture(state, navModule))
        }

    @Test
    fun `gesture cannot arm while bootstrapping`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val navModule = createModule()
            val store = createStore {
                module(navModule)
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            store.navigation { navigateTo("profile") }
            advanceUntilIdle()
            val state = store.selectState<NavigationState>().first().copy(isBootstrapping = true)
            assertFalse(canHandleBack(state, navModule))
            assertFalse(canArmInteractiveBackGesture(state, navModule))
        }

    @Test
    fun `edge gesture cannot arm on a vertically presented screen`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val navModule = createModule()
            val store = createStore {
                module(navModule)
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            store.navigation { navigateTo("sheet") }
            advanceUntilIdle()
            val state = store.selectState<NavigationState>().first()
            assertTrue(canHandleBack(state, navModule))
            assertFalse(canArmInteractiveBackGesture(state, navModule))
            assertTrue(canArmSwipeDismiss(state, navModule))
        }

    @Test
    fun `gesture cannot arm when the revealed screen would restore a modal`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val navModule = createModule()
            val store = createStore {
                module(navModule)
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            store.navigation { navigateTo("test-modal") }
            advanceUntilIdle()
            store.navigation { navigateTo("profile") }
            advanceUntilIdle()
            val state = store.selectState<NavigationState>().first()
            assertTrue(state.activeModalContexts.isNotEmpty())
            assertFalse(canArmInteractiveBackGesture(state, navModule))
        }
}
