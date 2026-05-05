package com.sakuravillager.manga_translator.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

sealed class AppRoutes(
    val route: String,
    val title: String? = null,
    val icon: ImageVector? = null
) {
    data object Home : AppRoutes("home", "Home", Icons.Default.Home)
    data object History : AppRoutes("history", "History", Icons.Default.History)
    data object HistoryDetail : AppRoutes("history_detail")
    data object SelectPhoto : AppRoutes("select_photo", "Select Photo", Icons.Default.Photo)
    data object Workspace : AppRoutes("workspace")
    data object Settings : AppRoutes("settings", "Settings", Icons.Default.Settings)
    data object SettingsAppearance : AppRoutes("settings_appearance")
    data object SettingsTranslation : AppRoutes("settings_translation")
    data object TranslatorConfig : AppRoutes("translator_config")
    data object TranslatorPlatformDetail : AppRoutes("translator_platform_detail")
    data object SettingsDebug : AppRoutes("settings_debug")
    data object SettingsAbout : AppRoutes("settings_about")

    companion object {
        val bottomNavItems = listOf(Home, History, SelectPhoto, Settings)

        fun translatorPlatformDetailRoute(platform: String): String {
            return "${TranslatorPlatformDetail.route}/$platform"
        }
    }
}