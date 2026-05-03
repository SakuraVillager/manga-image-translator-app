package com.sakuravillager.manga_translator.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sakuravillager.manga_translator.ui.components.SettingsListItem

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToAppearance: () -> Unit,
    onNavigateToTranslation: () -> Unit,
    onNavigateToDebug: () -> Unit,
    onNavigateToAbout: () -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Text(
            text = "Settings",
            fontSize = 22.sp,
            fontWeight = FontWeight.Normal,
            modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 16.dp)
        )

        SettingsListItem(
            icon = Icons.Default.Palette,
            title = "Appearance",
            subtitle = "Theme, colors, language",
            onClick = onNavigateToAppearance
        )

        SettingsListItem(
            icon = Icons.Default.Translate,
            title = "Translation",
            subtitle = "Translator, OCR, text detection",
            onClick = {
                Toast.makeText(context, "[test] 设置项暂不可修改", Toast.LENGTH_SHORT).show()
            }
        )

        SettingsListItem(
            icon = Icons.Default.BugReport,
            title = "Debug & Logs",
            subtitle = "Export logs, clear cache",
            onClick = onNavigateToDebug
        )

        SettingsListItem(
            icon = Icons.Default.Info,
            title = "About",
            subtitle = "Version, GitHub, license",
            onClick = onNavigateToAbout
        )
    }
}