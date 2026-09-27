package io.github.syrou.reaktiv.core

import io.github.syrou.reaktiv.core.persistance.PersistenceStrategy
import io.github.syrou.reaktiv.core.util.selectState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@Serializable
data class RequesterState(val value: Int = 0) : ModuleState

sealed class RequesterAction : ModuleAction(RequesterModule::class) {
    data object Touch : RequesterAction()
}

class RequesterLogic : ModuleLogic() {
    val transitions = mutableListOf<Boolean>()
    val hydrations = mutableListOf<HydrateSource>()

    override suspend fun onExternalControlChanged(externallyDriven: Boolean) {
        transitions += externallyDriven
    }

    override suspend fun onHydrated(source: HydrateSource) {
        hydrations += source
    }
}

class RequesterModule(
    private val startsFollowing: Boolean = false
) : ModuleWithLogic<RequesterState, RequesterAction, RequesterLogic>, ExternalStateRequester {
    var logic: RequesterLogic? = null

    override val initialState = RequesterState()
    override val reducer: (RequesterState, RequesterAction) -> RequesterState = { state, _ ->
        state.copy(value = state.value + 1)
    }
    override val createLogic: (StoreAccessor) -> RequesterLogic = { RequesterLogic().also { logic = it } }

    override fun startsUnderExternalControl(): Boolean = startsFollowing
}

private class InMemoryPersistence : PersistenceStrategy {
    var saved: String? = null

    override suspend fun saveState(serializedState: String) {
        saved = serializedState
    }

    override suspend fun loadState(): String? = saved

    override suspend fun hasPersistedState(): Boolean = saved != null
}

@OptIn(ExperimentalCoroutinesApi::class)
class ExternalStatePolicyTest {

    private val requesterKey = RequesterState::class.qualifiedName!!
    private val plainKey = ExternalControlState::class.qualifiedName!!

    private fun Store.recordDrops(): List<String> {
        val dropped = mutableListOf<String>()
        setDispatchInstrumentation(object : DispatchInstrumentation {
            override suspend fun onDispatchStarted(action: ModuleAction, queueWaitMs: Long, queueDepth: Long): String = ""
            override fun onDispatchCompleted(token: String, applied: Boolean, durationMs: Long) {}
            override fun onDispatchFailed(token: String, error: Throwable, durationMs: Long) {}
            override suspend fun onDispatchDropped(action: ModuleAction, reason: DispatchDropReason) {
                dropped += "${action::class.simpleName}:$reason"
            }
            override suspend fun onExternalControlChanged(enabled: Boolean) {}
        })
        return dropped
    }

    private fun TestScope.store(block: StoreDSL.() -> Unit): Store = createStore {
        coroutineContext(StandardTestDispatcher(testScheduler))
        block()
    }

    @Test
    fun `a store with no module asking for outside state drops a raw hydrate`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store { module(ExternalControlModule) }
            advanceUntilIdle()
            val dropped = store.recordDrops()

            @Suppress("DEPRECATION")
            val result = store.dispatchAndAwait(
                StoreAction.Hydrate(mapOf(plainKey to ExternalControlState(plainCount = 7)), "test")
            )

            assertNull(store.externalState())
            assertEquals(DispatchResult.Dropped(DispatchDropReason.EXTERNAL_STATE_DENIED), result)
            assertEquals(0, store.selectState<ExternalControlState>().first().plainCount)
            assertEquals(listOf("Hydrate:EXTERNAL_STATE_DENIED"), dropped)
            store.cleanup()
        }

    @Test
    fun `a module that asks for outside state grants it`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val module = RequesterModule()
            val store = store { module(module) }
            advanceUntilIdle()

            val access = assertNotNull(store.externalState())
            val result = access.hydrate(mapOf(requesterKey to RequesterState(value = 5)), HydrateSource.External("test"))

            assertEquals(DispatchResult.Processed, result)
            assertEquals(5, store.selectState<RequesterState>().first().value)
            assertEquals(listOf<HydrateSource>(HydrateSource.External("test")), module.logic!!.hydrations)
            store.cleanup()
        }

    @Test
    fun `allow grants outside state without a module asking`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store {
                module(ExternalControlModule)
                externalState(ExternalStatePolicy.Allow)
            }
            advanceUntilIdle()

            assertNotNull(store.externalState())
            store.cleanup()
        }

    @Test
    fun `deny wins over a module that asks and over a follower start`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val store = store {
                module(RequesterModule(startsFollowing = true))
                externalState(ExternalStatePolicy.Deny)
            }
            advanceUntilIdle()

            assertNull(store.externalState())
            assertFalse(store.isExternallyDriven)
            assertEquals(DispatchResult.Processed, store.dispatchAndAwait(RequesterAction.Touch))
            store.cleanup()
        }

    @Test
    fun `a module that starts following gates the store before any logic runs`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val module = RequesterModule(startsFollowing = true)
            val store = store { module(module) }
            advanceUntilIdle()

            assertTrue(store.isExternallyDriven)
            assertEquals(DispatchResult.Dropped(DispatchDropReason.EXTERNAL_CONTROL), store.dispatchAndAwait(RequesterAction.Touch))
            assertTrue(module.logic!!.transitions.isEmpty(), "a start under control notifies no logic")
            store.cleanup()
        }

    @Test
    fun `restoring persisted state works even when outside state is denied`() =
        runTest(timeout = 5.toDuration(DurationUnit.SECONDS)) {
            val module = RequesterModule()
            val store = store {
                module(module)
                externalState(ExternalStatePolicy.Deny)
                persistenceManager(InMemoryPersistence())
            }
            advanceUntilIdle()
            store.saveState(mapOf(requesterKey to RequesterState(value = 3)))

            store.loadState()

            assertEquals(3, store.selectState<RequesterState>().first().value)
            assertEquals(listOf<HydrateSource>(HydrateSource.Restore), module.logic!!.hydrations)
            store.cleanup()
        }
}
