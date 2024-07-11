package io.github.syrou.reaktiv.core.util

import kotlinx.serialization.modules.SerializersModuleBuilder

interface CustomTypeRegistrar {
    fun registerAdditionalSerializers(builder: SerializersModuleBuilder)
}