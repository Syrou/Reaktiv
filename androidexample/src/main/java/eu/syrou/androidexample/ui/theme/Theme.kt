package eu.syrou.androidexample.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val GoldenBrown = Color(0xFFD4AF37)
private val DarkGoldenBrown = Color(0xFFAA8C2C)
private val LightGoldenBrown = Color(0xFFE6C65C)

private val DarkBackground = Color(0xFF121212)
private val DarkSurface = Color(0xFF242424)

private val DarkColorScheme = darkColorScheme(
    primary = GoldenBrown,
    onPrimary = Color.Black,
    primaryContainer = DarkGoldenBrown,
    onPrimaryContainer = Color.White,
    secondary = LightGoldenBrown,
    onSecondary = Color.Black,
    background = DarkBackground,
    surface = DarkSurface,
    onBackground = Color.White,
    onSurface = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = GoldenBrown,
    onPrimary = Color.White,
    primaryContainer = LightGoldenBrown,
    onPrimaryContainer = Color.Black,
    secondary = DarkGoldenBrown,
    onSecondary = Color.White,
    background = Color.White,
    surface = Color(0xFFF5F5F5),
    onBackground = Color.Black,
    onSurface = Color.Black
)

@Composable
fun ReaktivTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        shapes = Shapes(),
        content = content
    )
}