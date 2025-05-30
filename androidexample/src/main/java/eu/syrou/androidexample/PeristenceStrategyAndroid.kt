package eu.syrou.androidexample

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.syrou.reaktiv.core.persistance.PersistenceStrategy
import kotlinx.coroutines.flow.firstOrNull

class PlatformPersistenceStrategy(private val context: Context) : PersistenceStrategy {
    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "store_state")
    private val STATE_KEY = stringPreferencesKey("persisted_state")

    override suspend fun saveState(serializedState: String) {
        context.dataStore.edit { preferences ->
            preferences[STATE_KEY] = serializedState
        }
    }

    override suspend fun loadState(): String? {
        val result = context.dataStore.data.firstOrNull()?.get(STATE_KEY)
        return result
    }

    override suspend fun hasPersistedState(): Boolean {
        return (context.dataStore.data.firstOrNull()?.get(STATE_KEY)?.length ?: 0) > 0
    }
}