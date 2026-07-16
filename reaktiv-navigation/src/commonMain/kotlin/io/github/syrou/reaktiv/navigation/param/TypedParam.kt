package io.github.syrou.reaktiv.navigation.param

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable


@Serializable
public sealed class TypedParam<T> {
    public abstract val value: T
    public abstract val serializer: KSerializer<T>
}

@Serializable
public data class SerializableParam<T>(
    override val value: T,
    override val serializer: KSerializer<T>
) : TypedParam<T>()

