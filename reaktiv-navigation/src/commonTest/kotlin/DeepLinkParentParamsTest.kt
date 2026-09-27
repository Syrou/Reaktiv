import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigateDeepLink
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
class DeepLinkParentParamsTest {

    private fun screen(route: String) = object : Screen {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Text(route)
        }
    }

    private val viewUser = screen("user/{id}")
    private val editUser = screen("user/{id}/edit")

    @Test
    fun `a parent synthesized under a deep link receives the link's values`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = createStore {
                module(
                    createNavigationModule {
                        rootGraph {
                            start(SplashScreen)
                            screens(viewUser, editUser)
                        }
                    }
                )
                coroutineContext(StandardTestDispatcher(testScheduler))
            }

            store.navigateDeepLink("user/42/edit")
            advanceUntilIdle()

            val backStack = store.selectState<NavigationState>().first().backStack
            assertEquals(listOf("splash", "user/42", "user/42/edit"), backStack.map { it.location })
            assertEquals("42", backStack[1].params.getString("id"))
        }
}
