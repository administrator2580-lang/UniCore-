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
import com.example.data.preferences.AppThemeMode

private val DarkColorScheme = darkColorScheme(
    primary = CobaltPrimaryLight,
    onPrimary = MidnightSlateBg,
    primaryContainer = CobaltContainerDark,
    onPrimaryContainer = TextDarkPrimary,
    secondary = EmeraldLight,
    onSecondary = MidnightSlateBg,
    secondaryContainer = EmeraldContainerDark,
    onSecondaryContainer = TextDarkPrimary,
    tertiary = AmberLight,
    onTertiary = MidnightSlateBg,
    tertiaryContainer = AmberContainerDark,
    onTertiaryContainer = TextDarkPrimary,
    background = MidnightSlateBg,
    onBackground = TextDarkPrimary,
    surface = MidnightSurface,
    onSurface = TextDarkPrimary,
    surfaceVariant = MidnightSurfaceVariant,
    onSurfaceVariant = TextDarkSecondary,
    outline = MidnightOutline,
    outlineVariant = MidnightOutline.copy(alpha = 0.6f),
    error = CrimsonLight,
    errorContainer = CrimsonContainerDark,
    onError = MidnightSlateBg,
    onErrorContainer = TextDarkPrimary
)

private val LightColorScheme = lightColorScheme(
    primary = CobaltPrimary,
    onPrimary = Color.White,
    primaryContainer = CobaltContainerLight,
    onPrimaryContainer = Color(0xFF1E3A8A),
    secondary = EmeraldShield,
    onSecondary = Color.White,
    secondaryContainer = EmeraldContainerLight,
    onSecondaryContainer = Color(0xFF065F46),
    tertiary = AmberQuota,
    onTertiary = Color.White,
    tertiaryContainer = AmberContainerLight,
    onTertiaryContainer = Color(0xFF78350F),
    background = LightBg,
    onBackground = TextLightPrimary,
    surface = LightSurface,
    onSurface = TextLightPrimary,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = TextLightSecondary,
    outline = LightOutline,
    outlineVariant = LightOutline.copy(alpha = 0.7f),
    error = CrimsonAlert,
    errorContainer = CrimsonContainerLight,
    onError = Color.White,
    onErrorContainer = Color(0xFF7F1D1D)
)

val UniCoreShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp)
)

@Composable
fun UniCoreTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        isDark -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = UniCoreShapes,
        content = content
    )
}
