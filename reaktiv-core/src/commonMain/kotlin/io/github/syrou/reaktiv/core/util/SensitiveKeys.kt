package io.github.syrou.reaktiv.core.util

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

public fun normalizeSensitiveKey(key: String): String = key.lowercase().replace("_", "").replace("-", "")

public fun isSensitiveKey(key: String, keys: Collection<String> = DEFAULT_SENSITIVE_KEYS): Boolean {
    val normalized = normalizeSensitiveKey(key)
    return keys.any { normalized.contains(normalizeSensitiveKey(it)) }
}
