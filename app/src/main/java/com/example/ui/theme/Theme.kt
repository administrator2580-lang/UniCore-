package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = CobaltPrimaryLight,
    onPrimary = MidnightSlateBg,
    primaryContainer = CobaltContainerDark,
    onPrimaryContainer = TextDarkPrimary,
    secondary = EmeraldShield,
    onSecondary = MidnightSlateBg,
    secondaryContainer = EmeraldContainerDark,
    onSecondaryContainer = TextDarkPrimary,
    tertiary = AmberQuota,
    onTertiary = MidnightSlateBg,
    tertiaryContainer = AmberContainerDark,
    background = MidnightSlateBg,
    onBackground = TextDarkPrimary,
    surface = MidnightSurface,
    onSurface = TextDarkPrimary,
    surfaceVariant = MidnightSurfaceVariant,
    onSurfaceVariant = TextDarkSecondary,
    outline = MidnightOutline,
    error = CrimsonAlert,
    errorContainer = CrimsonContainerDark,
    onError = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = CobaltPrimary,
    onPrimary = Color.White,
    primaryContainer = CobaltContainerLight,
    onPrimaryContainer = TextLightPrimary,
    secondary = EmeraldShield,
    onSecondary = Color.White,
    secondaryContainer = EmeraldContainerLight,
    onSecondaryContainer = TextLightPrimary,
    tertiary = AmberQuota,
    onTertiary = Color.White,
    background = LightBg,
    onBackground = TextLightPrimary,
    surface = LightSurface,
    onSurface = TextLightPrimary,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = TextLightSecondary,
    outline = LightOutline,
    error = CrimsonAlert,
    onError = Color.White
)

val UniCoreShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp)
)

@Composable
fun UniCoreTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = UniCoreShapes,
        content = content
    )
}
