package io.github.syrou.reaktiv.navigation.util

internal const val ROOT_GRAPH: String = "root"

internal fun normalizePath(path: String): String = path.trim('/')
