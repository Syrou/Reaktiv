package io.github.syrou.reaktiv.introspection.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * Reconstructs full state from an initial state snapshot and sequential module-level deltas.
 *
 * Each action captures only the changed module's state (a delta). Full state at any point
 * can be reconstructed by starting from the initial state and applying deltas sequentially.
 *
 * Usage:
 * ```kotlin
 * val fullState = StateReconstructor.applyDelta(currentStateJson, "CounterModule", deltaJson)
 * val stateAtIndex = StateReconstructor.reconstructAtIndex(initialStateJson, actions, 5)
 * ```
 */
object StateReconstructor {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Applies a single module delta to the current full state JSON.
     *
     * @param currentStateJson The current full state as a JSON string (object with module keys)
     * @param moduleName The module key whose state changed
     * @param deltaJson The new state for that module as a JSON string
     * @return Updated full state JSON string with the module key replaced
     */
    fun applyDelta(currentStateJson: String, moduleName: String, deltaJson: String): String {
        if (moduleName.isBlank()) return currentStateJson

        val currentState = try {
            json.parseToJsonElement(currentStateJson) as? JsonObject ?: return currentStateJson
        } catch (e: Exception) {
            return currentStateJson
        }

        val deltaElement = try {
            json.parseToJsonElement(deltaJson)
        } catch (e: Exception) {
            return currentStateJson
        }

        val updatedState = buildJsonObject {
            currentState.forEach { (key, value) ->
                if (key == moduleName) {
                    put(key, deltaElement)
                } else {
                    put(key, value)
                }
            }
            if (!currentState.containsKey(moduleName)) {
                put(moduleName, deltaElement)
            }
        }

        return json.encodeToString(JsonObject.serializer(), updatedState)
    }

    /**
     * Reconstructs the full state at a given action index by applying all deltas
     * from index 0 through the given index onto the initial state.
     *
     * @param initialStateJson The initial full state captured at session start
     * @param actions The list of captured actions with module-level deltas
     * @param index The index (inclusive) up to which to apply deltas
     * @return The reconstructed full state JSON string at the given index
     */
    fun reconstructAtIndex(initialStateJson: String, actions: List<CapturedAction>, index: Int): String {
        if (actions.isEmpty()) return initialStateJson
        var state = initialStateJson
        val end = index.coerceIn(0, actions.size - 1)
        for (i in 0..end) {
            val action = actions[i]
            state = applyDelta(state, action.moduleName, action.stateDeltaJson)
        }
        return state
    }
}
