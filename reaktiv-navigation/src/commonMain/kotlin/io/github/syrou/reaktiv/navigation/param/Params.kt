package io.github.syrou.reaktiv.navigation.param

import io.github.syrou.reaktiv.core.serialization.AnySerializer
import io.github.syrou.reaktiv.navigation.encoding.DualNavigationParameterEncoder
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlinx.serialization.json.Json
import kotlin.jvm.JvmName

/**
 * Type-safe parameter container for navigation.
 * 
 * Handles encoding/decoding automatically and provides type-safe access methods.
 * Supports both simple types (String, Int, Boolean, etc.) and complex serializable objects.
 * 
 * Usage:
 * ```kotlin
 * val params = Params.of(
 *     "userId" to 123,
 *     "documentUri" to contentUri,
 *     "isActive" to true
 * )
 * 
 * // In screens
 * val userId = params.getInt("userId")
 * val uri = params.getString("documentUri")
 * ```
 */
@Serializable
class Params private constructor(
    @Serializable(with = AnySerializer::class)
    private val values: Map<String, Any>
) {
    val size: Int = values.size

    companion object {
        /**
         * Create empty Params
         */
        fun empty() = Params(emptyMap())
        
        /**
         * Create Params from key-value pairs
         */
        fun of(vararg pairs: Pair<String, Any>) = Params(pairs.toMap())
        
        /**
         * Create Params from URL-encoded query string
         */
        fun fromUrl(encodedQuery: String): Params {
            val encoder = DualNavigationParameterEncoder()
            val decoded = encoder.decodeSimpleQueryString(encodedQuery)
            return Params(decoded)
        }
        
        /**
         * Creates Params from Map<String, Any>
         */
        fun fromMap(map: Map<String, Any>): Params {
            return Params(map)
        }
    }
    
    // Fluent builder API for type safety
    fun with(key: String, value: String) = Params(values + (key to value))
    fun with(key: String, value: Int) = Params(values + (key to value))
    fun with(key: String, value: Boolean) = Params(values + (key to value))
    fun with(key: String, value: Double) = Params(values + (key to value))
    fun with(key: String, value: Long) = Params(values + (key to value))
    fun with(key: String, value: Float) = Params(values + (key to value))
    
    // For complex types
    fun <T : Any> withTyped(key: String, value: T, serializer: KSerializer<T>): Params {
        val storedValue = when (value::class) {
            String::class, Int::class, Boolean::class, 
            Double::class, Long::class, Float::class -> value
            else -> SerializableParam(value, serializer) // Complex objects get wrapped
        }
        return Params(values + (key to storedValue))
    }
    
    // Convenience inline version for reified types
    inline fun <reified T : Any> withTyped(key: String, value: T): Params {
        return withTyped(key, value, serializer<T>())
    }
    
    // Type-safe retrieval
    fun getString(key: String): String? = when (val value = values[key]) {
        is String -> value
        else -> tryDecodeString(value?.toString())
    }
    
    fun getInt(key: String): Int? = when (val value = values[key]) {
        is Int -> value
        is String -> value.toIntOrNull()
        is Number -> value.toInt()
        else -> null
    }
    
    fun getBoolean(key: String): Boolean? = when (val value = values[key]) {
        is Boolean -> value
        is String -> value.toBooleanStrictOrNull()
        else -> null
    }
    
    fun getDouble(key: String): Double? = when (val value = values[key]) {
        is Double -> value
        is String -> value.toDoubleOrNull()
        is Number -> value.toDouble()
        else -> null
    }
    
    fun getLong(key: String): Long? = when (val value = values[key]) {
        is Long -> value
        is String -> value.toLongOrNull()
        is Number -> value.toLong()
        else -> null
    }
    
    fun getFloat(key: String): Float? = when (val value = values[key]) {
        is Float -> value
        is String -> value.toFloatOrNull()
        is Number -> value.toFloat()
        else -> null
    }
    
    // For complex types with proper decoding
    fun <T> getTyped(key: String, serializer: KSerializer<T>): T? {
        return when (val value = values[key]) {
            is TypedParam<*> -> {
                @Suppress("UNCHECKED_CAST")
                (value as? TypedParam<T>)?.value
            }
            is String -> tryDecodeJsonOrSimple(value, serializer)
            else -> value as? T
        }
    }
    
    // Convenience inline version
    inline fun <reified T> getTyped(key: String): T? {
        return getTyped(key, serializer<T>())
    }
    
    
    // Required versions that throw if missing
    fun requireString(key: String): String = getString(key) 
        ?: throw IllegalArgumentException("Required param '$key' not found")
    fun requireInt(key: String): Int = getInt(key) 
        ?: throw IllegalArgumentException("Required param '$key' not found")
    fun requireBoolean(key: String): Boolean = getBoolean(key) 
        ?: throw IllegalArgumentException("Required param '$key' not found")
    fun requireDouble(key: String): Double = getDouble(key) 
        ?: throw IllegalArgumentException("Required param '$key' not found")
    fun requireLong(key: String): Long = getLong(key) 
        ?: throw IllegalArgumentException("Required param '$key' not found")
    fun requireFloat(key: String): Float = getFloat(key) 
        ?: throw IllegalArgumentException("Required param '$key' not found")
    
    inline fun <reified T> requireTyped(key: String): T = getTyped<T>(key) 
        ?: throw IllegalArgumentException("Required param '$key' not found")
    
    // Shorter alias for requireTyped
    inline fun <reified T> require(key: String): T = getTyped<T>(key) 
        ?: throw IllegalArgumentException("Required param '$key' not found")
    
    // Combine params
    operator fun plus(other: Params): Params = Params(values + other.values)
    
    // Remove a param
    fun without(key: String): Params = Params(values - key)
    
    // Check if param exists
    fun contains(key: String): Boolean = values.containsKey(key)
    
    // Get all keys
    fun keys(): Set<String> = values.keys
    
    // Check if empty
    fun isEmpty(): Boolean = values.isEmpty()
    fun isNotEmpty(): Boolean = values.isNotEmpty()
    
    // For URL generation only
    internal fun toQueryString(): String {
        val encoder = DualNavigationParameterEncoder()
        return encoder.encodeSimpleQueryString(values)
    }
    
    // Raw parameter access (for testing and migration compatibility)
    operator fun get(key: String): Any? = values[key]
    
    // Map-like containsKey for testing compatibility
    fun containsKey(key: String): Boolean = values.containsKey(key)
    
    // Internal access to raw values (for migration compatibility)
    fun toMap(): Map<String, Any> = values
    
    private fun tryDecodeString(value: String?): String? {
        if (value == null) return null
        val encoder = DualNavigationParameterEncoder()
        return try {
            val decoded = encoder.decodeSimple(value)
            decoded.toString()
        } catch (e: Exception) {
            value // Return as-is if decoding fails
        }
    }
    
    private fun <T> tryDecodeJsonOrSimple(jsonString: String, serializer: KSerializer<T>): T? {
        return try {
            // Try JSON deserialization first
            if (jsonString.trim().startsWith("{") || jsonString.trim().startsWith("[")) {
                Json.decodeFromString(serializer, jsonString)
            } else {
                // Try simple conversion based on serializer descriptor
                trySimpleConversion(jsonString, serializer)
            }
        } catch (e: Exception) {
            trySimpleConversion(jsonString, serializer)
        }
    }
    
    private fun <T> trySimpleConversion(value: String, serializer: KSerializer<T>): T? {
        return try {
            when (serializer.descriptor.serialName) {
                "kotlin.String" -> value as? T
                "kotlin.Int" -> value.toIntOrNull() as? T
                "kotlin.Boolean" -> value.toBooleanStrictOrNull() as? T
                "kotlin.Double" -> value.toDoubleOrNull() as? T
                "kotlin.Long" -> value.toLongOrNull() as? T
                "kotlin.Float" -> value.toFloatOrNull() as? T
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Params) return false
        return values == other.values
    }
    
    override fun hashCode(): Int = values.hashCode()
    
    override fun toString(): String = "Params($values)"
}

/**
 * Helper builder for fluent parameter construction
 */
class ParamsBuilder {
    @PublishedApi
    internal var params = Params.empty()
    
    fun put(key: String, value: String) = apply { params = params.with(key, value) }
    fun put(key: String, value: Int) = apply { params = params.with(key, value) }
    fun put(key: String, value: Boolean) = apply { params = params.with(key, value) }
    fun put(key: String, value: Double) = apply { params = params.with(key, value) }
    fun put(key: String, value: Long) = apply { params = params.with(key, value) }
    fun put(key: String, value: Float) = apply { params = params.with(key, value) }
    
    inline fun <reified T : Any> putTyped(key: String, value: T) = apply { 
        params = params.withTyped(key, value) 
    }
    
    internal fun build() = params
}


fun Map<String, Any>.requireString(key: String): String = Params.fromMap(this).requireString(key)
fun Map<String, Any>.requireInt(key: String): Int = Params.fromMap(this).requireInt(key)
fun Map<String, Any>.requireBoolean(key: String): Boolean = Params.fromMap(this).requireBoolean(key)
fun Map<String, Any>.requireDouble(key: String): Double = Params.fromMap(this).requireDouble(key)
fun Map<String, Any>.requireLong(key: String): Long = Params.fromMap(this).requireLong(key)
fun Map<String, Any>.requireFloat(key: String): Float = Params.fromMap(this).requireFloat(key)