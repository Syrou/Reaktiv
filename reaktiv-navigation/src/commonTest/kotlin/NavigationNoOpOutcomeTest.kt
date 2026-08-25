import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.selectLogic
import io.github.syrou.reaktiv.navigation.NavigationLogic
import io.github.syrou.reaktiv.navigation.NavigationOutcome
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationNoOpOutcomeTest {

    private fun screen(name: String) = object : Screen {
        override val route = name
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Text(name)
        }
    }

    private val home = screen("home")

    @Test
    fun navigating_to_the_screen_you_are_already_on_reports_success() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = createStore {
                module(createNavigationModule {
                    rootGraph {
                        start(home)
                        screens(home)
                    }
                })
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceUntilIdle()
            val before = store.selectState<NavigationState>().first().backStack.map { it.route }

            val outcome = store.selectLogic<NavigationLogic>().navigate { navigateTo("home") }
            advanceUntilIdle()

            val after = store.selectState<NavigationState>().first().backStack.map { it.route }
            assertEquals(before, after, "the stack is untouched, so nothing actually happened")
            assertEquals(
                NavigationOutcome.Success,
                outcome,
                "yet the caller is told it succeeded, with no signal that the step was skipped"
            )
        }
}
