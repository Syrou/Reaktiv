package io.github.syrou.reaktiv.navigation.extension

import io.github.syrou.reaktiv.navigation.encoding.DualNavigationParameterEncoder
import io.github.syrou.reaktiv.navigation.param.TypeSafeParameterRetriever


fun Map<String, Any>.typed(): TypeSafeParameterRetriever {
    return TypeSafeParameterRetriever(this)
}

// Direct extension functions for common use cases
inline fun <reified T> Map<String, Any>.getDecodedParam(key: String): T? {
    return this.typed().get<T>(key)
}

fun Map<String, Any>.getDecodedString(key: String): String? {
    return this.typed().getString(key)
}

fun Map<String, Any>.getInt(key: String): Int? {
    return this.typed().getInt(key)
}

fun Map<String, Any>.getBoolean(key: String): Boolean? {
    return this.typed().getBoolean(key)
}

fun Map<String, Any>.getDouble(key: String): Double? {
    return this.typed().getDouble(key)
}

fun Map<String, Any>.getLong(key: String): Long? {
    return this.typed().getLong(key)
}

inline fun <reified T> Map<String, Any>.getList(key: String): List<T>? {
    return this.typed().getList<T>(key)
}

inline fun <reified T> Map<String, Any>.getMap(key: String): Map<String, T>? {
    return this.typed().getMap<T>(key)
}