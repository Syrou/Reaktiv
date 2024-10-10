package io.syrou.reaktiv

import io.github.syrou.reaktiv.core.Dispatch
import io.github.syrou.reaktiv.core.Middleware
import io.github.syrou.reaktiv.core.Module
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.createStore
import io.syrou.reaktiv.LargeStateModule.LargeAction
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MiddlewareTest {
    data class MiddlewareTestState(val value: Int) : ModuleState

    sealed class MiddlewareTestAction : ModuleAction(MiddlewareTestModule::class) {
        data object Increment : MiddlewareTestAction()
        data object Decrement : MiddlewareTestAction()
    }

    object MiddlewareTestModule : Module<MiddlewareTestState, MiddlewareTestAction> {
        override val initialState = MiddlewareTestState(0)
        override val reducer: (MiddlewareTestState, MiddlewareTestAction) -> MiddlewareTestState = { state, action ->
            when (action) {
                is MiddlewareTestAction.Increment -> state.copy(value = state.value + 1)
                is MiddlewareTestAction.Decrement -> state.copy(value = state.value - 1)
            }
        }

        override val createLogic: (storeAccessor: StoreAccessor) -> ModuleLogic<MiddlewareTestAction> = { storeAccessor: StoreAccessor ->
            ModuleLogic { action -> }
        }
    }

    @Test
    fun `test state immutability during middleware execution`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        var stateBeforeNext: MiddlewareTestState? = null
        var stateAfterNext: MiddlewareTestState? = null

        val testMiddleware: Middleware = { action, getState, dispatch, next ->
            val initialState = getState()[MiddlewareTestState::class.qualifiedName] as MiddlewareTestState
            stateBeforeNext = initialState
            val result = next(action)
            stateAfterNext = result as MiddlewareTestState
            assertEquals(initialState, stateBeforeNext, "State should not change before next is called")
        }

        val store = createStore {
            coroutineContext(testDispatcher)
            module(MiddlewareTestModule)
            middlewares(testMiddleware)
        }

        store.dispatch(MiddlewareTestAction.Increment)
        advanceUntilIdle() // Allow coroutines to execute// Allow coroutines to execute

        assertNotNull(stateBeforeNext)
        assertNotNull(stateAfterNext)
        assertEquals(MiddlewareTestState(0), stateBeforeNext)
        assertEquals(MiddlewareTestState(1), stateAfterNext)
    }

    @Test
    fun `test state immutability across multiple middlewares`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val stateSnapshots = mutableListOf<MiddlewareTestState>()

        val middleware1: Middleware = { action, getState, dispatch, next ->
            stateSnapshots.add(getState()[MiddlewareTestState::class.qualifiedName] as MiddlewareTestState)
            next(action)
        }

        val middleware2: Middleware = { action, getAllStates, dispatch, updatedState ->
            stateSnapshots.add(getAllStates()[MiddlewareTestState::class.qualifiedName] as MiddlewareTestState)
            updatedState(action)
        }

        val middleware3: Middleware = { action, getAllStates, dispatch, updatedState ->
            stateSnapshots.add(getAllStates()[MiddlewareTestState::class.qualifiedName] as MiddlewareTestState)
            val result = updatedState(action)
            stateSnapshots.add(getAllStates()[MiddlewareTestState::class.qualifiedName] as MiddlewareTestState)
        }

        val store = createStore {
            coroutineContext(testDispatcher)
            module(MiddlewareTestModule)
            middlewares(middleware1, middleware2, middleware3)
        }

        store.dispatch(MiddlewareTestAction.Increment)
        advanceUntilIdle() // Allow coroutines to execute
        assertEquals(4, stateSnapshots.size)
        assertEquals(
            listOf(
                MiddlewareTestState(0),
                MiddlewareTestState(0),
                MiddlewareTestState(0),
                MiddlewareTestState(1)
            ), stateSnapshots
        )
    }
}