package io.github.syrou.reaktiv.navigation.definition

open class ScreenGroup(
    val screens: List<Screen>
)  {
    constructor(vararg screens: Screen) : this(screens.toList())
}