package io.github.syrou.reaktiv.navigation.param

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable


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

