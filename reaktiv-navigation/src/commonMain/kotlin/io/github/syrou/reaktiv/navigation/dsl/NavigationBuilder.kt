package io.github.syrou.reaktiv.navigation.dsl

import io.github.syrou.reaktiv.navigation.model.NavigationConfig

class NavigationBuilder(
    var route: String,
    var params: Map<String, Any> = emptyMap()
) {
    private var popUpTo: String? = null
    private var inclusive: Boolean = false
    private var replaceWith: String? = null
    private var clearBackStack: Boolean = false
    private var forwardParams: Boolean = false

    fun popUpTo(route: String, inclusive: Boolean = false): NavigationBuilder {
        if (clearBackStack) {
            throw IllegalStateException("Cannot use popUpTo when clearBackStack is true")
        }
        this.popUpTo = route
        this.inclusive = inclusive
        return this
    }

    fun replaceWith(route: String): NavigationBuilder {
        if (clearBackStack) {
            throw IllegalStateException("Cannot use replaceWith when clearBackStack is true")
        }
        this.replaceWith = route
        return this
    }

    fun forwardParams(): NavigationBuilder {
        this.forwardParams = true
        return this
    }

    fun clearBackStack(): NavigationBuilder {
        if (popUpTo != null || replaceWith != null) {
            throw IllegalStateException("Cannot use clearBackStack with popUpTo or replaceWith")
        }
        this.clearBackStack = true
        return this
    }
    internal fun build(): NavigationConfig {
        return NavigationConfig(
            popUpTo = popUpTo,
            inclusive = inclusive,
            replaceWith = replaceWith,
            clearBackStack = clearBackStack,
            forwardParams = forwardParams,
        )
    }
}