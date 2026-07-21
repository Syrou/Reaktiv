package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.core.Middleware
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.ModuleWithLogic
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.createStore
import io.github.syrou.reaktiv.core.util.selectLogic
import io.github.syrou.reaktiv.core.util.selectState
import io.github.syrou.reaktiv.introspection.tooling.ServiceState
import io.github.syrou.reaktiv.introspection.tooling.ServiceStatus
import io.github.syrou.reaktiv.introspection.tooling.ToolingAction
import io.github.syrou.reaktiv.introspection.tooling.ToolingCommand
import io.github.syrou.reaktiv.introspection.tooling.ToolingLogic
import io.github.syrou.reaktiv.introspection.tooling.ToolingService
import io.github.syrou.reaktiv.introspection.tooling.ToolingServiceContext
import io.github.syrou.reaktiv.introspection.tooling.ToolingState
import io.github.syrou.reaktiv.introspection.tooling.createToolingModule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Serializable
data class ToolingHostState(val count: Int = 0) : ModuleState

sealed class ToolingHostAction : ModuleAction(ToolingHostModule::class) {
    data object Bump : ToolingHostAction()
}

object ToolingHostModule : ModuleWithLogic<ToolingHostState, ToolingHostAction, ModuleLogic> {
    override val initialState = ToolingHostState()
    override val reducer = { state: ToolingHostState, _: ToolingHostAction -> state.copy(count = state.count + 1) }
    override val createLogic = { _: StoreAccessor -> object : ModuleLogic() {} }
}

enum class FakeCommand : ToolingCommand { PING }

class FakeService(
    private val blockActions: Boolean = false,
    override val startsExternallyDriven: Boolean = false
) : ToolingService {
    override val name = "fake"
    var started = false
    var stopped = false
    val commands = mutableListOf<Pair<ToolingCommand, Map<String, String>>>()
    var blocking = false

    override fun createMiddleware(): Middleware? =
        if (!blockActions) null
        else { action, _, _, updatedState ->
            if (!blocking || action is ToolingAction) {
                updatedState(action)
            }
        }

    override suspend fun start(context: ToolingServiceContext) {
        started = true
        context.setStatus(ServiceStatus(ServiceState.RUNNING, "fake running"))
    }

    override suspend fun stop() {
        stopped = true
    }

    override suspend fun onCommand(command: ToolingCommand, args: Map<String, String>) {
        commands.add(command to args)
    }
}

class ToolingModuleTest {

    private fun config() = IntrospectionConfig(
        clientId = "tooling-test",
        clientName = "ToolingTest",
        platform = "JVM"
    )

    @Test
    fun `service starts reports status and receives enum commands`() = runTest {
        val service = FakeService()
        val store = createStore {
            module(createToolingModule(config(), PlatformContext()) { install(service) })
            module(ToolingHostModule)
            coroutineContext(StandardTestDispatcher(testScheduler))
        }
        advanceUntilIdle()

        assertTrue(service.started)
        val state = store.selectState<ToolingState>().first()
        assertEquals(ServiceStatus(ServiceState.RUNNING, "fake running"), state.services["fake"])
        assertTrue(state.isCapturing)

        store.dispatch(ToolingAction.ServiceCommand("fake", FakeCommand.PING, mapOf("k" to "v")))
        advanceUntilIdle()
        assertEquals(listOf<Pair<ToolingCommand, Map<String, String>>>(FakeCommand.PING to mapOf("k" to "v")), service.commands)

        store.reset()
        advanceUntilIdle()
        assertTrue(service.stopped)
    }

    @Test
    fun `capture records dispatched actions through the composed middleware`() = runTest {
        val store = createStore {
            module(createToolingModule(config(), PlatformContext()))
            module(ToolingHostModule)
            coroutineContext(StandardTestDispatcher(testScheduler))
        }
        advanceUntilIdle()
        val capture = store.selectLogic<ToolingLogic>().getSessionCapture()

        store.dispatch(ToolingHostAction.Bump)
        advanceUntilIdle()

        val history = capture.getSessionHistory()
        assertEquals(1, history.actions.size)
        assertEquals("Bump", history.actions.single().actionType)
    }

    @Test
    fun `blocking service gate runs before capture so blocked actions are never recorded`() = runTest {
        val service = FakeService(blockActions = true)
        val store = createStore {
            module(createToolingModule(config(), PlatformContext()) { install(service) })
            module(ToolingHostModule)
            coroutineContext(StandardTestDispatcher(testScheduler))
        }
        advanceUntilIdle()
        val capture = store.selectLogic<ToolingLogic>().getSessionCapture()

        store.dispatch(ToolingHostAction.Bump)
        advanceUntilIdle()
        assertEquals(1, capture.getSessionHistory().actions.size)

        service.blocking = true
        store.dispatch(ToolingHostAction.Bump)
        advanceUntilIdle()

        assertEquals(1, store.selectState<ToolingHostState>().first().count)
        assertEquals(1, capture.getSessionHistory().actions.size)

        store.dispatch(ToolingAction.ServiceCommand("fake", FakeCommand.PING))
        advanceUntilIdle()
        assertEquals(1, service.commands.size)
    }

    @Test
    fun `a service that starts externally driven gates the store before any logic runs`() = runTest {
        val service = FakeService(startsExternallyDriven = true)
        val store = createStore {
            module(createToolingModule(config(), PlatformContext()) { install(service) })
            module(ToolingHostModule)
            coroutineContext(StandardTestDispatcher(testScheduler))
        }
        advanceUntilIdle()

        assertTrue(store.isExternallyDriven)

        store.dispatch(ToolingHostAction.Bump)
        advanceUntilIdle()
        assertEquals(0, store.selectState<ToolingHostState>().first().count)

        val state = store.selectState<ToolingState>().first()
        assertEquals(ServiceStatus(ServiceState.RUNNING, "fake running"), state.services["fake"])

        store.dispatch(ToolingAction.ServiceCommand("fake", FakeCommand.PING))
        advanceUntilIdle()
        assertEquals(1, service.commands.size)
    }
}
