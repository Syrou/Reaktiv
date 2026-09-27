package io.github.syrou.reaktiv.devtools.ui.components

import androidx.compose.runtime.MutableState

internal fun <T> storeBacked(current: T, onChange: (T) -> Unit): MutableState<T> = object : MutableState<T> {
    override var value: T
        get() = current
        set(next) = onChange(next)

    override fun component1(): T = current

    override fun component2(): (T) -> Unit = onChange
}
