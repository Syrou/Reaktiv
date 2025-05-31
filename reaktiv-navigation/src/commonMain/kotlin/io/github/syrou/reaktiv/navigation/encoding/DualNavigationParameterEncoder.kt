package io.github.syrou.reaktiv.navigation.encoding

import io.github.syrou.reaktiv.navigation.definition.UrlEncoder
import io.github.syrou.reaktiv.navigation.param.TypedParam
import io.github.syrou.reaktiv.navigation.util.CommonUrlEncoder
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

class DualNavigationParameterEncoder(
    private val urlEncoder: UrlEncoder = CommonUrlEncoder(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
) {

    // ==========================================
    // SIMPLE APPROACH (Original/Legacy)
    // ==========================================

    /**
     * Simple encoding for basic use cases
     * Maps: "key1:value1,key2:value2"
     * Lists: "item1,item2,item3"
     * Objects: toString()
     */
    fun encodeSimple(value: Any): String {
        return when (value) {
            is String -> urlEncoder.encodeQuery(value)
            is Number, is Boolean -> value.toString()
            is Map<*, *> -> {
                val mapString = value.entries.joinToString(",") { (k, v) ->
                    "${k}:${v}"
                }
                urlEncoder.encodeQuery(mapString)
            }
            is List<*> -> {
                val listString = value.joinToString(",")
                urlEncoder.encodeQuery(listString)
            }
            is Array<*> -> {
                val arrayString = value.joinToString(",")
                urlEncoder.encodeQuery(arrayString)
            }
            is Enum<*> -> urlEncoder.encodeQuery(value.name)
            else -> urlEncoder.encodeQuery(value.toString())
        }
    }

    /**
     * Simple decoding with basic type inference
     */
    fun decodeSimple(encoded: String): Any {
        val decoded = urlEncoder.decode(encoded)

        return when {
            decoded.equals("true", ignoreCase = true) -> true
            decoded.equals("false", ignoreCase = true) -> false
            decoded.toIntOrNull() != null -> decoded.toInt()
            decoded.toLongOrNull() != null -> decoded.toLong()
            decoded.toDoubleOrNull() != null -> decoded.toDouble()
            decoded.contains(",") && decoded.contains(":") -> {
                // Parse as simple map: "key1:value1,key2:value2"
                try {
                    decoded.split(",").associate { pair ->
                        val parts = pair.split(":", limit = 2)
                        if (parts.size == 2) {
                            parts[0] to decodeSimple(parts[1])
                        } else {
                            pair to pair
                        }
                    }
                } catch (e: Exception) {
                    decoded
                }
            }
            decoded.contains(",") -> {
                // Parse as simple list: "item1,item2,item3"
                decoded.split(",").map { item ->
                    decodeSimple(item.trim())
                }
            }
            else -> decoded
        }
    }

    /**
     * Simple query string encoding
     */
    fun encodeSimpleQueryString(parameters: Map<String, Any>): String {
        if (parameters.isEmpty()) return ""

        return parameters.entries.joinToString("&") { (key, value) ->
            "${urlEncoder.encodeQuery(key)}=${encodeSimple(value)}"
        }
    }

    /**
     * Simple query string decoding
     */
    fun decodeSimpleQueryString(queryString: String): Map<String, Any> {
        if (queryString.isBlank()) return emptyMap()

        return queryString.split("&")
            .filter { it.isNotBlank() }
            .mapNotNull { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) {
                    urlEncoder.decode(parts[0]) to decodeSimple(parts[1])
                } else {
                    null
                }
            }
            .toMap()
    }

    // ==========================================
    // TYPE-SAFE APPROACH (New)
    // ==========================================

    /**
     * Type-safe encoding with full kotlinx.serialization support
     */
    fun <T> encodeTypeSafe(value: T, serializer: KSerializer<T>): String {
        val jsonString = json.encodeToString(serializer, value)
        return urlEncoder.encodeQuery(jsonString)
    }

    /**
     * Type-safe encoding with reified types
     */
    inline fun <reified T> encodeTypeSafe(value: T): String {
        val serializer = serializer<T>()
        return encodeTypeSafe(value, serializer)
    }

    /**
     * Type-safe decoding with explicit serializer
     */
    fun <T> decodeTypeSafe(encoded: String, serializer: KSerializer<T>): T {
        val decoded = urlEncoder.decode(encoded)
        return json.decodeFromString(serializer, decoded)
    }

    /**
     * Type-safe decoding with reified types
     */
    inline fun <reified T> decodeTypeSafe(encoded: String): T {
        val serializer = serializer<T>()
        return decodeTypeSafe(encoded, serializer)
    }

    /**
     * Type-safe query string encoding
     * Fixed to handle wildcard types properly
     */
    fun encodeTypeSafeQueryString(parameters: Map<String, TypedParam<*>>): String {
        if (parameters.isEmpty()) return ""

        return parameters.entries.joinToString("&") { (key, typedParam) ->
            @Suppress("UNCHECKED_CAST")
            val encodedValue = encodeTypeSafeWildcard(typedParam as TypedParam<Any>)
            "${urlEncoder.encodeQuery(key)}=$encodedValue"
        }
    }

    /**
     * Helper method to handle wildcard types in TypedParam
     */
    @Suppress("UNCHECKED_CAST")
    private fun encodeTypeSafeWildcard(typedParam: TypedParam<Any>): String {
        return encodeTypeSafe(typedParam.value, typedParam.serializer as KSerializer<Any>)
    }

    // ==========================================
    // UTILITY METHODS (Support both approaches)
    // ==========================================

    /**
     * Mixed encoding - handles both simple and type-safe parameters
     * Used internally by the type-safe approach to handle TypedParam wrappers
     */
    fun encodeMixed(value: Any): String {
        return when (value) {
            is TypedParam<*> -> {
                @Suppress("UNCHECKED_CAST")
                encodeTypeSafeWildcard(value as TypedParam<Any>)
            }
            else -> encodeSimple(value)
        }
    }

    /**
     * Mixed query string encoding - used internally
     */
    fun encodeMixedQueryString(parameters: Map<String, Any>): String {
        if (parameters.isEmpty()) return ""

        return parameters.entries.joinToString("&") { (key, value) ->
            "${urlEncoder.encodeQuery(key)}=${encodeMixed(value)}"
        }
    }
}