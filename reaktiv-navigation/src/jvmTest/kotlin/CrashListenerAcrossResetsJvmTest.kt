import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.CrashRecovery
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class CrashListenerAcrossResetsJvmTest {

    private fun screen(route: String) = object : Screen {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) {
            Text(route)
        }
    }

    @Test
    fun `one crash runs the crash handler once no matter how often the store was reset`() = runBlocking<Unit> {
        val crashHandled = AtomicInteger(0)
        val firstCrash = CompletableDeferred<Unit>()
        val home = screen("home")
        val store = createStore {
            module(createNavigationModule {
                crashScreen(screen("crashed")) { _, _ ->
                    crashHandled.incrementAndGet()
                    firstCrash.complete(Unit)
                    CrashRecovery.NAVIGATE_TO_CRASH_SCREEN
                }
                rootGraph {
                    start(home)
                    screens(home)
                }
            })
            coroutineContext(Dispatchers.Default)
        }
        withTimeout(5_000) { store.initialized.first { it } }
        store.reset()
        store.reset()

        store.launch { throw IllegalStateException("boom") }
        withTimeout(5_000) { firstCrash.await() }
        delay(300)

        assertEquals(1, crashHandled.get())
        store.cleanup()
    }
}
