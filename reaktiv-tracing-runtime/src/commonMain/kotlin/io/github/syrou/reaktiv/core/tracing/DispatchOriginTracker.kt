package io.github.syrou.reaktiv.core.tracing

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndUpdate
import kotlin.concurrent.atomics.update

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

internal const val ORIGIN_CAPACITY: Int = 256

internal class OriginIdentityKey(val ref: Any) {
    override fun equals(other: Any?): Boolean = other is OriginIdentityKey && other.ref === ref
    override fun hashCode(): Int = ref.hashCode()
}

@OptIn(ExperimentalAtomicApi::class)
internal object OriginRegistry {
    private val origins = AtomicReference<Map<OriginIdentityKey, List<String>>>(emptyMap())

    fun record(action: Any, origin: String) {
        val key = OriginIdentityKey(action)
        origins.update { current ->
            val base = if (current.size >= ORIGIN_CAPACITY && key !in current) current - current.keys.first() else current
            base + (key to (base[key].orEmpty() + origin))
        }
    }

    fun consume(action: Any): String? {
        val key = OriginIdentityKey(action)
        val previous = origins.fetchAndUpdate { current ->
            val rest = current[key]?.drop(1) ?: return@fetchAndUpdate current
            if (rest.isEmpty()) current - key else current + (key to rest)
        }
        return previous[key]?.first()
    }

    fun clear() {
        origins.store(emptyMap())
    }
}
