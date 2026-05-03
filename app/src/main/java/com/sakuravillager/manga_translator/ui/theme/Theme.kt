package com.sakuravillager.manga_translator.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Light Color Schemes
private val LightDefaultColorScheme = lightColorScheme(
    primary = TaupePrimary,
    secondary = WarmSandSecondary,
    background = BackgroundLight,
    surface = SurfaceLight,
    surfaceVariant = SurfaceGreenLight,
    onPrimary = TextPrimary,
    onSecondary = TextPrimary,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    primaryContainer = CardGreenBackground,
    secondaryContainer = CardGreenBackground,
    tertiary = SuccessGreen
)

private val LightGreenAppleColorScheme = lightColorScheme(
    primary = GreenApplePrimary,
    secondary = GreenAppleSecondary,
    background = GreenAppleBackground,
    surface = GreenAppleSurface,
    surfaceVariant = SurfaceGreenLight,
    onPrimary = TextPrimary,
    onSecondary = TextPrimary,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    primaryContainer = GreenAppleContainer,
    secondaryContainer = GreenAppleContainer,
    tertiary = SuccessGreen
)

// Dark Color Schemes
private val DarkDefaultColorScheme = darkColorScheme(
    primary = TaupePrimary,
    secondary = WarmSandSecondary,
    background = BackgroundDark,
    surface = SurfaceDark,
    surfaceVariant = SurfaceGreenDark,
    onPrimary = TextPrimaryDark,
    onSecondary = TextPrimaryDark,
    onBackground = TextPrimaryDark,
    onSurface = TextPrimaryDark,
    primaryContainer = DarkWorkspaceBackground,
    secondaryContainer = DarkWorkspaceBackground,
    tertiary = SuccessGreen
)

private val DarkPureBlackColorScheme = darkColorScheme(
    primary = TaupePrimary,
    secondary = WarmSandSecondary,
    background = PureBlackBackground,
    surface = PureBlackSurface,
    surfaceVariant = SurfaceGreenDark,
    onPrimary = TextPrimaryDark,
    onSecondary = TextPrimaryDark,
    onBackground = TextPrimaryDark,
    onSurface = TextPrimaryDark,
    primaryContainer = DarkWorkspaceBackground,
    secondaryContainer = DarkWorkspaceBackground,
    tertiary = SuccessGreen
)

private val DarkGreenAppleColorScheme = darkColorScheme(
    primary = GreenApplePrimaryDark,
    secondary = GreenAppleSecondaryDark,
    background = GreenAppleBackgroundDark,
    surface = GreenAppleSurfaceDark,
    surfaceVariant = SurfaceGreenDark,
    onPrimary = TextPrimaryDark,
    onSecondary = TextPrimaryDark,
    onBackground = TextPrimaryDark,
    onSurface = TextPrimaryDark,
    primaryContainer = GreenAppleContainerDark,
    secondaryContainer = GreenAppleContainerDark,
    tertiary = SuccessGreen
)

// Dynamic color schemes would be generated at runtime based on wallpaper
// For now, we use default schemes as placeholder
private val DynamicLightColorScheme = LightDefaultColorScheme
private val DynamicDarkColorScheme = DarkDefaultColorScheme

@Composable
fun MangaTranslatorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    colorSchemeName: String = "default",
    pureBlackDarkMode: Boolean = false,
    content: @Composable () -> Unit
) {
    val isDarkMode = darkTheme

    val resolvedColorScheme = when {
        isDarkMode && colorSchemeName == "green_apple" -> DarkGreenAppleColorScheme
        isDarkMode && pureBlackDarkMode -> DarkPureBlackColorScheme
        isDarkMode && colorSchemeName == "dynamic" -> DynamicDarkColorScheme
        isDarkMode -> DarkDefaultColorScheme
        colorSchemeName == "green_apple" -> LightGreenAppleColorScheme
        colorSchemeName == "dynamic" -> DynamicLightColorScheme
        else -> LightDefaultColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = resolvedColorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !isDarkMode
        }
    }

    MaterialTheme(
        colorScheme = resolvedColorScheme,
        typography = Typography,
        content = content
    )
}