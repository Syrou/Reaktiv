package io.github.syrou.reaktiv.navigation.param

import io.github.syrou.reaktiv.navigation.encoding.DualNavigationParameterEncoder
import io.github.syrou.reaktiv.navigation.util.CommonUrlEncoder
import kotlin.reflect.KClass

class TypeSafeParameterRetriever(
    private val params: Map<String, Any>,  // Back to private - no visibility issues now
    private val encoder: DualNavigationParameterEncoder = DualNavigationParameterEncoder()
) {

    /**
     * Non-inline function that takes KClass parameter - can access private properties
     */
    fun <T : Any> get(key: String, clazz: KClass<T>): T? {
        val value = params[key] ?: return null

        return when (value) {
            is TypedParam<*> -> {
                @Suppress("UNCHECKED_CAST")
                if (clazz.isInstance(value.value)) value.value as T else null
            }

            is String -> {
                // Handle string values that might be encoded
                try {
                    when (clazz) {
                        String::class -> {
                            // Check if it's URL encoded
                            if (value.contains('%') || value.contains('+')) {
                                CommonUrlEncoder().decode(value) as? T
                            } else {
                                value as? T
                            }
                        }

                        else -> {
                            // Try simple decoding first (for "key:value,key:value" format)
                            try {
                                val simpleDecoded = encoder.decodeSimple(value)
                                if (clazz.isInstance(simpleDecoded)) simpleDecoded as T else null
                            } catch (e: Exception) {
                                // If simple decoding fails, try type-safe decoding
                                try {
                                    // For type-safe decoding, we need to handle it differently since we can't use reified here
                                    // We'll decode the string and try to cast
                                    val decoded = CommonUrlEncoder().decode(value)
                                    // Try to parse as JSON if it looks like JSON
                                    if (decoded.startsWith("{") || decoded.startsWith("[")) {
                                        // This is a limitation - we can't deserialize without knowing the exact type
                                        // Fall back to raw value
                                        if (clazz.isInstance(value)) value as T else null
                                    } else {
                                        if (clazz.isInstance(value)) value as T else null
                                    }
                                } catch (e2: Exception) {
                                    // If all else fails, check if the value is already the right type
                                    if (clazz.isInstance(value)) value as T else null
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (clazz.isInstance(value)) value as T else null
                }
            }

            else -> {
                // Direct type match
                if (clazz.isInstance(value)) value as T else null
            }
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