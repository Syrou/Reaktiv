package io.github.syrou.reaktiv.navigation.layer

/**
 * Defines where a navigatable should render in the layer hierarchy
 */
enum class RenderLayer {
    /**
     * Renders in the normal navigation flow, respects graph layouts
     */
    CONTENT,
    
    /**
     * Renders at the root level, above all graphs
     */
    GLOBAL_OVERLAY,
    
    /**
     * Renders at the absolute top, even above other global overlays
     */
    SYSTEM
}