package com.sakuravillager.manga_translator.navigation

import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.sakuravillager.manga_translator.data.logging.AppLogger
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.sakuravillager.manga_translator.ui.screens.*

@Composable
fun AnimatedNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = AppRoutes.Home.route,
        modifier = modifier,
        enterTransition = {
            slideInHorizontally(initialOffsetX = { 300 }) + fadeIn(animationSpec = tween(200))
        },
        exitTransition = {
            slideOutHorizontally(targetOffsetX = { -300 }) + fadeOut(animationSpec = tween(200))
        },
        popEnterTransition = {
            slideInHorizontally(initialOffsetX = { -300 }) + fadeIn(animationSpec = tween(200))
        },
        popExitTransition = {
            slideOutHorizontally(targetOffsetX = { 300 }) + fadeOut(animationSpec = tween(200))
        }
    ) {
        // 1. Home
        composable(AppRoutes.Home.route) {
            LaunchedEffect(Unit) {
                AppLogger.d("Navigation", "Navigated to Home")
            }
            HomeScreen(
                onNavigate = { navController.navigate(AppRoutes.SelectPhoto.route) }
            )
        }

        // 2. History
        composable(AppRoutes.History.route) {
            LaunchedEffect(Unit) {
                AppLogger.d("Navigation", "Navigated to History")
            }
            HistoryScreen(
                onHistoryItemClick = { id: Long ->
                    navController.navigate("${AppRoutes.HistoryDetail.route}/$id")
                }
            )
        }

        // 3. History Detail
        composable(
            route = AppRoutes.HistoryDetail.route + "/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getLong("id") ?: 0L
            LaunchedEffect(Unit) {
                AppLogger.d("Navigation", "Navigated to HistoryDetail: id=$id")
            }
            HistoryDetailScreen(
                historyId = id,
                onBack = { navController.popBackStack() }
            )
        }

        // 4. Select Photo
        composable(AppRoutes.SelectPhoto.route) {
            LaunchedEffect(Unit) {
                AppLogger.d("Navigation", "Navigated to SelectPhoto")
            }
            SelectPhotoScreen(
                onBack = { navController.popBackStack() },
                onNavigateToWorkspace = { selectedImages ->
                    val encodedUris = Uri.encode(selectedImages.joinToString(separator = "\n") { it.toString() })
                    AppLogger.i(
                        "Navigation",
                        "Opening Workspace with ${selectedImages.size} selected images"
                    )
                    navController.navigate(AppRoutes.Workspace.route + "/$encodedUris")
                }
            )
        }

        // 5. Workspace
        composable(
            route = AppRoutes.Workspace.route + "/{imageUris}",
            arguments = listOf(navArgument("imageUris") { type = NavType.StringType })
        ) {
            val rawImageUris = it.arguments?.getString("imageUris").orEmpty()
            val imageUris = if (rawImageUris.isBlank()) {
                emptyList()
            } else {
                Uri.decode(rawImageUris)
                    .split('\n')
                    .filter { uri -> uri.isNotBlank() }
            }
            LaunchedEffect(Unit) {
                AppLogger.d("Navigation", "Navigated to Workspace with ${imageUris.size} image(s)")
            }
            WorkspaceScreen(
                imageUris = imageUris,
                onBack = { navController.popBackStack() }
            )
        }

        // 6. Settings
        composable(AppRoutes.Settings.route) {
            LaunchedEffect(Unit) {
                AppLogger.d("Navigation", "Navigated to Settings")
            }
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onNavigateToAppearance = { navController.navigate(AppRoutes.SettingsAppearance.route) },
                onNavigateToTranslation = { navController.navigate(AppRoutes.SettingsTranslation.route) },
                onNavigateToDebug = { navController.navigate(AppRoutes.SettingsDebug.route) },
                onNavigateToAbout = { navController.navigate(AppRoutes.SettingsAbout.route) }
            )
        }

        // 7. Settings Appearance
        composable(AppRoutes.SettingsAppearance.route) {
            LaunchedEffect(Unit) {
                AppLogger.d("Navigation", "Navigated to SettingsAppearance")
            }
            SettingsAppearanceScreen(onBack = { navController.popBackStack() })
        }

        // 8. Settings Translation
        composable(AppRoutes.SettingsTranslation.route) {
            LaunchedEffect(Unit) {
                AppLogger.d("Navigation", "Navigated to SettingsTranslation")
            }
            SettingsTranslationScreen(onBack = { navController.popBackStack() })
        }

        // 9. Settings Debug
        composable(AppRoutes.SettingsDebug.route) {
            LaunchedEffect(Unit) {
                AppLogger.d("Navigation", "Navigated to SettingsDebug")
            }
            SettingsDebugScreen(onBack = { navController.popBackStack() })
        }

        // 10. Settings About
        composable(AppRoutes.SettingsAbout.route) {
            LaunchedEffect(Unit) {
                AppLogger.d("Navigation", "Navigated to SettingsAbout")
            }
            SettingsAboutScreen(onBack = { navController.popBackStack() })
        }
    }
}
