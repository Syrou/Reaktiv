package io.github.syrou.reaktiv.devtools.ui.components

internal fun copyTextToClipboard(text: String) {
    js("navigator.clipboard.writeText(text)")
}
