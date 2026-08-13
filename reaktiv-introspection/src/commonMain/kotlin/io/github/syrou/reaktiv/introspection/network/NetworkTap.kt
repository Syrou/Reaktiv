package io.github.syrou.reaktiv.introspection.network

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

public fun interface NetworkEventListener {
    public fun onNetworkEvent(event: NetworkRequestCapture)
}

@OptIn(ExperimentalAtomicApi::class)
public object NetworkTap {
    private val listeners = AtomicReference<List<NetworkEventListener>>(emptyList())
    private val bodyProviders = AtomicReference<List<NetworkBodyProvider>>(emptyList())

    public val hasListeners: Boolean get() = listeners.load().isNotEmpty()

    public fun emit(event: NetworkRequestCapture) {
        listeners.load().forEach { listener ->
            try {
                listener.onNetworkEvent(event)
            } catch (_: Exception) {
            }
        }
    }

    public fun addListener(listener: NetworkEventListener) {
        while (true) {
            val current = listeners.load()
            if (listeners.compareAndSet(current, current + listener)) return
        }
    }

    public fun removeListener(listener: NetworkEventListener) {
        while (true) {
            val current = listeners.load()
            if (listeners.compareAndSet(current, current - listener)) return
        }
    }

    public fun addBodyProvider(provider: NetworkBodyProvider) {
        while (true) {
            val current = bodyProviders.load()
            if (bodyProviders.compareAndSet(current, current + provider)) return
        }
    }

    public fun removeBodyProvider(provider: NetworkBodyProvider) {
        while (true) {
            val current = bodyProviders.load()
            if (bodyProviders.compareAndSet(current, current - provider)) return
        }
    }

    public fun bodySlice(
        requestId: String,
        part: NetworkBodyPart,
        offset: Int,
        maxBytes: Int
    ): NetworkBodySlice? {
        bodyProviders.load().forEach { provider ->
            val slice = try {
                provider.slice(requestId, part, offset, maxBytes)
            } catch (_: Exception) {
                null
            }
            if (slice != null) return slice
        }
        return null
    }

    public fun clear() {
        listeners.store(emptyList())
        bodyProviders.store(emptyList())
    }
}
