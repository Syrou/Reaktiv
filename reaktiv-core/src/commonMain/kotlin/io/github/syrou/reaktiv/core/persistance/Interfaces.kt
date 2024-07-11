package io.github.syrou.reaktiv.core.persistance

interface PersistenceStrategy {
    suspend fun saveState(serializedState: String)
    suspend fun loadState(): String?
}