package io.github.syrou.reaktiv.navigation.definition

import io.github.syrou.reaktiv.core.StoreAccessor

public enum class DismissSource {
    Back,
    TapOutside,
    Swipe
}

public sealed interface DismissAction {
    public object Pop : DismissAction
    public object Ignore : DismissAction
    public class Run(public val handler: suspend StoreAccessor.() -> Unit) : DismissAction
}

public data class Dismissal(
    val back: DismissAction = DismissAction.Pop,
    val tapOutside: DismissAction = DismissAction.Ignore,
    val swipe: DismissAction = DismissAction.Pop
) {
    public operator fun get(source: DismissSource): DismissAction = when (source) {
        DismissSource.Back -> back
        DismissSource.TapOutside -> tapOutside
        DismissSource.Swipe -> swipe
    }

    public companion object {
        public val Default: Dismissal = Dismissal()

        public val Blocking: Dismissal = Dismissal(
            back = DismissAction.Ignore,
            tapOutside = DismissAction.Ignore,
            swipe = DismissAction.Ignore
        )

        public val Dismissable: Dismissal = Dismissal(
            back = DismissAction.Pop,
            tapOutside = DismissAction.Pop,
            swipe = DismissAction.Pop
        )

        public fun all(action: DismissAction): Dismissal = Dismissal(action, action, action)

        internal fun fromLegacy(
            handler: (suspend StoreAccessor.() -> Unit)?,
            swipeToDismiss: Boolean
        ): Dismissal {
            val handled = handler?.let { DismissAction.Run(it) }
            return Dismissal(
                back = handled ?: DismissAction.Pop,
                tapOutside = handled ?: DismissAction.Ignore,
                swipe = if (!swipeToDismiss) DismissAction.Ignore else handled ?: DismissAction.Pop
            )
        }
    }
}

internal val DismissAction.allowsDismiss: Boolean
    get() = this !is DismissAction.Ignore
