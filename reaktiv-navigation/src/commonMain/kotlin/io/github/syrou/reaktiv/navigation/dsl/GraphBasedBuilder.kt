package io.github.syrou.reaktiv.navigation.dsl

import io.github.syrou.reaktiv.core.CrashRecovery
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.navigation.NavigationModule
import io.github.syrou.reaktiv.navigation.definition.LoadingModal
import io.github.syrou.reaktiv.navigation.definition.NavigationGraph
import io.github.syrou.reaktiv.navigation.definition.Screen
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class GraphBasedBuilder {
    private var rootGraph: NavigationGraph? = null
    private var notFoundScreen: Screen? = null
    private var crashScreen: Screen? = null
    private var onCrash: (suspend (Throwable, ModuleAction?) -> CrashRecovery)? = null
    private val deepLinkAliasBuilder = DeepLinkAliasBuilder()
    private var screenRetentionDuration: Duration = 10.seconds
    private var loadingModal: LoadingModal? = null

    fun rootGraph(block: NavigationGraphBuilder.() -> Unit) {
        val builder = NavigationGraphBuilder("root")
        builder.apply(block)
        rootGraph = builder.build()
    }

    /**
     * Sets the screen to display when a route is not found or when navigating
     * to a graph that has no startScreen/startGraph defined.
     *
     * @param screen The screen to display for not found routes
     */
    fun notFoundScreen(screen: Screen) {
        this.notFoundScreen = screen
    }

    /**
     * Sets the screen to display when a crash occurs in a logic method.
     *
     * The crash screen receives params:
     * - `"exceptionType"` — exception class name
     * - `"exceptionMessage"` — exception message
     * - `"actionType"` — the action that triggered the logic
     *
     * @param screen The screen to display on crash
     * @param onCrash Optional callback invoked before navigation
     */
    fun crashScreen(
        screen: Screen,
        onCrash: (suspend (exception: Throwable, action: ModuleAction?) -> CrashRecovery)? = null
    ) {
        this.crashScreen = screen
        this.onCrash = onCrash
    }

    fun screenRetentionDuration(duration: Duration) {
        screenRetentionDuration = duration
    }

    /**
     * Sets the global loading modal shown while guards or dynamic entry routes are evaluating.
     *
     * The modal is shown as a [RenderLayer.SYSTEM] overlay when evaluation takes longer than
     * the [loadingThreshold] configured on `entry()` or `intercept()` (default 200ms).
     *
     * It does not need to be registered in [screens] or [modals] — it is registered
     * automatically by the navigation module.
     *
     * @param modal The loading modal to display during async evaluation
     */
    fun loadingModal(modal: LoadingModal) {
        this.loadingModal = modal
    }

    /**
     * Register deep link alias mappings for [NavigationLogic.navigateDeepLink].
     *
     * Aliases are checked first before normal route resolution. This allows mapping legacy
     * external URL paths to canonical internal routes.
     *
     * Example:
     * ```kotlin
     * deepLinkAliases {
     *     alias(
     *         pattern = "artist/invite",
     *         targetRoute = "workspace/invite/{token}"
     *     ) { params ->
     *         Params.of("token" to (params["token"] as? String ?: ""))
     *     }
     * }
     * ```
     *
     * @param block Builder block for registering alias entries
     */
    fun deepLinkAliases(block: DeepLinkAliasBuilder.() -> Unit) {
        deepLinkAliasBuilder.apply(block)
    }

    fun build(): NavigationModule {
        requireNotNull(rootGraph) { "Root graph must be defined" }
        return NavigationModule(
            rootGraph = rootGraph!!,
            notFoundScreen = notFoundScreen,
            crashScreen = crashScreen,
            onCrash = onCrash,
            deepLinkAliases = deepLinkAliasBuilder.aliases.toList(),
            screenRetentionDuration = screenRetentionDuration,
            loadingModal = loadingModal
        )
    }
}
