package io.github.syrou.reaktiv.navigation.model

import androidx.compose.runtime.Stable
import io.github.syrou.reaktiv.navigation.NavigationBreadcrumb
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Everything about the navigation position that is a pure function of the back stack,
 * the active modal contexts, and the registered graph definitions.
 *
 * The reducer computes one of these per state change and
 * [io.github.syrou.reaktiv.navigation.NavigationState] exposes each field through an
 * accessor of the same name, so callers read `navState.visibleLayers` rather than
 * `navState.derived.visibleLayers`.
 *
 * Adding a derived value means adding it here and adding one accessor, rather than
 * threading a field through the state class, an intermediate holder, and every
 * construction site.
 *
 * The constructor is internal on purpose: a projection is something the reducer computes from
 * the back stack, never something a caller supplies. Nothing outside the navigation module can
 * fabricate one, so a derived value cannot disagree with the stack it describes.
 *
 * @property visibleLayers Entries that should be rendered, ordered from bottom to top layer.
 * @property currentFullPath The full slash-separated path for the current entry.
 * @property currentGraphHierarchy Graph IDs from root to the graph containing the current entry.
 * @property breadcrumbs Breadcrumb trail derived from [currentFullPath].
 * @property isCurrentModal `true` when the current entry is a [io.github.syrou.reaktiv.navigation.definition.Modal].
 * @property isCurrentScreen `true` when the current entry is a [io.github.syrou.reaktiv.navigation.definition.Screen].
 * @property hasModalsInStack `true` when at least one modal is present anywhere in the back stack.
 * @property contentLayerEntries Entries assigned to [io.github.syrou.reaktiv.navigation.layer.RenderLayer.CONTENT].
 * @property globalOverlayEntries Entries assigned to [io.github.syrou.reaktiv.navigation.layer.RenderLayer.GLOBAL_OVERLAY].
 * @property systemLayerEntries Entries assigned to [io.github.syrou.reaktiv.navigation.layer.RenderLayer.SYSTEM].
 * @property underlyingScreen The screen rendered underneath the current modal, or `null`.
 * @property modalsInStack All modal entries currently present in the back stack.
 * @property underlyingScreenGraphHierarchy Graph hierarchy of [underlyingScreen].
 * @property showsNavigationChrome `true` when the current destination wants a navigation header.
 */
@Stable
@Serializable
@ConsistentCopyVisibility
public data class NavigationProjection internal constructor(
    val visibleLayers: List<@Contextual NavigationEntry>,
    val currentFullPath: String,
    val currentGraphHierarchy: List<String>,
    val breadcrumbs: List<NavigationBreadcrumb>,
    val isCurrentModal: Boolean,
    val isCurrentScreen: Boolean,
    val hasModalsInStack: Boolean,
    val contentLayerEntries: List<@Contextual NavigationEntry>,
    val globalOverlayEntries: List<@Contextual NavigationEntry>,
    val systemLayerEntries: List<@Contextual NavigationEntry>,
    @Contextual val underlyingScreen: NavigationEntry?,
    val modalsInStack: List<@Contextual NavigationEntry>,
    val underlyingScreenGraphHierarchy: List<String>? = null,
    val showsNavigationChrome: Boolean = true
)
