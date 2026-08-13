package io.github.syrou.reaktiv.core.tracing

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

public object DispatchOriginTracker {

    public fun record(action: Any, origin: String) {
        if (!LogicTracer.active) return
        OriginRegistry.record(action, origin)
    }

    public fun consume(action: Any): String? = OriginRegistry.consume(action)

    public fun clear() {
        OriginRegistry.clear()
    }
}

internal expect object OriginRegistry {
    fun record(action: Any, origin: String)
    fun consume(action: Any): String?
    fun clear()
}

internal const val ORIGIN_CAPACITY: Int = 256

internal class OriginIdentityKey(val ref: Any) {
    override fun equals(other: Any?): Boolean = other is OriginIdentityKey && other.ref === ref
    override fun hashCode(): Int = ref.hashCode()
}

@OptIn(ExperimentalAtomicApi::class)
internal class CowOriginRegistry {
    private val origins = AtomicReference<Map<OriginIdentityKey, List<String>>>(emptyMap())

    fun record(action: Any, origin: String) {
        val key = OriginIdentityKey(action)
        while (true) {
            val current = origins.load()
            val base = if (current.size >= ORIGIN_CAPACITY && key !in current) emptyMap() else current
            val updated = base + (key to (base[key].orEmpty() + origin))
            if (origins.compareAndSet(current, updated)) return
        }
    }

    fun consume(action: Any): String? {
        val key = OriginIdentityKey(action)
        while (true) {
            val current = origins.load()
            val queue = current[key] ?: return null
            val origin = queue.first()
            val rest = queue.drop(1)
            val updated = if (rest.isEmpty()) current - key else current + (key to rest)
            if (origins.compareAndSet(current, updated)) return origin
        }
    }

    fun clear() {
        origins.store(emptyMap())
    }
}
