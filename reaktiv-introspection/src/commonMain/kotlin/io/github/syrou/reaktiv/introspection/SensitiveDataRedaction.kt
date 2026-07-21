package io.github.syrou.reaktiv.introspection

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
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

/**
 * The key kotlinx.serialization writes the concrete type into for polymorphic values.
 *
 * Masking it would leave a value that no longer names any registered subclass, so redaction
 * preserves it even inside a subtree it is otherwise masking entirely.
 */
public const val CLASS_DISCRIMINATOR_KEY: String = "type"

public fun sensitiveKeyRedactor(
    keys: Set<String> = DEFAULT_SENSITIVE_KEYS,
    mask: String = REDACTED_PLACEHOLDER,
    discriminator: String = CLASS_DISCRIMINATOR_KEY
): StateRedactor {
    val normalizedKeys = keys.map { it.normalizeRedactionKey() }
    return StateRedactor { _, state -> redactSensitive(state, normalizedKeys, mask, discriminator) }
}

private fun redactSensitive(
    element: JsonElement,
    normalizedKeys: List<String>,
    mask: String,
    discriminator: String
): JsonElement =
    when (element) {
        is JsonObject -> buildJsonObject {
            element.forEach { (key, value) ->
                when {
                    key == discriminator -> put(key, value)
                    key.isSensitiveRedactionKey(normalizedKeys) ->
                        put(key, maskLeaves(value, mask, discriminator, normalizedKeys, true))
                    else -> put(key, redactSensitive(value, normalizedKeys, mask, discriminator))
                }
            }
        }
        is JsonArray -> JsonArray(element.map { redactSensitive(it, normalizedKeys, mask, discriminator) })
        else -> element
    }

/**
 * Masks the leaves beneath [element] without changing what any of them decode as.
 *
 * Strings are masked wherever they appear under a sensitive key, since a secret is essentially
 * always a string. Numbers are masked only where the key naming them is itself sensitive, which
 * covers a secret held numerically such as an SSN or card number stored as a Long, while
 * leaving benign numbers that merely sit inside a sensitive object intact. Booleans are never
 * masked: a Boolean is a flag about a secret rather than the secret, and reporting the opposite
 * of the truth misleads anyone reading a capture and makes a replicated follower behave
 * differently from its publisher.
 *
 * Preserving the JSON type is what keeps this safe for replication. Substituting a value of a
 * different type is invisible in a tree viewer but breaks anything decoding the capture back
 * into typed state, which is how one sensitive key used to make a whole module impossible to
 * replicate.
 *
 * The polymorphic class discriminator is preserved even though it is a string, since replacing
 * it leaves a type name no serializer can resolve.
 *
 * @param keyIsSensitive whether the key naming [element] was itself sensitive, which is what
 *   distinguishes a numeric secret from an ordinary number inside a secret's object
 */
private fun maskLeaves(
    element: JsonElement,
    mask: String,
    discriminator: String,
    normalizedKeys: List<String>,
    keyIsSensitive: Boolean
): JsonElement = when (element) {
    is JsonNull -> JsonNull
    is JsonObject -> buildJsonObject {
        element.forEach { (key, value) ->
            if (key == discriminator) {
                put(key, value)
            } else {
                put(
                    key,
                    maskLeaves(
                        value,
                        mask,
                        discriminator,
                        normalizedKeys,
                        key.isSensitiveRedactionKey(normalizedKeys)
                    )
                )
            }
        }
    }
    is JsonArray -> JsonArray(
        element.map { maskLeaves(it, mask, discriminator, normalizedKeys, keyIsSensitive) }
    )
    is JsonPrimitive -> when {
        element.isString -> JsonPrimitive(mask)
        !keyIsSensitive -> element
        element.longOrNull != null -> JsonPrimitive(0)
        element.doubleOrNull != null -> JsonPrimitive(0.0)
        else -> element
    }
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
