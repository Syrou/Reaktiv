package io.github.syrou.reaktiv.devtools.ui

import io.github.syrou.reaktiv.introspection.protocol.CapturedAction
import io.github.syrou.reaktiv.introspection.protocol.KeyframedReconstructor
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
internal object Reconstruction {
    private class Entry(
        val initialStateJson: String,
        val history: List<CapturedAction>,
        val reconstructor: KeyframedReconstructor
    )

    private val latest = AtomicReference<Entry?>(null)

    fun of(initialStateJson: String, history: List<CapturedAction>): KeyframedReconstructor {
        latest.load()?.let { entry ->
            if (entry.history === history && entry.initialStateJson == initialStateJson) return entry.reconstructor
        }
        val fresh = KeyframedReconstructor(initialStateJson, history)
        latest.store(Entry(initialStateJson, history, fresh))
        return fresh
    }

    fun stateAt(initialStateJson: String, history: List<CapturedAction>, index: Int): String =
        if (history.isEmpty()) initialStateJson else of(initialStateJson, history).stateAt(index)
}
