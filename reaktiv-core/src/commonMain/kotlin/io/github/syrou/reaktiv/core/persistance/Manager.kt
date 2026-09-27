package io.github.syrou.reaktiv.core.persistance

import io.github.syrou.reaktiv.core.ModuleState
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

internal class PersistenceManager(
    private val persistenceStrategy: PersistenceStrategy,
    private val json: Json
) {
    private val stateSerializer = MapSerializer(String.serializer(), PolymorphicSerializer(ModuleState::class))

    suspend fun persistState(state: Map<String, ModuleState>) {
        val serializedState = json.encodeToString(stateSerializer, state)
        persistenceStrategy.saveState(serializedState)
    }

    suspend fun restoreState(): Map<String, ModuleState>? {
        val serializedState = persistenceStrategy.loadState() ?: return null
        return json.decodeFromString(stateSerializer, serializedState)
    }

    suspend fun hasPersistedState(): Boolean {
        return persistenceStrategy.hasPersistedState()
    }
}