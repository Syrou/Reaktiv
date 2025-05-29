package io.github.syrou.reaktiv.navigation.model

import io.github.syrou.reaktiv.core.serialization.StringAnyMap

data class ClearBackStackConfig(
    val root: String?,
    val params: StringAnyMap
)
