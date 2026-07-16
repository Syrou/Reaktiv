package io.github.syrou.reaktiv.devtools.tracing

import io.github.syrou.reaktiv.core.tracing.LogicMethodCompleted
import io.github.syrou.reaktiv.core.tracing.LogicMethodFailed
import io.github.syrou.reaktiv.core.tracing.LogicMethodStart
import io.github.syrou.reaktiv.core.tracing.LogicObserver
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.devtools.DevToolsLogic
import io.github.syrou.reaktiv.devtools.middleware.DevToolsConfig
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
 * @param config DevTools configuration for client identification
 * @param devToolsLogic DevToolsLogic instance for sending messages
 * @param scope CoroutineScope for async message sending
 */
public class DevToolsLogicObserver(
    private val config: DevToolsConfig,
    private val devToolsLogic: DevToolsLogic,
    private val scope: CoroutineScope
) : LogicObserver {

    private fun send(message: DevToolsMessage) {
        if (!devToolsLogic.isConnected()) return
        scope.launch {
            try {
                devToolsLogic.send(message)
            } catch (e: Exception) {
                ReaktivDebug.warn("DevToolsLogicObserver: failed to send ${message::class.simpleName} - ${e.message}")
            }
        }
    }

    override fun onMethodStart(event: LogicMethodStart) {
        send(DevToolsMessage.LogicMethodStarted(config.clientId, event))
    }

    override fun onMethodCompleted(event: LogicMethodCompleted) {
        send(DevToolsMessage.LogicMethodCompleted(config.clientId, event))
    }

    override fun onMethodFailed(event: LogicMethodFailed) {
        send(DevToolsMessage.LogicMethodFailed(config.clientId, event))
    }
}
