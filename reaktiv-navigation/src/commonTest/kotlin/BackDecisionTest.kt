import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationModule
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.DismissAction
import io.github.syrou.reaktiv.navigation.definition.DismissSource
import io.github.syrou.reaktiv.navigation.definition.Dismissal
import io.github.syrou.reaktiv.navigation.definition.Graph
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import io.github.syrou.reaktiv.navigation.util.performUserBack
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class BackDecisionTest {

    private fun screen(route: String, dismissal: Dismissal = Dismissal.Default) = object : Screen {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None
        override val dismissal = dismissal

        @Composable
        override fun Content(params: Params) {
            Text(route)
        }
    }

    private var handlerRuns = 0

    private val homeScreen = screen("home")
    private val lockedScreen = screen("locked", Dismissal(back = DismissAction.Ignore))
    private val confirmScreen = screen("confirm", Dismissal(back = DismissAction.Run { handlerRuns++ }))
    private val sheetStart = screen("first")

    private object LockedSheet : Graph {
        override val route = "sheet"
        override val enterTransition = NavTransition.SlideUpBottom
        override val dismissal = Dismissal(back = DismissAction.Ignore, swipe = DismissAction.Pop)
    }

    private val navigationModule: NavigationModule = createNavigationModule {
        rootGraph {
            start(homeScreen)
            screens(homeScreen, lockedScreen, confirmScreen)
            graph(LockedSheet) {
                start(sheetStart)
            }
        }
    }

    private fun TestScope.store() = createStore {
        module(navigationModule)
        coroutineContext(StandardTestDispatcher(testScheduler))
    }

    private suspend fun io.github.syrou.reaktiv.core.Store.routes() =
        selectState<NavigationState>().first().backStack.map { it.route }

    @Test
    fun `a user back on a screen that ignores back leaves it in place`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            store.navigation { navigateTo("locked") }
            advanceUntilIdle()

            performUserBack(store, navigationModule, DismissSource.Back)
            advanceUntilIdle()

            assertEquals(listOf("home", "locked"), store.routes())
        }

    @Test
    fun `a user back on a screen with a back handler runs it instead of popping`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            store.navigation { navigateTo("confirm") }
            advanceUntilIdle()

            performUserBack(store, navigationModule, DismissSource.Back)
            advanceUntilIdle()

            assertEquals(listOf("home", "confirm"), store.routes())
            assertEquals(1, handlerRuns)
        }

    @Test
    fun `a user back that would leave a presented graph follows the graph's policy`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            store.navigation { navigateTo("sheet") }
            advanceUntilIdle()
            assertEquals(listOf("home", "first"), store.routes())

            performUserBack(store, navigationModule, DismissSource.Back)
            advanceUntilIdle()

            assertEquals(listOf("home", "first"), store.routes())
        }

    @Test
    fun `a programmatic back is not subject to the policy`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store()
            store.navigation { navigateTo("locked") }
            advanceUntilIdle()

            store.navigation { navigateBack() }
            advanceUntilIdle()

            assertTrue(store.routes() == listOf("home"))
        }
}
