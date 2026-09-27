package eu.syrou.example.tooling

import io.github.syrou.reaktiv.core.Module
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.devtools.protocol.ClientRole
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

@Serializable
data class DevToolsFormState(
    val serverUrl: String = "",
    val role: ClientRole = ClientRole.PUBLISHER,
    val probeResult: String? = null
) : ModuleState

sealed class DevToolsFormAction : ModuleAction(DevToolsFormModule::class) {
    data class SetServerUrl(val url: String) : DevToolsFormAction()
    data class SetRole(val role: ClientRole) : DevToolsFormAction()
    data class SetProbeResult(val result: String) : DevToolsFormAction()
}

enum class NetworkProbe(val label: String) {
    NULL_FIELD("Null field"),
    MISSING_FIELD("Missing field"),
    VALID("Valid")
}

class DevToolsFormModule(defaultServerUrl: String?) : Module<DevToolsFormState, DevToolsFormAction> {
    override val initialState = DevToolsFormState(serverUrl = defaultServerUrl.orEmpty())
    override val reducer: (DevToolsFormState, DevToolsFormAction) -> DevToolsFormState = { state, action ->
        when (action) {
            is DevToolsFormAction.SetServerUrl -> state.copy(serverUrl = action.url)
            is DevToolsFormAction.SetRole -> state.copy(role = action.role)
            is DevToolsFormAction.SetProbeResult -> state.copy(probeResult = action.result)
        }
    }
    override val createLogic: (storeAccessor: StoreAccessor) -> ModuleLogic = { DevToolsFormLogic(it) }
}

class DevToolsFormLogic(private val storeAccessor: StoreAccessor) : ModuleLogic() {

    suspend fun probe(probe: NetworkProbe) {
        val result = try {
            val subscriber = when (probe) {
                NetworkProbe.NULL_FIELD -> NetworkFailureProbe.fetchSubscriberWithNullName()
                NetworkProbe.MISSING_FIELD -> NetworkFailureProbe.fetchIncompleteSubscriber()
                NetworkProbe.VALID -> NetworkFailureProbe.fetchValidSubscriber()
            }
            "Decoded ${subscriber.displayName}"
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            "${failure::class.simpleName}: ${failure.message}"
        }
        storeAccessor.dispatch(DevToolsFormAction.SetProbeResult(result))
    }
}
