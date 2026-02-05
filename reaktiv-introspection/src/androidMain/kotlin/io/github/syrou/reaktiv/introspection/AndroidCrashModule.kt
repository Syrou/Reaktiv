package io.github.syrou.reaktiv.introspection

import android.content.Context
import io.github.syrou.reaktiv.core.Module
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.core.ModuleLogic
import io.github.syrou.reaktiv.core.ModuleState
import io.github.syrou.reaktiv.core.StoreAccessor
import io.github.syrou.reaktiv.core.util.selectLogic
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * State for the AndroidCrash module.
 */
@Serializable
data class AndroidCrashState(
    val isInstalled: Boolean = false
) : ModuleState

/**
 * Actions for the AndroidCrash module.
 */
@Serializable
sealed class AndroidCrashAction : ModuleAction(AndroidCrashModule::class) {
    @Serializable
    data object MarkInstalled : AndroidCrashAction()
}

/**
 * Logic for installing the Android crash handler.
 *
 * The crash handler is installed during Logic initialization,
 * getting the SessionCapture from IntrospectionLogic.
 */
class AndroidCrashLogic(
    private val storeAccessor: StoreAccessor,
    private val context: Context
) : ModuleLogic<AndroidCrashAction>() {

    init {
        storeAccessor.launch {
            installCrashHandler()
        }
    }

    private suspend fun installCrashHandler() {
        try {
            val introspectionLogic = storeAccessor.selectLogic<IntrospectionLogic>()
            val sessionCapture = introspectionLogic.getSessionCapture()

            AndroidCrashHandler.install(context, sessionCapture)
            storeAccessor.dispatch(AndroidCrashAction.MarkInstalled)
            println("AndroidCrashModule: Crash handler installed successfully")
        } catch (e: Exception) {
            println("AndroidCrashModule: Failed to install crash handler - ${e.message}")
            e.printStackTrace()
        }
    }
}

/**
 * Module for automatic Android crash handler installation.
 *
 * This module automatically installs the AndroidCrashHandler during
 * Logic initialization, capturing session data when crashes occur.
 *
 * **Important:** This module must be registered AFTER IntrospectionModule.
 *
 * Usage (standalone - without DevTools):
 * ```kotlin
 * val store = createStore {
 *     module(IntrospectionModule(config))
 *     module(AndroidCrashModule(applicationContext))
 *     // ... other modules
 * }
 * ```
 *
 * Usage (with DevTools):
 * ```kotlin
 * val store = createStore {
 *     module(IntrospectionModule(config))  // Must be before DevToolsModule
 *     module(DevToolsModule(...))
 *     module(AndroidCrashModule(applicationContext))
 *     // ... other modules
 * }
 * ```
 *
 * @param context Android application context for crash file storage
 */
class AndroidCrashModule(
    private val context: Context
) : Module<AndroidCrashState, AndroidCrashAction> {

    override val initialState = AndroidCrashState()

    override val reducer: (AndroidCrashState, AndroidCrashAction) -> AndroidCrashState = { state, action ->
        when (action) {
            is AndroidCrashAction.MarkInstalled -> state.copy(isInstalled = true)
        }
    }

    override val createLogic: (StoreAccessor) -> AndroidCrashLogic = { storeAccessor ->
        AndroidCrashLogic(storeAccessor, context.applicationContext)
    }
}
