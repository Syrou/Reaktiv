import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.syrou.reaktiv.core.DispatchInstrumentation
import io.github.syrou.reaktiv.core.Module
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.createStore
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@OptIn(ExperimentalCoroutinesApi::class)
class GuardTracingTest {

    private data class EvaluationStart(
        val scope: String,
        val name: String,
        val params: Map<String, String>,
        val token: String
    )

    private data class EvaluationCompletion(
        val token: String,
        val result: String?,
        val resultType: String
    )

    private data class EvaluationFailure(
        val token: String,
        val exceptionType: String,
        val exceptionMessage: String?
    )

    private class RecordingInstrumentation : DispatchInstrumentation {
        val started = mutableListOf<EvaluationStart>()
        val completed = mutableListOf<EvaluationCompletion>()
        val failed = mutableListOf<EvaluationFailure>()

        private var tokenCounter = 0

        override suspend fun onDispatchStarted(
            action: ModuleAction,
            queueWaitMs: Long,
            queueDepth: Long
        ): String = ""

        override fun onDispatchCompleted(token: String, applied: Boolean, durationMs: Long) = Unit

        override fun onDispatchFailed(token: String, error: Throwable, durationMs: Long) = Unit

        override suspend fun onDispatchDropped(action: ModuleAction) = Unit

        override suspend fun onExternalControlChanged(enabled: Boolean) = Unit

        override suspend fun onEvaluationStarted(
            scope: String,
            name: String,
            params: Map<String, String>
        ): String {
            val token = "eval-${tokenCounter++}"
            started.add(EvaluationStart(scope, name, params, token))
            return token
        }

        override fun onEvaluationCompleted(
            token: String,
            result: String?,
            resultType: String,
            durationMs: Long
        ) {
            completed.add(EvaluationCompletion(token, result, resultType))
        }

        override fun onEvaluationFailed(token: String, error: Throwable, durationMs: Long) {
            failed.add(
                EvaluationFailure(
                    token = token,
                    exceptionType = error::class.simpleName ?: "Unknown",
                    exceptionMessage = error.message
                )
            )
        }

        fun guardStarts() = started.filter { it.scope == "NavigationGuards" }

        fun guardCompletions() = completed.filter { completion ->
            guardStarts().any { it.token == completion.token }
        }

        fun guardFailures() = failed.filter { failure ->
            guardStarts().any { it.token == failure.token }
        }
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

            val instrumentation = RecordingInstrumentation()
            store.setDispatchInstrumentation(instrumentation)

            store.navigation { navigateTo("workspace/home") }
            advanceUntilIdle()

            val starts = instrumentation.guardStarts()
            assertEquals(1, starts.size)
            assertTrue(starts[0].name.startsWith("guard("))
            assertEquals("workspace/home", starts[0].params["target"])

            val completions = instrumentation.guardCompletions()
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
            val instrumentation = RecordingInstrumentation()
            store.setDispatchInstrumentation(instrumentation)

            store.navigation { navigateTo("workspace/home") }
            advanceUntilIdle()

            val completions = instrumentation.guardCompletions()
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
            val instrumentation = RecordingInstrumentation()
            store.setDispatchInstrumentation(instrumentation)

            store.navigation { navigateTo("workspace/home") }
            advanceUntilIdle()

            val completions = instrumentation.guardCompletions()
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
            val instrumentation = RecordingInstrumentation()
            store.setDispatchInstrumentation(instrumentation)

            runCatching { store.navigation { navigateTo("workspace/home") } }
            advanceUntilIdle()

            val failures = instrumentation.guardFailures()
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

            val instrumentation = RecordingInstrumentation()
            store.setDispatchInstrumentation(instrumentation)

            store.navigation { navigateTo("workspace/home") }
            advanceUntilIdle()

            val starts = instrumentation.guardStarts()
            assertEquals(2, starts.size)
            assertTrue(starts[0].name.startsWith("outerGuard[0]("))
            assertTrue(starts[1].name.startsWith("guard("))

            val completions = instrumentation.guardCompletions()
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

            val instrumentation = RecordingInstrumentation()
            store.setDispatchInstrumentation(instrumentation)

            store.navigation { navigateTo("workspace") }
            advanceUntilIdle()

            val starts = instrumentation.guardStarts()
            assertEquals(1, starts.size)
            assertEquals("entry(workspace)", starts[0].name)

            val completions = instrumentation.guardCompletions()
            assertEquals(1, completions.size)
            assertEquals("dashboard", completions[0].result)

            assertEquals("dashboard", store.selectState<NavigationState>().first().currentEntry.route)
        }

    @Test
    fun `no guard events are recorded without installed instrumentation`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val store = createStore {
                module(AuthModule)
                module(guardedModule { GuardResult.Allow })
                coroutineContext(dispatcher)
            }
            store.navigation { navigateTo("workspace/home") }
            advanceUntilIdle()

            val instrumentation = RecordingInstrumentation()
            store.setDispatchInstrumentation(instrumentation)
            assertEquals(0, instrumentation.guardStarts().size)
            assertEquals("home", store.selectState<NavigationState>().first().currentEntry.route)
        }
}
