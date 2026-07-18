package io.github.syrou.reaktiv.core.tracing

import io.github.syrou.reaktiv.core.util.ReaktivDebug
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.serialization.Serializable

@Serializable
public data class StateRead(
    val stateClass: String,
    val composable: String
)

@OptIn(ExperimentalAtomicApi::class)
public object StateReadTracker {

    private val observers = AtomicReference<List<(StateRead) -> Unit>>(emptyList())
    private val seen = AtomicReference<Set<StateRead>>(emptySet())

    public val active: Boolean get() = observers.load().isNotEmpty()

    public fun addObserver(observer: (StateRead) -> Unit) {
        while (true) {
            val current = observers.load()
            if (observer in current) return
            if (observers.compareAndSet(current, current + observer)) break
        }
        for (read in seen.load()) {
            notifyObserver(observer, read)
        }
    }

    public fun removeObserver(observer: (StateRead) -> Unit): Boolean {
        while (true) {
            val current = observers.load()
            if (observer !in current) return false
            if (observers.compareAndSet(current, current - observer)) return true
        }
    }

    public fun clearObservers() {
        observers.store(emptyList())
    }

    public fun snapshot(): Set<StateRead> = seen.load()

    public fun reset() {
        seen.store(emptySet())
    }

    public fun notifyStateRead(stateClass: String, composable: String) {
        val read = StateRead(stateClass, composable)
        while (true) {
            val current = seen.load()
            if (read in current) return
            if (seen.compareAndSet(current, current + read)) break
        }
        for (observer in observers.load()) {
            notifyObserver(observer, read)
        }
    }

    private fun notifyObserver(observer: (StateRead) -> Unit, read: StateRead) {
        try {
            observer(read)
        } catch (e: Throwable) {
            ReaktivDebug.warn("StateReadTracker observer threw exception: ${e.message}")
        }
    }
}
