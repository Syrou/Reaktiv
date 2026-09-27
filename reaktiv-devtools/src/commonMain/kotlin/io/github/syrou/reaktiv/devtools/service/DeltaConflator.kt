package io.github.syrou.reaktiv.devtools.service

import io.github.syrou.reaktiv.introspection.protocol.CapturedAction
import io.github.syrou.reaktiv.introspection.protocol.mergeCapturedDeltas
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class DeltaConflator(
    private val scope: CoroutineScope,
    private val windowMs: Long,
    private val send: suspend (CapturedAction) -> Unit
) {
    private val mutex = Mutex()
    private val pending = LinkedHashMap<String, CapturedAction>()
    private var flushing = false

    suspend fun offer(event: CapturedAction) {
        val startFlush = mutex.withLock {
            val held = pending[event.moduleName]
            pending[event.moduleName] = if (held != null) mergeCapturedDeltas(held, event) else event
            val idle = !flushing
            flushing = true
            idle
        }
        if (startFlush) scope.launch { flush() }
    }

    private suspend fun flush() {
        while (true) {
            delay(windowMs)
            val batch = mutex.withLock {
                val drained = pending.values.toList()
                pending.clear()
                if (drained.isEmpty()) flushing = false
                drained
            }
            if (batch.isEmpty()) return
            batch.forEach { send(it) }
        }
    }
}
