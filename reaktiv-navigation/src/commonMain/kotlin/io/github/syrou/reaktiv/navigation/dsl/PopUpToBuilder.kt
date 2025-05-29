package io.github.syrou.reaktiv.navigation.dsl

import io.github.syrou.reaktiv.navigation.model.PopUpToConfig

class PopUpToBuilder(
    var route: String,
    var inclusive: Boolean = false
) {
    private var replaceWith: String? = null
    private var replaceParams: Map<String, Any> = emptyMap()

    fun replaceWith(route: String, params: Map<String, Any> = emptyMap()): PopUpToBuilder {
        this.replaceWith = route
        this.replaceParams = params
        return this
    }

    internal fun build(): PopUpToConfig {
        return PopUpToConfig(
            replaceWith = replaceWith,
            replaceParams = replaceParams
        )
    }
}