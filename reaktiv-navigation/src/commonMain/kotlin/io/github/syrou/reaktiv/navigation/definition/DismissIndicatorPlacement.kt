package io.github.syrou.reaktiv.navigation.definition

public sealed interface DismissIndicatorPlacement {
    public object Surface : DismissIndicatorPlacement

    public object OutermostChrome : DismissIndicatorPlacement
}
