package io.github.syrou.reaktiv.core.util

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndUpdate

@OptIn(ExperimentalAtomicApi::class)
public class CopyOnWriteRegistry<T> {

    private val entries = AtomicReference<List<T>>(emptyList())

    public val isEmpty: Boolean
        get() = entries.load().isEmpty()

    public val size: Int
        get() = entries.load().size

    public fun snapshot(): List<T> = entries.load()

    public fun add(entry: T): Boolean =
        entry !in entries.fetchAndUpdate { current -> if (entry in current) current else current + entry }

    public fun remove(entry: T): Boolean =
        entry in entries.fetchAndUpdate { current -> current - entry }

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
