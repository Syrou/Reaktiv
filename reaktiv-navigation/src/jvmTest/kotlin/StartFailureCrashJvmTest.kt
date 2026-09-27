import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.CrashRecovery
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.LoadingModal
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class StartFailureCrashJvmTest {

    private fun screen(route: String) = object : Screen {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Text(route)
        }
    }

    private val loading = object : LoadingModal {
        override val route = "loading"
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Text(route)
        }
    }

    private val home = screen("home")

    private fun storeWith(recovery: CrashRecovery, handled: AtomicInteger): Store = createStore {
        module(createNavigationModule {
            loadingModal(loading)
            crashScreen(screen("crashed")) { _, _ ->
                handled.incrementAndGet()
                recovery
            }
            rootGraph {
                start(route = { _ -> throw IllegalStateException("no destination") })
                screens(home)
            }
        })
        coroutineContext(Dispatchers.Default)
    }

    private suspend fun Store.nav(): NavigationState = selectState<NavigationState>().first()

    @Test
    fun `a failed start reaches the crash screen through one crash handler call`() = runBlocking<Unit> {
        val handled = AtomicInteger(0)
        val store = storeWith(CrashRecovery.NAVIGATE_TO_CRASH_SCREEN, handled)

        withTimeout(5_000) { store.selectState<NavigationState>().first { it.currentEntry.route == "crashed" } }
        delay(300)

        assertEquals(1, handled.get())
        assertEquals(listOf("crashed"), store.nav().backStack.map { it.route })
        assertEquals("no destination", store.nav().startFailure?.exceptionMessage)
    }
}
