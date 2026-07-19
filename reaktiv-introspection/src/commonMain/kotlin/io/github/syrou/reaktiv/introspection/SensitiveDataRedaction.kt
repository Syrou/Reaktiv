package io.github.syrou.reaktiv.introspection

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

public val DEFAULT_SENSITIVE_KEYS: Set<String> = setOf(
    "password",
    "passwd",
    "pwd",
    "secret",
    "token",
    "apikey",
    "accesstoken",
    "refreshtoken",
    "authorization",
    "credential",
    "privatekey",
    "cvv",
    "creditcard",
    "cardnumber",
    "ssn"
)

public const val REDACTED_PLACEHOLDER: String = "[REDACTED]"

public fun sensitiveKeyRedactor(
    keys: Set<String> = DEFAULT_SENSITIVE_KEYS,
    mask: String = REDACTED_PLACEHOLDER
): StateRedactor {
    val normalizedKeys = keys.map { it.normalizeRedactionKey() }
    return StateRedactor { _, state -> redactSensitive(state, normalizedKeys, mask) }
}

private fun redactSensitive(element: JsonElement, normalizedKeys: List<String>, mask: String): JsonElement =
    when (element) {
        is JsonObject -> buildJsonObject {
            element.forEach { (key, value) ->
                if (key.isSensitiveRedactionKey(normalizedKeys)) {
                    put(key, JsonPrimitive(mask))
                } else {
                    put(key, redactSensitive(value, normalizedKeys, mask))
                }
            }
        }
        is JsonArray -> JsonArray(element.map { redactSensitive(it, normalizedKeys, mask) })
        else -> element
    }

private fun String.normalizeRedactionKey(): String =
    lowercase().replace("_", "").replace("-", "")

private fun String.isSensitiveRedactionKey(normalizedKeys: List<String>): Boolean {
    val normalized = normalizeRedactionKey()
    return normalizedKeys.any { normalized.contains(it) }
}

internal fun IntrospectionConfig.resolveRedactor(): StateRedactor? {
    val builtin = if (redactSensitiveKeys) sensitiveKeyRedactor() else null
    val custom = redactor
    return when {
        builtin != null && custom != null ->
            StateRedactor { module, state -> custom.redact(module, builtin.redact(module, state)) }
        else -> builtin ?: custom
    }
}
