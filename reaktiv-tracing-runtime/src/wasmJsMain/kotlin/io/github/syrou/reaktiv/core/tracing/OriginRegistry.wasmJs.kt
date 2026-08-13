package io.github.syrou.reaktiv.core.tracing

internal actual object OriginRegistry {
    private val delegate = CowOriginRegistry()

    actual fun record(action: Any, origin: String) {
        delegate.record(action, origin)
    }

    actual fun consume(action: Any): String? = delegate.consume(action)

    actual fun clear() {
        delegate.clear()
    }
}
