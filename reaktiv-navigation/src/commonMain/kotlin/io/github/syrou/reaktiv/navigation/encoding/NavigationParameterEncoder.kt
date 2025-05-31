package io.github.syrou.reaktiv.navigation.encoding

import io.github.syrou.reaktiv.navigation.definition.UrlEncoder
import io.github.syrou.reaktiv.navigation.util.CommonUrlEncoder
import kotlinx.serialization.json.Json

/**
 * Handles encoding and decoding of navigation parameters
 * Supports different types with proper URL encoding
 */
class NavigationParameterEncoder(
    private val urlEncoder: UrlEncoder = CommonUrlEncoder(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
) {
    
    /**
     * Encode a parameter value for use in URL paths
     */
    fun encodePathParameter(value: Any): String {
        return when (value) {
            is String -> urlEncoder.encodePath(value)
            is Number -> value.toString()
            is Boolean -> value.toString()
            else -> {
                // For complex objects, serialize to JSON then URL encode
                val jsonString = json.encodeToString(value)
                urlEncoder.encodePath(jsonString)
            }
        }
    }
    
    /**
     * Encode a parameter value for use in URL query parameters
     */
    fun encodeQueryParameter(value: Any): String {
        return when (value) {
            is String -> urlEncoder.encodeQuery(value)
            is Number -> value.toString()
            is Boolean -> value.toString()
            else -> {
                // For complex objects, serialize to JSON then URL encode
                val jsonString = json.encodeToString(value)
                urlEncoder.encodeQuery(jsonString)
            }
        }
    }
    
    /**
     * Decode a parameter value from URL encoding
     */
    fun decodeParameter(encoded: String): String {
        return urlEncoder.decode(encoded)
    }
    
    /**
     * Encode a map of parameters into a query string
     */
    fun encodeQueryString(parameters: Map<String, Any>): String {
        if (parameters.isEmpty()) return ""
        
        return parameters.entries.joinToString("&") { (key, value) ->
            "${urlEncoder.encodeQuery(key)}=${encodeQueryParameter(value)}"
        }
    }
    
    /**
     * Decode a query string into a map of parameters
     */
    fun decodeQueryString(queryString: String): Map<String, String> {
        if (queryString.isBlank()) return emptyMap()
        
        return queryString.split("&")
            .filter { it.isNotBlank() }
            .mapNotNull { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) {
                    urlEncoder.decode(parts[0]) to urlEncoder.decode(parts[1])
                } else {
                    null
                }
            }
            .toMap()
    }
    
    /**
     * Smart parameter decoding that attempts to convert strings back to their original types
     */
    fun decodeParameterWithTypeInference(encoded: String): Any {
        val decoded = decodeParameter(encoded)
        
        // Try to infer the original type
        return when {
            decoded.equals("true", ignoreCase = true) -> true
            decoded.equals("false", ignoreCase = true) -> false
            decoded.toIntOrNull() != null -> decoded.toInt()
            decoded.toLongOrNull() != null -> decoded.toLong()
            decoded.toDoubleOrNull() != null -> decoded.toDouble()
            decoded.startsWith("{") && decoded.endsWith("}") -> {
                // Looks like JSON object, try to parse
                try {
                    json.decodeFromString<Map<String, Any>>(decoded)
                } catch (e: Exception) {
                    decoded // Fall back to string
                }
            }
            decoded.startsWith("[") && decoded.endsWith("]") -> {
                // Looks like JSON array, try to parse
                try {
                    json.decodeFromString<List<Any>>(decoded)
                } catch (e: Exception) {
                    decoded // Fall back to string
                }
            }
            else -> decoded
        }
    }
}
