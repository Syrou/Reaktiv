package io.github.syrou.reaktiv.test

import io.github.syrou.reaktiv.core.Middleware
import io.github.syrou.reaktiv.core.Module
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.Store
import io.github.syrou.reaktiv.core.StoreDSL
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.selectState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

public class ReaktivTestScope internal constructor(
    public val testScope: TestScope,
    public val store: Store,
    private val recorded: MutableList<ModuleAction>
) {

    public val dispatchedActions: List<ModuleAction> get() = recorded.toList()

    public fun settle() {
        testScope.advanceUntilIdle()
    }

    public fun advanceTimeBy(duration: Duration) {
        testScope.advanceTimeBy(duration)
        testScope.runCurrent()
    }

    public fun dispatch(action: ModuleAction) {
        store.dispatch(action)
        settle()
    }

    public suspend inline fun <reified S : ModuleState> currentState(): S {
        settle()
        return store.selectState<S>().first()
    }

    public suspend inline fun <reified S : ModuleState> awaitState(noinline predicate: (S) -> Boolean): S =
        store.selectState<S>().first { predicate(it) }

    public inline fun <reified A : ModuleAction> assertDispatched(): A {
        settle()
        return dispatchedActions.filterIsInstance<A>().firstOrNull()
            ?: throw AssertionError(
                "Expected an action of type ${A::class.simpleName} to have been dispatched, " +
                    "but recorded actions were: ${dispatchedActions.map { it::class.simpleName }}"
            )
    }

    public inline fun <reified A : ModuleAction> assertNotDispatched() {
        settle()
        val match = dispatchedActions.filterIsInstance<A>().firstOrNull()
        if (match != null) {
            throw AssertionError(
                "Expected no action of type ${A::class.simpleName} to have been dispatched, but found: $match"
            )
        }
    }
}

public fun reaktivTest(
    vararg modules: Module<*, *>,
    timeout: Duration = 10.seconds,
    configure: StoreDSL.() -> Unit = {},
    test: suspend ReaktivTestScope.() -> Unit
): TestResult = runTest(timeout = timeout) {
    val recorded = mutableListOf<ModuleAction>()
    val recording: Middleware = { action, _, _, updatedState ->
        recorded.add(action)
        updatedState(action)
    }
    val store = createStore {
        modules.forEach { module(it) }
        middlewares(recording)
        configure()
        coroutineContext(StandardTestDispatcher(testScheduler))
    }
    val scope = ReaktivTestScope(this, store, recorded)
    try {
        scope.test()
    } finally {
        store.cleanup()
    }
}
