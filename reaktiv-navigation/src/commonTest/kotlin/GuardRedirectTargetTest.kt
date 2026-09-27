import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.model.GuardResult
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
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class GuardRedirectTargetTest {

    private fun screen(route: String) = object : Screen {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Text(route)
        }
    }

    private val rootLogin = screen("login")
    private val accountLogin = screen("login")
    private val profile = screen("profile")

    private fun TestScope.store(guard: GuardResult) = createStore {
        module(
            createNavigationModule {
                rootGraph {
                    start(SplashScreen)
                    screens(rootLogin)
                    graph("account") {
                        start(accountLogin)
                    }
                    intercept(guard = { guard }) {
                        screens(profile)
                    }
                }
            }
        )
        coroutineContext(StandardTestDispatcher(testScheduler))
    }

    @Test
    fun `a redirect to a screen object lands on that screen even when its short route is shared`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store(GuardResult.RedirectTo(accountLogin))

            store.navigation { navigateTo("profile") }
            advanceUntilIdle()

            assertEquals("account/login", store.selectState<NavigationState>().first().currentEntry.path)
        }

    @Test
    fun `a pending redirect to a screen object lands on that screen even when its short route is shared`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store(GuardResult.PendAndRedirectTo(accountLogin))

            store.navigation { navigateTo("profile") }
            advanceUntilIdle()

            val state = store.selectState<NavigationState>().first()
            assertEquals("account/login", state.currentEntry.path)
            assertEquals("profile", state.pendingNavigation?.route)
        }
}
