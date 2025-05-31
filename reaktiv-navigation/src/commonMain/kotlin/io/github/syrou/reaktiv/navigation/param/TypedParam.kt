package io.github.syrou.reaktiv.navigation.param

import kotlinx.serialization.KSerializer

sealed class TypedParam<T> {
    abstract val value: T
    abstract val serializer: KSerializer<T>
}