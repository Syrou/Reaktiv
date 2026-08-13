package io.github.syrou.reaktiv.core.tracing

import io.github.syrou.reaktiv.core.util.CopyOnWriteRegistry
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

    private val observers = CopyOnWriteRegistry<(StateRead) -> Unit>()
    private val seen = AtomicReference<Set<StateRead>>(emptySet())

    public val active: Boolean get() = !observers.isEmpty

    public fun addObserver(observer: (StateRead) -> Unit) {
        if (!observers.add(observer)) return
        for (read in seen.load()) {
            notifyObserver(observer, read)
        }
    }

    public fun removeObserver(observer: (StateRead) -> Unit): Boolean = observers.remove(observer)

    public fun clearObservers() {
        observers.clear()
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
        for (observer in observers.snapshot()) {
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
