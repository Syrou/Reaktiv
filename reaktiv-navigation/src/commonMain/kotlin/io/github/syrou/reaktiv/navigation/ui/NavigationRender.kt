package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import io.github.syrou.reaktiv.compose.composeState
import io.github.syrou.reaktiv.compose.rememberStore
import io.github.syrou.reaktiv.core.util.ReaktivDebug
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.NavigationModule
import io.github.syrou.reaktiv.navigation.NavigationState
import io.github.syrou.reaktiv.navigation.alias.ActionResource
import io.github.syrou.reaktiv.navigation.definition.LoadingModal
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import io.github.syrou.reaktiv.navigation.param.Params
import io.github.syrou.reaktiv.navigation.util.NavigationDebugger
import io.github.syrou.reaktiv.navigation.util.canHandleBack
import io.github.syrou.reaktiv.navigation.util.getNavigationModule

/**
 * [CompositionLocal] that provides the active [NavigationModule] to the Compose tree.
 *
 * Populated automatically by [NavigationRender]. Use [LocalNavigationModule.current] inside
 * a composable that is a descendant of [NavigationRender] to access the module directly.
 * An error is thrown if accessed outside of a [NavigationRender] host.
 */
val LocalNavigationModule = compositionLocalOf<NavigationModule> {
    error("NavigationModule not provided. Wrap your content with NavigationRender.")
}

/**
 * Returns the [ActionResource] of the currently visible screen, or `null` if the screen
 * does not define one.
 *
 * Must be called inside a composable that is a descendant of [NavigationRender].
 *
 * Example:
 * ```kotlin
 * TopAppBar(
 *     actions = {
 *         currentActionResource()?.invoke()
 *     }
 * )
 * ```
 */
@Composable
fun currentActionResource(): ActionResource? = LocalCurrentActionResource.current

internal val LocalCurrentActionResource = compositionLocalOf<ActionResource?> { null }

/**
 * Root composable that drives the navigation UI.
 *
 * `NavigationRender` observes [NavigationState] from the store and renders the correct
 * screen layers in response to state changes. It handles:
 * - Content layer (regular screens)
 * - Global overlay layer (overlays rendered above content)
 * - System layer (loading modals and other system-level UI)
 * - Bootstrap suppression (content layers are hidden until bootstrap completes)
 * - Evaluation overlay (loading modal rendered directly when isEvaluatingNavigation is true)
 * - Title resolution (resolves the current entry's title via `stringResource`)
 *
 * Place this composable at the root of your application's Compose hierarchy, inside a
 * [StoreProvider]:
 *
 * ```kotlin
 * StoreProvider(store) {
 *     NavigationRender()
 * }
 * ```
 *
 * @param modifier Modifier applied to the root [Box] that wraps all rendered layers.
 * @param handlePlatformBack When true, system back (Android hardware/gesture back) is handled
 *   automatically by dispatching through the unified dismiss funnel. Set to false if the app
 *   wires its own platform back handling.
 */
@Composable
fun NavigationRender(
    modifier: Modifier = Modifier,
    handlePlatformBack: Boolean = true
) {
    val store = rememberStore()
    val navigationState by composeState<NavigationState>()
    val latestNavigationState = rememberUpdatedState(navigationState)
    val navModule = remember { store.getNavigationModule() }
    val graphDefinitions = remember { navModule.getGraphDefinitions() }

    val currentEntryKey = navigationState.currentEntry.stableKey
    val currentNavigatable = navModule.resolveNavigatable(navigationState.currentEntry)
    val resolvedTitle = currentNavigatable?.titleResource?.invoke()
    val resolvedActionResource = currentNavigatable?.actionResource
    LaunchedEffect(currentEntryKey) {
        store.dispatch(NavigationAction.SetCurrentTitle(resolvedTitle))
    }

    if (ReaktivDebug.isEnabled) {
        NavigationDebugger(navigationState, store)
    }

    val interactiveController = remember { InteractiveTransitionController() }

    CompositionLocalProvider(
        LocalNavigationModule provides navModule,
        LocalCurrentActionResource provides resolvedActionResource,
        LocalInteractiveTransitionController provides interactiveController
    ) {
        if (handlePlatformBack) {
            val backCoordinator = remember(navModule) {
                PlatformBackCoordinator(store, navModule, interactiveController) {
                    latestNavigationState.value
                }
            }
            PlatformBackHandler(
                enabled = canHandleBack(navigationState, navModule),
                coordinator = backCoordinator
            )
        }
        Box(
            modifier = modifier
                .fillMaxSize()
                .let {
                    if (platformEdgeSwipeBackEnabled()) {
                        it.backGestureRecognizer(interactiveController)
                    } else {
                        it
                    }
                }
                .dismissGestureRecognizer(interactiveController)
                .gestureNestedScrollHandoff(interactiveController)
        ) {
            val hasActiveLoadingOverlay = navigationState.isEvaluatingNavigation ||
                navigationState.systemLayerEntries.any { navModule.resolveNavigatable(it) is LoadingModal }
            val showContentLayers = !navigationState.isBootstrapping || !hasActiveLoadingOverlay
            if (showContentLayers) {
                UnifiedLayerRenderer(
                    layerType = RenderLayer.CONTENT,
                    entries = navigationState.contentLayerEntries,
                    graphDefinitions = graphDefinitions
                )

                UnifiedLayerRenderer(
                    layerType = RenderLayer.GLOBAL_OVERLAY,
                    entries = navigationState.globalOverlayEntries,
                    graphDefinitions = graphDefinitions
                )
            }

            UnifiedLayerRenderer(
                layerType = RenderLayer.SYSTEM,
                entries = navigationState.systemLayerEntries,
                graphDefinitions = graphDefinitions
            )

            val isEvaluating = navigationState.isEvaluatingNavigation
            if (isEvaluating) {
                val loadingModal = navModule.getLoadingModal()
                if (loadingModal != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .zIndex(NavigationZIndex.SYSTEM_BASE + loadingModal.elevation)
                    ) {
                        loadingModal.Content(Params.empty())
                    }
                }
            }
        }
    }
}
