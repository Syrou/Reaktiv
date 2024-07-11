package io.github.syrou.reaktiv.core.persistance

import io.github.syrou.reaktiv.core.ModuleState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PersistenceManager(
    private val persistenceStrategy: PersistenceStrategy,
    val json: Json = Json {
        ignoreUnknownKeys = true
    }
) {
    suspend fun persistState(state: Map<String, ModuleState>) {
        val serializedState = json.encodeToString(state)
        persistenceStrategy.saveState(serializedState)
    }

    suspend fun restoreState(): Map<String, ModuleState>? {
        val serializedState = persistenceStrategy.loadState() ?: return null
        return json.decodeFromString(serializedState)
    }

    fun copy(json: Json = this.json): PersistenceManager {
        return PersistenceManager(persistenceStrategy, json)
    }
}