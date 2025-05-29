package io.github.syrou.reaktiv.navigation.model

data class NavigationConfig(
    val popUpTo: String?,
    val inclusive: Boolean,
    val replaceWith: String?,
    val clearBackStack: Boolean,
    val forwardParams: Boolean
)