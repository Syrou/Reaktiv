package io.github.syrou.reaktiv.devtools.tracing

import io.github.syrou.reaktiv.core.tracing.LogicMethodCompleted
import io.github.syrou.reaktiv.core.tracing.LogicMethodFailed
import io.github.syrou.reaktiv.core.tracing.LogicMethodStart
import io.github.syrou.reaktiv.core.tracing.LogicObserver
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.devtools.protocol.DevToolsMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * LogicObserver implementation that forwards tracing events to DevTools.
 *
 * This observer connects the LogicTracer (from reaktiv-core) with the DevTools
 * infrastructure, enabling logic method traces to be displayed in the DevTools UI.
 * Filtering of events is handled in the WASM UI, not here.
 *
 * @param clientId Client identifier attached to each forwarded message
 * @param scope CoroutineScope for async message sending
 * @param isConnected Gate consulted before sending
 * @param sendMessage Sink delivering the message over the active connection
 */
public class DevToolsLogicObserver(
    private val clientId: String,
    private val scope: CoroutineScope,
    private val isConnected: () -> Boolean,
    private val sendMessage: suspend (DevToolsMessage) -> Unit
) : LogicObserver {

    private fun send(message: DevToolsMessage) {
        if (!isConnected()) return
        scope.launch {
            try {
                sendMessage(message)
            } catch (e: Exception) {
                ReaktivDebug.warn("DevToolsLogicObserver: failed to send ${message::class.simpleName} - ${e.message}")
            }
        }
    }

    override fun onMethodStart(event: LogicMethodStart) {
        send(DevToolsMessage.LogicMethodStarted(clientId, event))
    }

    override fun onMethodCompleted(event: LogicMethodCompleted) {
        send(DevToolsMessage.LogicMethodCompleted(clientId, event))
    }

    override fun onMethodFailed(event: LogicMethodFailed) {
        send(DevToolsMessage.LogicMethodFailed(clientId, event))
    }
}
