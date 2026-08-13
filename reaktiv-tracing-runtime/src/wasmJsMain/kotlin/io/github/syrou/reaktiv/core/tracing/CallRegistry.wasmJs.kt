package io.github.syrou.reaktiv.core.tracing

import kotlinx.coroutines.Job

internal actual object CallRegistry {
    private val delegate = CowCallRegistry()

    actual fun push(job: Job, callId: String): String? = delegate.push(job, callId)

    actual fun pop(callId: String) {
        delegate.pop(callId)
    }

    actual fun clear() {
        delegate.clear()
    }
}
