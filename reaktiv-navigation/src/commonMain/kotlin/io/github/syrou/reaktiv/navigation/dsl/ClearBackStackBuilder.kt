package io.github.syrou.reaktiv.navigation.dsl

import io.github.syrou.reaktiv.core.serialization.StringAnyMap
import io.github.syrou.reaktiv.navigation.model.ClearBackStackConfig

class ClearBackStackBuilder(
    private var root: String? = null,
    private var params: StringAnyMap = emptyMap()
) {
    fun setRoot(route: String, params: StringAnyMap = emptyMap()) {
        this.root = route
        this.params = params
    }

    internal fun build(): ClearBackStackConfig {
        return ClearBackStackConfig(root = root, params = params)
    }
}