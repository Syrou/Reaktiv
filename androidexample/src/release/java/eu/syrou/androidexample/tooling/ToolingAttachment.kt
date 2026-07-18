package eu.syrou.androidexample.tooling

import android.content.Context
import io.github.syrou.reaktiv.core.Module
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.navigation.definition.Screen

fun toolingModule(context: Context): Module<*, *>? = null

fun toolingScreens(): List<Screen> = emptyList()

suspend fun exportCapturedSession(store: StoreAccessor): String? = null
