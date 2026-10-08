package io.github.syrou.reaktiv.navigation.definition

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import kotlinx.serialization.Serializable

@Serializable
public enum class WindowWidthClass(public val minWidthDp: Int) {
    Compact(0),
    Medium(600),
    Expanded(840),
    Large(1200),
    ExtraLarge(1600);

    public companion object {
        public fun fromWidthDp(widthDp: Float): WindowWidthClass =
            entries.lastOrNull { widthDp >= it.minWidthDp } ?: Compact
    }
}

public class PaneColumn internal constructor(
    internal val navigatables: List<Navigatable>
)

@Stable
public class PaneSlot internal constructor(
    public val isOpen: Boolean,
    private val render: @Composable (Modifier, @Composable () -> Unit) -> Unit
) {
    @Composable
    public operator fun invoke(modifier: Modifier = Modifier, empty: @Composable () -> Unit = {}) {
        render(modifier, empty)
    }
}

internal class PaneBlock(
    val columns: List<PaneColumn>,
    val content: @Composable (List<PaneSlot>) -> Unit
) {
    fun columnOf(navigatable: Navigatable): Int = columns.indexOfFirst { navigatable in it.navigatables }
}

public class PaneLayout internal constructor(
    internal val blocks: Map<WindowWidthClass, PaneBlock>
) {
    internal val placed: Set<Navigatable> =
        blocks.values.flatMap { block -> block.columns.flatMap { it.navigatables } }.toSet()

    internal fun blockFor(widthClass: WindowWidthClass?): PaneBlock? {
        if (widthClass == null) return null
        return WindowWidthClass.entries
            .filter { it <= widthClass }
            .asReversed()
            .firstNotNullOfOrNull { blocks[it] }
    }
}

public fun PaneLayout(build: PaneLayoutBuilder.() -> Unit): PaneLayout =
    PaneLayoutBuilder().apply(build).build()

public class PaneLayoutBuilder internal constructor() {
    private val blocks = LinkedHashMap<WindowWidthClass, PaneBlock>()

    public fun column(vararg navigatables: Navigatable): PaneColumn {
        require(navigatables.isNotEmpty()) { "A pane column needs at least one navigatable." }
        return PaneColumn(navigatables.toList())
    }

    public fun at(
        widthClass: WindowWidthClass,
        columns: List<PaneColumn>,
        content: @Composable (List<PaneSlot>) -> Unit
    ) {
        require(widthClass != WindowWidthClass.Compact) {
            "Compact windows always show a single stack, so a pane layout cannot declare them."
        }
        require(widthClass !in blocks) { "The pane layout declares $widthClass more than once." }
        require(columns.isNotEmpty()) { "The $widthClass pane layout declares no columns." }
        val repeated = columns.flatMap { it.navigatables }.groupBy { it }.filterValues { it.size > 1 }.keys
        require(repeated.isEmpty()) {
            "The $widthClass pane layout places ${repeated.joinToString { "'${it.route}'" }} in more than one column."
        }
        blocks[widthClass] = PaneBlock(columns, content)
    }

    public fun medium(
        first: PaneColumn,
        second: PaneColumn,
        content: @Composable (PaneSlot, PaneSlot) -> Unit
    ): Unit = at(WindowWidthClass.Medium, listOf(first, second)) { slots -> content(slots[0], slots[1]) }

    public fun medium(
        first: PaneColumn,
        second: PaneColumn,
        third: PaneColumn,
        content: @Composable (PaneSlot, PaneSlot, PaneSlot) -> Unit
    ): Unit = at(WindowWidthClass.Medium, listOf(first, second, third)) { slots ->
        content(slots[0], slots[1], slots[2])
    }

    public fun expanded(
        first: PaneColumn,
        second: PaneColumn,
        content: @Composable (PaneSlot, PaneSlot) -> Unit
    ): Unit = at(WindowWidthClass.Expanded, listOf(first, second)) { slots -> content(slots[0], slots[1]) }

    public fun expanded(
        first: PaneColumn,
        second: PaneColumn,
        third: PaneColumn,
        content: @Composable (PaneSlot, PaneSlot, PaneSlot) -> Unit
    ): Unit = at(WindowWidthClass.Expanded, listOf(first, second, third)) { slots ->
        content(slots[0], slots[1], slots[2])
    }

    public fun expanded(
        first: PaneColumn,
        second: PaneColumn,
        third: PaneColumn,
        fourth: PaneColumn,
        content: @Composable (PaneSlot, PaneSlot, PaneSlot, PaneSlot) -> Unit
    ): Unit = at(WindowWidthClass.Expanded, listOf(first, second, third, fourth)) { slots ->
        content(slots[0], slots[1], slots[2], slots[3])
    }

    public fun large(
        first: PaneColumn,
        second: PaneColumn,
        content: @Composable (PaneSlot, PaneSlot) -> Unit
    ): Unit = at(WindowWidthClass.Large, listOf(first, second)) { slots -> content(slots[0], slots[1]) }

    public fun large(
        first: PaneColumn,
        second: PaneColumn,
        third: PaneColumn,
        content: @Composable (PaneSlot, PaneSlot, PaneSlot) -> Unit
    ): Unit = at(WindowWidthClass.Large, listOf(first, second, third)) { slots ->
        content(slots[0], slots[1], slots[2])
    }

    public fun large(
        first: PaneColumn,
        second: PaneColumn,
        third: PaneColumn,
        fourth: PaneColumn,
        content: @Composable (PaneSlot, PaneSlot, PaneSlot, PaneSlot) -> Unit
    ): Unit = at(WindowWidthClass.Large, listOf(first, second, third, fourth)) { slots ->
        content(slots[0], slots[1], slots[2], slots[3])
    }

    public fun extraLarge(
        first: PaneColumn,
        second: PaneColumn,
        content: @Composable (PaneSlot, PaneSlot) -> Unit
    ): Unit = at(WindowWidthClass.ExtraLarge, listOf(first, second)) { slots -> content(slots[0], slots[1]) }

    public fun extraLarge(
        first: PaneColumn,
        second: PaneColumn,
        third: PaneColumn,
        content: @Composable (PaneSlot, PaneSlot, PaneSlot) -> Unit
    ): Unit = at(WindowWidthClass.ExtraLarge, listOf(first, second, third)) { slots ->
        content(slots[0], slots[1], slots[2])
    }

    public fun extraLarge(
        first: PaneColumn,
        second: PaneColumn,
        third: PaneColumn,
        fourth: PaneColumn,
        content: @Composable (PaneSlot, PaneSlot, PaneSlot, PaneSlot) -> Unit
    ): Unit = at(WindowWidthClass.ExtraLarge, listOf(first, second, third, fourth)) { slots ->
        content(slots[0], slots[1], slots[2], slots[3])
    }

    internal fun build(): PaneLayout {
        require(blocks.isNotEmpty()) { "A pane layout needs at least one window width class." }
        return PaneLayout(blocks.toMap())
    }
}
