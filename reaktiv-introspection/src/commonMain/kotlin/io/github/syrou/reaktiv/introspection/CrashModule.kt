package io.github.syrou.reaktiv.introspection

import io.github.syrou.reaktiv.core.Module
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.introspection.capture.SessionCapture
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * State for the Crash module.
 */
@Serializable
data class CrashState(
    val isInstalled: Boolean = false
) : ModuleState

/**
 * Actions for the Crash module.
 */
@Serializable
sealed class CrashAction : ModuleAction(CrashModule::class) {
    @Serializable
    data object MarkInstalled : CrashAction()
}

/**
 * Logic for installing the platform crash handler.
 *
 * The crash handler is installed during Logic initialization
 * using the platform-specific [CrashHandler] implementation.
 */
class CrashLogic(
    private val storeAccessor: StoreAccessor,
    private val platformContext: PlatformContext,
    private val sessionCapture: SessionCapture
) : ModuleLogic<CrashAction>() {

    init {
        storeAccessor.launch {
            installCrashHandler()
        }
    }

    private suspend fun installCrashHandler() {
        try {
            CrashHandler(platformContext, sessionCapture).install()
            storeAccessor.dispatch(CrashAction.MarkInstalled)
            println("CrashModule: Crash handler installed successfully")
        } catch (e: Exception) {
            println("CrashModule: Failed to install crash handler - ${e.message}")
            e.printStackTrace()
        }
    }
}

/**
 * Module for automatic platform crash handler installation.
 *
 * This module automatically installs the platform-specific [CrashHandler] during
 * Logic initialization, capturing session data when crashes occur.
 *
 * Usage:
 * ```kotlin
 * val sessionCapture = SessionCapture()
 *
 * val store = createStore {
 *     module(IntrospectionModule(config, sessionCapture, platformContext))
 *     module(CrashModule(platformContext, sessionCapture))
 * }
 * ```
 *
 * @param platformContext Platform context for file storage
 * @param sessionCapture Shared SessionCapture instance for crash data
 */
class CrashModule(
    private val platformContext: PlatformContext,
    private val sessionCapture: SessionCapture
) : Module<CrashState, CrashAction> {

    override val initialState = CrashState()

    override val reducer: (CrashState, CrashAction) -> CrashState = { state, action ->
        when (action) {
            is CrashAction.MarkInstalled -> state.copy(isInstalled = true)
        }
    }

    override val createLogic: (StoreAccessor) -> CrashLogic = { storeAccessor ->
        CrashLogic(storeAccessor, platformContext, sessionCapture)
    }
}
