package io.github.syrou.reaktiv.navigation.dsl

import io.github.syrou.reaktiv.navigation.param.SerializableParam
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

class TypeSafeParameterBuilder {
     val params = mutableMapOf<String, Any>()
    
    // Inline methods for automatic type inference
    inline fun <reified T> put(key: String, value: T) {
        params[key] = SerializableParam(value, serializer<T>())
    }
    
    // Explicit serializer methods
    fun <T> put(key: String, value: T, serializer: KSerializer<T>) {
        params[key] = SerializableParam(value, serializer)
    }
    
    // Primitive type methods (no serialization needed)
    fun putString(key: String, value: String) {
        params[key] = value
    }
    
    fun putInt(key: String, value: Int) {
        params[key] = value
    }
    
    fun putBoolean(key: String, value: Boolean) {
        params[key] = value
    }
    
    fun putDouble(key: String, value: Double) {
        params[key] = value
    }
    
    fun putLong(key: String, value: Long) {
        params[key] = value
    }
    
    fun putFloat(key: String, value: Float) {
        params[key] = value
    }
    
    // Raw parameter (falls back to toString)
    fun putRaw(key: String, value: Any) {
        params[key] = value
    }
    
    internal fun build(): Map<String, Any> = params.toMap()
}