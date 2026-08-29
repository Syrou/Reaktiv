package io.github.syrou.reaktiv.introspection.network

import io.github.syrou.reaktiv.core.util.CopyOnWriteRegistry

public fun interface NetworkEventListener {
    public fun onNetworkEvent(event: NetworkRequestCapture)
}

public object NetworkTap {
    private val listeners = CopyOnWriteRegistry<NetworkEventListener>()
    private val bodyProviders = CopyOnWriteRegistry<NetworkBodyProvider>()

    public val hasListeners: Boolean get() = !listeners.isEmpty

    public fun emit(event: NetworkRequestCapture) {
        listeners.snapshot().forEach { listener ->
            try {
                listener.onNetworkEvent(event)
            } catch (_: Exception) {
            }
        }
    }

    public fun addListener(listener: NetworkEventListener) {
        listeners.add(listener)
    }

    public fun removeListener(listener: NetworkEventListener) {
        listeners.remove(listener)
    }

    public fun addBodyProvider(provider: NetworkBodyProvider) {
        bodyProviders.add(provider)
    }

    public fun removeBodyProvider(provider: NetworkBodyProvider) {
        bodyProviders.remove(provider)
    }

    public fun bodySlice(
        requestId: String,
        part: NetworkBodyPart,
        offset: Int,
        maxBytes: Int
    ): NetworkBodySlice? {
        bodyProviders.snapshot().forEach { provider ->
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
        listeners.clear()
        bodyProviders.clear()
    }
}
