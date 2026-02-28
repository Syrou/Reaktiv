package io.github.syrou.reaktiv.core.persistance

/**
 * Pluggable storage backend for persisting and restoring serialized store state.
 *
 * Implement this interface to provide platform-specific storage (e.g. `SharedPreferences`,
 * a file on disk, or an in-memory store for tests) and pass it to the store builder:
 *
 * ```kotlin
 * val store = createStore {
 *     persistence(MyPersistenceStrategy())
 *     module(CounterModule)
 * }
 * ```
 *
 * All methods are `suspend` so that I/O can be performed on the appropriate dispatcher without
 * blocking the calling coroutine.
 */
interface PersistenceStrategy {
    /**
     * Persist the full serialized store state.
     *
     * @param serializedState A JSON string produced by the store's serialization layer.
     */
    suspend fun saveState(serializedState: String)

    /**
     * Load a previously persisted state string, or `null` if no state has been saved yet.
     *
     * @return The serialized JSON string, or `null` if storage is empty.
     */
    suspend fun loadState(): String?

    /**
     * Returns `true` if a previously persisted state is available to be loaded.
     *
     * Callers use this to decide whether to call [loadState] or start from the initial state.
     */
    suspend fun hasPersistedState(): Boolean
}