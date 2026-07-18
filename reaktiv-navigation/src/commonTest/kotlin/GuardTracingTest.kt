import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.Module
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.tracing.LogicMethodCompleted
import io.github.syrou.reaktiv.core.tracing.LogicMethodFailed
import io.github.syrou.reaktiv.core.tracing.LogicMethodStart
import io.github.syrou.reaktiv.core.tracing.LogicObserver
import io.github.syrou.reaktiv.core.tracing.LogicTracer
import io.github.syrou.reaktiv.core.util.selectState
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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class GuardTracingTest {

    private class RecordingObserver : LogicObserver {
        val started = mutableListOf<LogicMethodStart>()
        val completed = mutableListOf<LogicMethodCompleted>()
        val failed = mutableListOf<LogicMethodFailed>()

        override fun onMethodStart(event: LogicMethodStart) {
            started.add(event)
        }

        override fun onMethodCompleted(event: LogicMethodCompleted) {
            completed.add(event)
        }

        override fun onMethodFailed(event: LogicMethodFailed) {
            failed.add(event)
        }

        fun guardStarts() = started.filter { it.logicClass == "NavigationGuards" }

        fun guardCompletions() = completed.filter { completion ->
            guardStarts().any { it.callId == completion.callId }
        }

        fun guardFailures() = failed.filter { failure ->
            guardStarts().any { it.callId == failure.callId }
        }
    }

    @AfterTest
    fun tearDown() {
        LogicTracer.clearObservers()
    }

    private fun screen(route: String) = object : Screen {
        override val route = route
        override val enterTransition = NavTransition.None
        override val exitTransition = NavTransition.None

        @Composable
        override fun Content(params: Params) { Text(route) }
    }

    private val startScreen = screen("start")
    private val homeScreen = screen("home")
    private val loginScreen = screen("login")
    private val dashboardScreen = screen("dashboard")

    @Serializable
    data class AuthState(
        val isAuthenticated: Boolean = false,
        val startupReady: Boolean = false
    ) : ModuleState

    sealed class AuthAction(tag: kotlin.reflect.KClass<*>) : ModuleAction(tag) {
        data object Login : AuthAction(AuthModule::class)
        data object StartupReady : AuthAction(AuthModule::class)
    }

    object AuthModule : Module<AuthState, AuthAction> {
        override val initialState = AuthState()
        override val reducer: (AuthState, AuthAction) -> AuthState = { state, action ->
            when (action) {
                AuthAction.Login -> state.copy(isAuthenticated = true)
                AuthAction.StartupReady -> state.copy(startupReady = true)
            }
        }
        override val createLogic: (StoreAccessor) -> ModuleLogic =
            { object : ModuleLogic() {} }
    }

    private fun guardedModule(guard: suspend (StoreAccessor) -> GuardResult) = createNavigationModule {
        rootGraph {
            start(startScreen)
            screens(startScreen, loginScreen)
            intercept(guard = guard) {
                graph("workspace") {
                    start(homeScreen)
                    screens(homeScreen)
                }
            }
        }
    }

    @Test
    fun `allowed guard evaluation is traced with result Allow`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(AuthModule)
                module(guardedModule { store ->
                    if (store.selectState<AuthState>().first().isAuthenticated) GuardResult.Allow
                    else GuardResult.Reject
                })
                coroutineContext(dispatcher)
            }
            store.dispatch(AuthAction.Login)
            advanceUntilIdle()

            val observer = RecordingObserver()
            LogicTracer.addObserver(observer)

            store.navigation { navigateTo("workspace/home") }
            advanceUntilIdle()

            val starts = observer.guardStarts()
            assertEquals(1, starts.size)
            assertTrue(starts[0].methodName.startsWith("guard("))
            assertEquals("workspace/home", starts[0].params["target"])

            val completions = observer.guardCompletions()
            assertEquals(1, completions.size)
            assertEquals("Allow", completions[0].result)
            assertEquals("GuardResult", completions[0].resultType)

            assertEquals("home", store.selectState<NavigationState>().first().currentEntry.route)
        }

    @Test
    fun `rejected guard evaluation records the Reject decision`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(AuthModule)
                module(guardedModule { store ->
                    if (store.selectState<AuthState>().first().isAuthenticated) GuardResult.Allow
                    else GuardResult.Reject
                })
                coroutineContext(dispatcher)
            }
            val observer = RecordingObserver()
            LogicTracer.addObserver(observer)

            store.navigation { navigateTo("workspace/home") }
            advanceUntilIdle()

            val completions = observer.guardCompletions()
            assertEquals(1, completions.size)
            assertEquals("Reject", completions[0].result)
            assertEquals("start", store.selectState<NavigationState>().first().currentEntry.route)
        }

    @Test
    fun `redirecting guard evaluation records the redirect target`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(AuthModule)
                module(guardedModule { store ->
                    if (store.selectState<AuthState>().first().isAuthenticated) GuardResult.Allow
                    else GuardResult.RedirectTo(loginScreen)
                })
                coroutineContext(dispatcher)
            }
            val observer = RecordingObserver()
            LogicTracer.addObserver(observer)

            store.navigation { navigateTo("workspace/home") }
            advanceUntilIdle()

            val completions = observer.guardCompletions()
            assertEquals(1, completions.size)
            assertEquals("RedirectTo(login)", completions[0].result)
            assertEquals("login", store.selectState<NavigationState>().first().currentEntry.route)
        }

    @Test
    fun `throwing guard is reported as a failed evaluation`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(AuthModule)
                module(guardedModule { throw IllegalStateException("guard exploded") })
                coroutineContext(dispatcher)
            }
            val observer = RecordingObserver()
            LogicTracer.addObserver(observer)

            runCatching { store.navigation { navigateTo("workspace/home") } }
            advanceUntilIdle()

            val failures = observer.guardFailures()
            assertEquals(1, failures.size)
            assertEquals("IllegalStateException", failures[0].exceptionType)
            assertEquals("guard exploded", failures[0].exceptionMessage)
        }

    @Test
    fun `nested intercepts trace the outer guard before the primary guard`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val navModule = createNavigationModule {
                rootGraph {
                    start(startScreen)
                    screens(startScreen, loginScreen)
                    intercept(guard = { store ->
                        if (store.selectState<AuthState>().first().startupReady) GuardResult.Allow
                        else GuardResult.Reject
                    }) {
                        intercept(guard = { store ->
                            if (store.selectState<AuthState>().first().isAuthenticated) GuardResult.Allow
                            else GuardResult.RedirectTo(loginScreen)
                        }) {
                            graph("workspace") {
                                start(homeScreen)
                                screens(homeScreen)
                            }
                        }
                    }
                }
            }
            val store = createStore {
                module(AuthModule)
                module(navModule)
                coroutineContext(dispatcher)
            }
            store.dispatch(AuthAction.Login)
            store.dispatch(AuthAction.StartupReady)
            advanceUntilIdle()

            val observer = RecordingObserver()
            LogicTracer.addObserver(observer)

            store.navigation { navigateTo("workspace/home") }
            advanceUntilIdle()

            val starts = observer.guardStarts()
            assertEquals(2, starts.size)
            assertTrue(starts[0].methodName.startsWith("outerGuard[0]("))
            assertTrue(starts[1].methodName.startsWith("guard("))

            val completions = observer.guardCompletions()
            assertEquals(2, completions.size)
            assertTrue(completions.all { it.result == "Allow" })
        }

    @Test
    fun `dynamic entry selection is traced with the resolved route`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val navModule = createNavigationModule {
                rootGraph {
                    start(startScreen)
                    screens(startScreen)
                    graph("workspace") {
                        start(route = { _ -> dashboardScreen })
                        screens(dashboardScreen)
                    }
                }
            }
            val store = createStore {
                module(AuthModule)
                module(navModule)
                coroutineContext(dispatcher)
            }
            advanceUntilIdle()

            val observer = RecordingObserver()
            LogicTracer.addObserver(observer)

            store.navigation { navigateTo("workspace") }
            advanceUntilIdle()

            val starts = observer.guardStarts()
            assertEquals(1, starts.size)
            assertEquals("entry(workspace)", starts[0].methodName)

            val completions = observer.guardCompletions()
            assertEquals(1, completions.size)
            assertEquals("dashboard", completions[0].result)

            assertEquals("dashboard", store.selectState<NavigationState>().first().currentEntry.route)
        }

    @Test
    fun `no guard events are recorded without a registered observer`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(AuthModule)
                module(guardedModule { GuardResult.Allow })
                coroutineContext(dispatcher)
            }
            store.navigation { navigateTo("workspace/home") }
            advanceUntilIdle()

            val observer = RecordingObserver()
            LogicTracer.addObserver(observer)
            assertEquals(0, observer.guardStarts().size)
            assertEquals("home", store.selectState<NavigationState>().first().currentEntry.route)
        }
}
