package io.github.syrou.reaktiv.core.tracing

import java.util.concurrent.ConcurrentHashMap

internal actual object OriginRegistry {
    private val origins = ConcurrentHashMap<OriginIdentityKey, ArrayDeque<String>>()

    actual fun record(action: Any, origin: String) {
        if (origins.size >= ORIGIN_CAPACITY) {
            origins.clear()
        }
        origins.compute(OriginIdentityKey(action)) { _, existing ->
            (existing ?: ArrayDeque()).apply { addLast(origin) }
        }
    }

    actual fun consume(action: Any): String? {
        var origin: String? = null
        origins.computeIfPresent(OriginIdentityKey(action)) { _, queue ->
            origin = queue.removeFirstOrNull()
            queue.takeIf { it.isNotEmpty() }
        }
        return origin
    }

    actual fun clear() {
        origins.clear()
    }
}
