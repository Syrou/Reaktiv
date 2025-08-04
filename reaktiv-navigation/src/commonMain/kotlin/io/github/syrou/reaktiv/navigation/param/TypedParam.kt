package io.github.syrou.reaktiv.navigation.param

import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.navigation.encoding.DualNavigationParameterEncoder
import io.github.syrou.reaktiv.navigation.util.CommonUrlEncoder
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.reflect.KClass


@Serializable
sealed class TypedParam<T> {
    abstract val value: T
    abstract val serializer: KSerializer<T>
}

@Serializable
data class SerializableParam<T>(
    override val value: T,
    override val serializer: KSerializer<T>
) : TypedParam<T>()


class ParameterRetriever(
    @PublishedApi
    internal val params: Map<String, Any>,
    @PublishedApi
    internal val encoder: DualNavigationParameterEncoder = DualNavigationParameterEncoder()
) {

    inline fun <reified T> get(key: String): T? = getInternal<T>(key)

    @PublishedApi
    internal inline fun <reified T> getInternal(key: String): T? {
        val value = params[key] ?: return null

        ReaktivDebug.nav("ParameterRetriever.get() - key: $key, value type: ${value::class}, value: $value")

        return when (value) {
            is TypedParam<*> -> {
                ReaktivDebug.nav("Found TypedParam, value type: ${value.value::class}")
                @Suppress("UNCHECKED_CAST")
                try {
                    value.value as? T
                } catch (e: Exception) {
                    ReaktivDebug.nav("Error casting TypedParam value: ${e.message}")
                    null
                }
            }

            is String -> {
                ReaktivDebug.nav("Found String value, attempting to decode with reified type")
                handleStringValueReified<T>(value)
            }

            else -> {
                ReaktivDebug.nav("Found direct value, checking if instance of T")
                try {
                    value as? T
                } catch (e: Exception) {
                    ReaktivDebug.nav("Direct value cast failed: ${e.message}")
                    null
                }
            }
        }
    }

    inline fun <reified T> handleStringValueReified(value: String): T? {
        return try {
            when (T::class) {
                String::class -> {
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
                    ReaktivDebug.nav("Attempting JSON deserialization for ${T::class}")
                    try {
                        val decoded = CommonUrlEncoder().decode(value)
                        ReaktivDebug.nav("Decoded string: $decoded")

                        if (decoded.startsWith("{") || decoded.startsWith("[")) {
                            val json = Json { ignoreUnknownKeys = true }
                            val result = json.decodeFromString<T>(decoded)
                            ReaktivDebug.nav("Successfully deserialized JSON to ${T::class}")
                            result
                        } else {
                            ReaktivDebug.nav("Decoded string doesn't look like JSON")
                            null
                        }
                    } catch (e: Exception) {
                        ReaktivDebug.nav("JSON deserialization failed: ${e.message}")
                        try {
                            val simpleDecoded = encoder.decodeSimple(value)
                            simpleDecoded as? T
                        } catch (e2: Exception) {
                            ReaktivDebug.nav("Simple decoding also failed: ${e2.message}")
                            null
                        }
                    }
                }
            }
        } catch (e: Exception) {
            ReaktivDebug.nav("handleStringValueReified failed completely: ${e.message}")
            null
        }
    }

    inline fun <reified T> require(key: String): T {
        return getInternal<T>(key) ?: throw IllegalArgumentException("Required parameter '$key' of type ${T::class} not found")
    }

    inline fun <reified T> getOrDefault(key: String, default: T): T {
        return getInternal<T>(key) ?: default
    }

    fun getString(key: String): String? = getInternal<String>(key)
    fun getInt(key: String): Int? = getInternal<Int>(key)
    fun getBoolean(key: String): Boolean? = getInternal<Boolean>(key)
    fun getDouble(key: String): Double? = getInternal<Double>(key)
    fun getLong(key: String): Long? = getInternal<Long>(key)
    fun getFloat(key: String): Float? = getInternal<Float>(key)
    inline fun <reified T> getList(key: String): List<T>? = getInternal<List<T>>(key)
    inline fun <reified T> getMap(key: String): Map<String, T>? = getInternal<Map<String, T>>(key)
}

fun Map<String, Any>.typed(): ParameterRetriever {
    return ParameterRetriever(this)
}

inline fun <reified T> Map<String, Any>.getParam(key: String): T? {
    return this.typed().get<T>(key)
}

fun Map<String, Any>.getString(key: String): String? = this.typed().getString(key)
fun Map<String, Any>.getInt(key: String): Int? = this.typed().getInt(key)
fun Map<String, Any>.getBoolean(key: String): Boolean? = this.typed().getBoolean(key)
fun Map<String, Any>.getDouble(key: String): Double? = this.typed().getDouble(key)
fun Map<String, Any>.getLong(key: String): Long? = this.typed().getLong(key)
fun Map<String, Any>.getFloat(key: String): Float? = this.typed().getFloat(key)

inline fun <reified T> Map<String, Any>.getList(key: String): List<T>? = this.typed().getList<T>(key)
inline fun <reified T> Map<String, Any>.getMap(key: String): Map<String, T>? = this.typed().getMap<T>(key)

inline fun <reified T> Map<String, Any>.requireParam(key: String): T = this.typed().require<T>(key)
inline fun <reified T> Map<String, Any>.getParamOrDefault(key: String, default: T): T = this.typed().getOrDefault(key, default)