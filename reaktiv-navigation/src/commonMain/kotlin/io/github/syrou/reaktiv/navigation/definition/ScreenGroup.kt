package io.github.syrou.reaktiv.navigation.definition

public open class ScreenGroup(
    public val screens: List<Screen>
)  {
    public constructor(vararg screens: Screen) : this(screens.toList())
}
