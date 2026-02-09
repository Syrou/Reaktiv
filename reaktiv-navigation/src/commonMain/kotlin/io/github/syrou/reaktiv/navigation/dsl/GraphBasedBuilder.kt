package io.github.syrou.reaktiv.navigation.dsl

import io.github.syrou.reaktiv.core.CrashRecovery
import io.github.syrou.reaktiv.core.ModuleAction
import io.github.syrou.reaktiv.navigation.NavigationModule
import io.github.syrou.reaktiv.navigation.definition.NavigationGraph
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.model.GuidedFlowDefinition
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class GraphBasedBuilder {
    private var rootGraph: NavigationGraph? = null
    private var notFoundScreen: Screen? = null
    private var crashScreen: Screen? = null
    private var onCrash: (suspend (Throwable, ModuleAction?) -> CrashRecovery)? = null
    private val guidedFlowDefinitions = mutableMapOf<String, GuidedFlowDefinition>()
    private var screenRetentionDuration: Duration = 10.seconds

    fun rootGraph(block: NavigationGraphBuilder.() -> Unit) {
        val builder = NavigationGraphBuilder("root")
        builder.apply(block)
        rootGraph = builder.build()
    }

    /**
     * Sets the screen to display when a route is not found or when navigating
     * to a graph that has no startScreen/startGraph defined.
     *
     * This screen acts as a 404 fallback for the navigation system.
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
     * - `"exception"` — the Throwable that caused the crash
     * - `"exceptionType"` — exception class name
     * - `"exceptionMessage"` — exception message
     * - `"actionType"` — the action that triggered the logic
     *
     * @param screen The screen to display on crash
     * @param onCrash Optional callback invoked before navigation. Return
     *   [CrashRecovery.NAVIGATE_TO_CRASH_SCREEN] to recover, or
     *   [CrashRecovery.RETHROW] to let the crash propagate.
     *   If null, defaults to [CrashRecovery.NAVIGATE_TO_CRASH_SCREEN].
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
     * DSL function to create a GuidedFlow definition
     *
     * @deprecated Guided flows are deprecated. Use regular navigation with separate state modules for multi-step flows.
     * This provides better separation of concerns and more flexibility.
     */
    @Deprecated(
        message = "Guided flows are deprecated. Use regular navigation with separate state modules for multi-step flows.",
        level = DeprecationLevel.WARNING
    )
    fun guidedFlow(route: String, block: GuidedFlowBuilder.() -> Unit) {
        val definition = io.github.syrou.reaktiv.navigation.dsl.guidedFlow(route, block)
        guidedFlowDefinitions[definition.guidedFlow.route] = definition
    }

    fun build(): NavigationModule {
        requireNotNull(rootGraph) { "Root graph must be defined" }
        return NavigationModule(
            rootGraph = rootGraph!!,
            notFoundScreen = notFoundScreen,
            crashScreen = crashScreen,
            onCrash = onCrash,
            originalGuidedFlowDefinitions = guidedFlowDefinitions.toMap(),
            screenRetentionDuration = screenRetentionDuration
        )
    }
}