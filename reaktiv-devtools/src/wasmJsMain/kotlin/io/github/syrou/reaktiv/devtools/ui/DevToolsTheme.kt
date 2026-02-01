package io.github.syrou.reaktiv.devtools.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * DevTools color scheme optimized for debugging and state inspection.
 *
 * Color choices are designed for:
 * - Clear differentiation between added, modified, and removed states
 * - High contrast syntax highlighting for JSON values
 * - Reduced eye strain during long debugging sessions
 * - Consistent visual hierarchy
 *
 * Color system:
 * - Primary (Blue): Keys, links, interactive elements
 * - Secondary (Orange): String values
 * - Tertiary (Purple): Boolean values, special types
 * - Error (Red): Removed items, errors
 * - Custom diff colors: Added (green), Modified (yellow)
 */
object DevToolsColors {
    // Background colors - dark theme for reduced eye strain
    val background = Color(0xFF1E1E1E)
    val surface = Color(0xFF252526)
    val surfaceVariant = Color(0xFF2D2D30)

    // Primary - Blue for keys and interactive elements
    val primary = Color(0xFF569CD6)
    val primaryContainer = Color(0xFF264F78)
    val onPrimary = Color(0xFFFFFFFF)
    val onPrimaryContainer = Color(0xFFD6E3FF)

    // Secondary - Orange/Gold for strings (high visibility)
    val secondary = Color(0xFFCE9178)
    val secondaryContainer = Color(0xFF5C3A1E)
    val onSecondary = Color(0xFF1E1E1E)
    val onSecondaryContainer = Color(0xFFFFDDB3)

    // Tertiary - Purple for booleans and special values (consistent purple family)
    val tertiary = Color(0xFFB388FF)
    val tertiaryContainer = Color(0xFF3D2E5C)
    val onTertiary = Color(0xFF1E1E1E)
    val onTertiaryContainer = Color(0xFFE8DDFF)

    // Error - Red for removed/deleted items (clear danger signal)
    val error = Color(0xFFFF6B6B)
    val errorContainer = Color(0xFF5C1E1E)
    val onError = Color(0xFF1E1E1E)
    val onErrorContainer = Color(0xFFFFDAD6)

    // Text colors
    val onBackground = Color(0xFFD4D4D4)
    val onSurface = Color(0xFFD4D4D4)
    val onSurfaceVariant = Color(0xFF9E9E9E)

    // Outline
    val outline = Color(0xFF404040)
    val outlineVariant = Color(0xFF333333)

    // Status colors for connection indicators
    val success = Color(0xFF4EC9B0)  // Green - connected state
    val warning = Color(0xFFDCDCAA)  // Yellow/Amber - connecting state
}

/**
 * Extended colors for diff visualization that don't fit Material 3's standard scheme.
 * These provide clear semantic meaning for state changes in debugging.
 */
data class DiffColors(
    // Added - Green tones for new/added items
    val added: Color = Color(0xFF4EC9B0),
    val addedContainer: Color = Color(0xFF1E4D40),
    val onAddedContainer: Color = Color(0xFF4EC9B0),

    // Modified - Yellow/Amber tones for changed items
    val modified: Color = Color(0xFFDCDCAA),
    val modifiedContainer: Color = Color(0xFF4D4D1E),
    val onModifiedContainer: Color = Color(0xFFDCDCAA),

    // Removed - Uses error colors from main scheme
    val removed: Color = DevToolsColors.error,
    val removedContainer: Color = DevToolsColors.errorContainer,
    val onRemovedContainer: Color = DevToolsColors.onErrorContainer
)

/**
 * Syntax highlighting colors for JSON/code visualization.
 */
data class SyntaxColors(
    val key: Color = DevToolsColors.primary,
    val string: Color = DevToolsColors.secondary,
    val boolean: Color = DevToolsColors.tertiary,
    val number: Color = Color(0xFFB5CEA8),  // Light green for numbers
    val nullValue: Color = DevToolsColors.onSurfaceVariant,
    val bracket: Color = DevToolsColors.onSurfaceVariant,
    val oldValue: Color = DevToolsColors.error
)

val LocalDiffColors = staticCompositionLocalOf { DiffColors() }
val LocalSyntaxColors = staticCompositionLocalOf { SyntaxColors() }

private val DevToolsDarkColorScheme = darkColorScheme(
    // Primary - for keys, links, interactive elements
    primary = DevToolsColors.primary,
    onPrimary = DevToolsColors.onPrimary,
    primaryContainer = DevToolsColors.primaryContainer,
    onPrimaryContainer = DevToolsColors.onPrimaryContainer,

    // Secondary - for strings
    secondary = DevToolsColors.secondary,
    onSecondary = DevToolsColors.onSecondary,
    secondaryContainer = DevToolsColors.secondaryContainer,
    onSecondaryContainer = DevToolsColors.onSecondaryContainer,

    // Tertiary - for booleans and special values (consistent purple)
    tertiary = DevToolsColors.tertiary,
    onTertiary = DevToolsColors.onTertiary,
    tertiaryContainer = DevToolsColors.tertiaryContainer,
    onTertiaryContainer = DevToolsColors.onTertiaryContainer,

    // Error - for removed items, errors
    error = DevToolsColors.error,
    onError = DevToolsColors.onError,
    errorContainer = DevToolsColors.errorContainer,
    onErrorContainer = DevToolsColors.onErrorContainer,

    // Background & Surface
    background = DevToolsColors.background,
    onBackground = DevToolsColors.onBackground,
    surface = DevToolsColors.surface,
    onSurface = DevToolsColors.onSurface,
    surfaceVariant = DevToolsColors.surfaceVariant,
    onSurfaceVariant = DevToolsColors.onSurfaceVariant,

    // Outline
    outline = DevToolsColors.outline,
    outlineVariant = DevToolsColors.outlineVariant
)

/**
 * DevTools theme wrapper that applies the debugging-optimized color scheme.
 *
 * Provides:
 * - Material 3 dark color scheme optimized for code/state viewing
 * - Extended diff colors via [LocalDiffColors]
 * - Syntax highlighting colors via [LocalSyntaxColors]
 *
 * Usage:
 * ```kotlin
 * DevToolsTheme {
 *     val diffColors = LocalDiffColors.current
 *     val syntaxColors = LocalSyntaxColors.current
 *     // Use colors for diff visualization
 * }
 * ```
 */
@Composable
fun DevToolsTheme(content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalDiffColors provides DiffColors(),
        LocalSyntaxColors provides SyntaxColors()
    ) {
        MaterialTheme(
            colorScheme = DevToolsDarkColorScheme,
            content = content
        )
    }
}
