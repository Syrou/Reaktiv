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
    
    fun encodeSimple(value: Any): Any {
        return when (value) {
            is String -> urlEncoder.encodeQuery(value)
            is Number, is Boolean -> value
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

    
    fun decodeSimple(encoded: String): Any {
        val decoded = urlEncoder.decode(encoded)

        return when {
            decoded.equals("true", ignoreCase = true) -> true
            decoded.equals("false", ignoreCase = true) -> false
            decoded.toIntOrNull() != null -> decoded.toInt()
            decoded.toLongOrNull() != null -> decoded.toLong()
            decoded.toDoubleOrNull() != null -> decoded.toDouble()
            decoded.contains(",") && decoded.contains(":") -> {
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
                decoded.split(",").map { item ->
                    decodeSimple(item.trim())
                }
            }
            else -> decoded
        }
    }

    
    fun encodeSimpleQueryString(parameters: Map<String, Any>): String {
        if (parameters.isEmpty()) return ""

        return parameters.entries.joinToString("&") { (key, value) ->
            "${urlEncoder.encodeQuery(key)}=${encodeSimple(value)}"
        }
    }

    
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

    
    fun <T> encodeTypeSafe(value: T, serializer: KSerializer<T>): String {
        val jsonString = json.encodeToString(serializer, value)
        return urlEncoder.encodeQuery(jsonString)
    }

    
    inline fun <reified T> encodeTypeSafe(value: T): String {
        val serializer = serializer<T>()
        return encodeTypeSafe(value, serializer)
    }

    
    fun <T> decodeTypeSafe(encoded: String, serializer: KSerializer<T>): T {
        val decoded = urlEncoder.decode(encoded)
        return json.decodeFromString(serializer, decoded)
    }

    
    inline fun <reified T> decodeTypeSafe(encoded: String): T {
        val serializer = serializer<T>()
        return decodeTypeSafe(encoded, serializer)
    }

    
    fun encodeTypeSafeQueryString(parameters: Map<String, TypedParam<*>>): String {
        if (parameters.isEmpty()) return ""

        return parameters.entries.joinToString("&") { (key, typedParam) ->
            @Suppress("UNCHECKED_CAST")
            val encodedValue = encodeTypeSafeWildcard(typedParam as TypedParam<Any>)
            "${urlEncoder.encodeQuery(key)}=$encodedValue"
        }
    }

    
    @Suppress("UNCHECKED_CAST")
    private fun encodeTypeSafeWildcard(typedParam: TypedParam<Any>): String {
        return encodeTypeSafe(typedParam.value, typedParam.serializer as KSerializer<Any>)
    }

    
    fun encodeMixed(value: Any): Any {
        return when (value) {
            is TypedParam<*> -> {
                @Suppress("UNCHECKED_CAST")
                encodeTypeSafeWildcard(value as TypedParam<Any>)
            }
            else -> encodeSimple(value)
        }
    }

    
    fun encodeMixedQueryString(parameters: Map<String, Any>): String {
        if (parameters.isEmpty()) return ""

        return parameters.entries.joinToString("&") { (key, value) ->
            "${urlEncoder.encodeQuery(key)}=${encodeMixed(value)}"
        }
    }
    
    fun encodeStepParameters(stepParams: Map<String, Any>): Map<String, Any> {
        return stepParams.mapValues { (_, value) ->
            encodeMixed(value)
        }
    }
}