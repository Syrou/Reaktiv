package io.github.syrou.reaktiv.navigation.definition

public sealed interface ContentInsets {
    public object Fullscreen : ContentInsets

    public object SafeArea : ContentInsets
}
