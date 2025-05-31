package io.github.syrou.reaktiv.navigation.param

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/**
 * For serializable data classes
 */
@Serializable
data class SerializableParam<T>(
    override val value: T,
    override val serializer: KSerializer<T>
) : TypedParam<T>()