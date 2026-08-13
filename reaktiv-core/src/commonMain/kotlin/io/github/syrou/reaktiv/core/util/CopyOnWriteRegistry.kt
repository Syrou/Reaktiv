package io.github.syrou.reaktiv.core.util

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
public class CopyOnWriteRegistry<T> {

    private val entries = AtomicReference<List<T>>(emptyList())

    public val isEmpty: Boolean
        get() = entries.load().isEmpty()

    public val size: Int
        get() = entries.load().size

    public fun snapshot(): List<T> = entries.load()

    public fun add(entry: T): Boolean {
        while (true) {
            val current = entries.load()
            if (entry in current) return false
            if (entries.compareAndSet(current, current + entry)) return true
        }
    }

    public fun remove(entry: T): Boolean {
        while (true) {
            val current = entries.load()
            if (entry !in current) return false
            if (entries.compareAndSet(current, current - entry)) return true
        }
    }

    public fun clear() {
        entries.store(emptyList())
    }

    public inline fun forEachCatching(onError: (Throwable) -> Unit, action: (T) -> Unit) {
        for (entry in snapshot()) {
            try {
                action(entry)
            } catch (e: Throwable) {
                onError(e)
            }
        }
    }
}
