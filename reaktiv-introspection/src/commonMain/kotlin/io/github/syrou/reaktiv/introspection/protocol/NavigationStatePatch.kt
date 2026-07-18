package io.github.syrou.reaktiv.introspection.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

public object NavigationStatePatch {

    public fun clearBootstrapping(fullStateJson: String): String {
        return try {
            val root = Json.parseToJsonElement(fullStateJson).jsonObject
            val navKey = root.keys.firstOrNull { it.endsWith(".NavigationState") }
                ?: return fullStateJson
            val nav = root[navKey]?.jsonObject ?: return fullStateJson
            val patchedNav = JsonObject(
                nav + ("isBootstrapping" to JsonPrimitive(false)) + ("isEvaluatingNavigation" to JsonPrimitive(false))
            )
            JsonObject(root + (navKey to patchedNav)).toString()
        } catch (e: Exception) {
            fullStateJson
        }
    }
}
