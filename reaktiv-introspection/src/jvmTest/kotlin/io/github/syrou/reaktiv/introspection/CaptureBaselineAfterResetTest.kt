package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.core.Module
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.HydrateSource
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.tracing.LogicTracer
import io.github.syrou.reaktiv.introspection.tooling.ToolingLogic
import io.github.syrou.reaktiv.introspection.tooling.createToolingModule
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptureBaselineAfterResetTest {

    @Serializable
    data class ProfileState(val name: String = "") : ModuleState

    sealed class ProfileAction : ModuleAction(ProfileModule::class) {
        data object Touch : ProfileAction()
    }

    object ProfileModule : Module<ProfileState, ProfileAction> {
        override val initialState = ProfileState()
        override val reducer: (ProfileState, ProfileAction) -> ProfileState = { state, _ -> state }
        override val createLogic: (StoreAccessor) -> ModuleLogic = { object : ModuleLogic() {} }
    }

    @Serializable
    data class CounterState(val count: Int = 0) : ModuleState

    sealed class CounterAction : ModuleAction(CounterModule::class) {
        data object Increment : CounterAction()
    }

    object CounterModule : Module<CounterState, CounterAction> {
        override val initialState = CounterState()
        override val reducer: (CounterState, CounterAction) -> CounterState = { state, _ -> state.copy(count = state.count + 1) }
        override val createLogic: (StoreAccessor) -> ModuleLogic = { object : ModuleLogic() {} }
    }

    @AfterTest
    fun tearDown() {
        LogicTracer.clearObservers()
    }

    @Test
    fun `after a reset the session baseline is the state the store was reset to`() = runTest {
        val store = createStore {
            coroutineContext(StandardTestDispatcher(testScheduler))
            module(createToolingModule(IntrospectionConfig(clientId = "baseline", clientName = "Baseline", platform = "JVM"), PlatformContext()))
            module(ProfileModule)
            module(CounterModule)
        }
        advanceUntilIdle()
        val capture = store.selectLogic<ToolingLogic>().getSessionCapture()

        store.externalState()!!.hydrate(
            mapOf(ProfileState::class.qualifiedName!! to ProfileState("Bob")),
            HydrateSource.External("test")
        )
        store.dispatchAndAwait(CounterAction.Increment)
        advanceUntilIdle()
        assertTrue(capture.getSessionHistory().initialStateJson.contains("Bob"))

        store.reset()
        advanceUntilIdle()
        store.dispatchAndAwait(CounterAction.Increment)
        advanceUntilIdle()

        val baseline = capture.getSessionHistory().initialStateJson
        assertFalse(baseline.contains("Bob"), "the baseline still holds pre-reset state: $baseline")
    }
}
