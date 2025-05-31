package io.github.syrou.reaktiv.navigation.param

import io.github.syrou.reaktiv.navigation.encoding.DualNavigationParameterEncoder
import io.github.syrou.reaktiv.navigation.util.CommonUrlEncoder
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.reflect.KClass

class TypeSafeParameterRetriever(
    private val params: Map<String, Any>,
    private val encoder: DualNavigationParameterEncoder = DualNavigationParameterEncoder()
) {

    @OptIn(InternalSerializationApi::class)
    fun <T : Any> get(key: String, clazz: KClass<T>): T? {
        val value = params[key] ?: return null

        println("DEBUG: TypeSafeParameterRetriever.get() - key: $key, value type: ${value::class}, value: $value")

        return when (value) {
            is TypedParam<*> -> {
                println("DEBUG: Found TypedParam, value type: ${value.value::class}")
                @Suppress("UNCHECKED_CAST")
                try {
                    if (clazz.isInstance(value.value)) {
                        println("DEBUG: TypedParam value is instance of $clazz")
                        value.value as T
                    } else {
                        println("DEBUG: TypedParam value is NOT instance of $clazz")
                        null
                    }
                } catch (e: Exception) {
                    println("DEBUG: Error casting TypedParam value: ${e.message}")
                    null
                }
            }

            is String -> {
                println("DEBUG: Found String value, attempting to decode")
                handleStringValue(value, clazz)
            }

            else -> {
                println("DEBUG: Found direct value, checking if instance of $clazz")
                // Direct type match
                if (clazz.isInstance(value)) {
                    println("DEBUG: Direct value is instance of $clazz")
                    value as T
                } else {
                    println("DEBUG: Direct value is NOT instance of $clazz")
                    null
                }
            }
        }
    }

    @OptIn(InternalSerializationApi::class)
    private fun <T : Any> handleStringValue(value: String, clazz: KClass<T>): T? {
        return try {
            when (clazz) {
                String::class -> {
                    // Handle URL decoding for strings
                    if (value.contains('%') || value.contains('+')) {
                        CommonUrlEncoder().decode(value) as? T
                    } else {
                        value as? T
                    }
                }

                Int::class -> value.toIntOrNull() as? T
                Long::class -> value.toLongOrNull() as? T
                Double::class -> value.toDoubleOrNull() as? T
                Float::class -> value.toFloatOrNull() as? T
                Boolean::class -> value.toBooleanStrictOrNull() as? T

                else -> {
                    println("DEBUG: Attempting JSON deserialization for $clazz")
                    // Try to deserialize as JSON
                    try {
                        val decoded = CommonUrlEncoder().decode(value)
                        println("DEBUG: Decoded string: $decoded")

                        if (decoded.startsWith("{") || decoded.startsWith("[")) {
                            // Looks like JSON, try to deserialize
                            val json = Json { ignoreUnknownKeys = true }
                            val serializer = clazz.serializer()
                            val result = json.decodeFromString(serializer, decoded)
                            println("DEBUG: Successfully deserialized JSON to $clazz")
                            result
                        } else {
                            println("DEBUG: Decoded string doesn't look like JSON")
                            null
                        }
                    } catch (e: Exception) {
                        println("DEBUG: JSON deserialization failed: ${e.message}")
                        // Try simple decoding as fallback
                        try {
                            val simpleDecoded = encoder.decodeSimple(value)
                            if (clazz.isInstance(simpleDecoded)) simpleDecoded as T else null
                        } catch (e2: Exception) {
                            println("DEBUG: Simple decoding also failed: ${e2.message}")
                            null
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("DEBUG: handleStringValue failed completely: ${e.message}")
            null
        }
    }

    /**
     * Inline convenience function that calls the non-inline version with reified type
     */
    inline fun <reified T> get(key: String): T? = get(key, T::class as KClass<Any>) as T?

    /**
     * Get required typed parameter (throws if missing)
     */
    inline fun <reified T> require(key: String): T {
        return get<T>(key) ?: throw IllegalArgumentException("Required parameter '$key' of type ${T::class} not found")
    }

    /**
     * Get with default value
     */
    inline fun <reified T> getOrDefault(key: String, default: T): T {
        return get<T>(key) ?: default
    }

    /**
     * Get string parameter with automatic decoding
     */
    fun getString(key: String): String? = get<String>(key)

    /**
     * Get integer parameter
     */
    fun getInt(key: String): Int? = get<Int>(key)

    /**
     * Get boolean parameter
     */
    fun getBoolean(key: String): Boolean? = get<Boolean>(key)

    /**
     * Get double parameter
     */
    fun getDouble(key: String): Double? = get<Double>(key)

    /**
     * Get long parameter
     */
    fun getLong(key: String): Long? = get<Long>(key)

    /**
     * Get list parameter
     */
    inline fun <reified T> getList(key: String): List<T>? = get<List<T>>(key)

    /**
     * Get map parameter
     */
    inline fun <reified T> getMap(key: String): Map<String, T>? = get<Map<String, T>>(key)
}