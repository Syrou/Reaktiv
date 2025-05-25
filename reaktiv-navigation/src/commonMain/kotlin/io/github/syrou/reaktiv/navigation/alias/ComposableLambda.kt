package io.github.syrou.reaktiv.navigation.alias

import androidx.compose.runtime.Composable

typealias TitleResource = @Composable (() -> String)
typealias ActionResource = @Composable (() -> Unit)