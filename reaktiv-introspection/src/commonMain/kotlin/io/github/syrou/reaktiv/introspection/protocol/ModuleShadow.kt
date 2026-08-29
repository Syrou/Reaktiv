package io.github.syrou.reaktiv.introspection.protocol

import io.github.syrou.reaktiv.core.util.reaktivJson
import kotlinx.serialization.json.JsonObject

public class ModuleShadow(initialStateJson: String? = null) {

    private val modules = LinkedHashMap<String, JsonObject>()

    init {
        initialStateJson?.let(::seed)
    }

    public fun seed(initialStateJson: String) {
        modules.clear()
        val root = runCatching { json.parseToJsonElement(initialStateJson) as? JsonObject }.getOrNull() ?: return
        for ((moduleName, element) in root) {
            (element as? JsonObject)?.let { modules[moduleName] = it }
        }
    }

    public fun apply(action: CapturedAction): JsonObject? {
        if (action.moduleName.isBlank()) return null
        val delta = runCatching { json.parseToJsonElement(action.stateDeltaJson) as? JsonObject }.getOrNull()
            ?: return null

        val merged = when (action.deltaKind) {
            DeltaKind.FULL -> delta
            DeltaKind.FIELDS -> {
                val base = modules[action.moduleName] ?: return null
                mergeFields(base, delta)
            }
        }
        modules[action.moduleName] = merged
        return merged
    }

    public fun put(moduleName: String, state: JsonObject) {
        modules[moduleName] = state
    }

    public fun snapshot(): Map<String, JsonObject> = LinkedHashMap(modules)

    public fun encode(): String = JsonObject(modules).toString()

    private companion object {
        val json = reaktivJson()
    }
}
