import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.core.util.ReaktivLogSink
import io.github.syrou.reaktiv.navigation.createNavigationModule
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.extension.navigation
import io.github.syrou.reaktiv.navigation.model.GuardResult
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.transition.NavTransition
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationLoggingTest {

    private val lines = mutableListOf<String>()
    private val sink = ReaktivLogSink { level, category, message ->
        lines.add("$level|$category|$message")
    }

    @BeforeTest
    fun install() {
        lines.clear()
        ReaktivDebug.enable()
        ReaktivDebug.addSink(sink)
    }

    @AfterTest
    fun remove() {
        ReaktivDebug.removeSink(sink)
        ReaktivDebug.disable()
    }

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
    private val secret = screen("secret")

    @Test
    fun navigating_to_the_screen_you_are_already_on_is_logged_as_skipped() =
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
            lines.clear()

            store.navigation { navigateTo("home") }
            advanceUntilIdle()

            assertTrue(
                lines.any { it.contains("skipped") && it.contains("home") },
                "a navigation that changes nothing must say so rather than look like it worked: $lines"
            )
        }

    @Test
    fun a_navigation_rejected_by_a_guard_is_logged_with_its_outcome() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = createStore {
                module(createNavigationModule {
                    rootGraph {
                        start(home)
                        screens(home)
                        intercept(guard = { GuardResult.Reject }) {
                            graph("secure") {
                                start(secret)
                                screens(secret)
                            }
                        }
                    }
                })
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceUntilIdle()
            lines.clear()

            store.navigation { navigateTo("secure/secret") }
            advanceUntilIdle()

            assertTrue(
                lines.any { it.contains("Rejected") },
                "the transaction records how it ended, not only whether a guard ran: $lines"
            )
        }

    @Test
    fun an_ordinary_navigation_still_records_its_outcome() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = createStore {
                module(createNavigationModule {
                    rootGraph {
                        start(home)
                        screens(home, secret)
                    }
                })
                coroutineContext(StandardTestDispatcher(testScheduler))
            }
            advanceUntilIdle()
            lines.clear()

            store.navigation { navigateTo("secret") }
            advanceUntilIdle()

            assertTrue(
                lines.any { it.contains("Success") },
                "so a log read end to end shows every attempt and how each one finished: $lines"
            )
        }
}
