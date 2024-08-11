package io.github.syrou.reaktiv.core.util

import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.StoreAccessor
import kotlinx.coroutines.flow.StateFlow

inline fun <reified S : ModuleState> StoreAccessor.selectState(): StateFlow<S> = this.selectState(S::class)
inline fun <reified L : ModuleLogic<out ModuleAction>> StoreAccessor.selectLogic(): L = selectLogic(L::class)