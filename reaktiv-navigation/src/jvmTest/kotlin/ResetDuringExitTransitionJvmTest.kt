import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.BackstackLifecycle
import io.github.syrou.reaktiv.navigation.definition.RemovalReason
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigateBack
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResetDuringExitTransitionJvmTest {

    private fun screen(
        screenRoute: String,
        enter: NavTransition = NavTransition.None,
        onLifecycle: (BackstackLifecycle) -> Unit = {}
    ) = object : Screen {
        override val route = screenRoute
        override val enterTransition = enter
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Text(screenRoute)
        }

        override suspend fun onLifecycleCreated(lifecycle: BackstackLifecycle) {
            onLifecycle(lifecycle)
        }
    }

    @Test
    fun `reset while a screen is still leaving is safe on a real dispatcher`() = runBlocking {
        repeat(100) { iteration ->
            val reasons = ConcurrentLinkedQueue<RemovalReason>()
            val home = screen("home")
            val detail = screen("detail", enter = NavTransition.Scale(durationMillis = 50)) { lifecycle ->
                lifecycle.invokeOnRemoval { reason -> reasons.add(reason) }
            }
            val store = createStore {
                module(createNavigationModule {
                    rootGraph {
                        start(home)
                        screens(home, detail)
                    }
                })
            }
            try {
                withTimeout(5_000) {
                    store.selectState<NavigationState>().first { !it.isBootstrapping }
                    launch {
                        try {
                            store.navigation { navigateTo("detail") }
                        } catch (_: CancellationException) {
                        }
                    }
                    store.selectState<NavigationState>().first { it.currentEntry.route == "detail" }
                    launch {
                        try {
                            store.navigateBack()
                        } catch (_: CancellationException) {
                        }
                    }
                    store.selectState<NavigationState>().first { it.currentEntry.route == "home" }
                    assertTrue(store.reset(), "iteration $iteration: reset should run")
                    store.selectState<NavigationState>().first { !it.isBootstrapping }
                }
                assertEquals(
                    1,
                    reasons.size,
                    "iteration $iteration: the leaving screen's removal handler must run exactly once, got $reasons"
                )
            } finally {
                store.cleanup()
            }
        }
    }
}
