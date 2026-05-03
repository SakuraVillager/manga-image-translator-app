package com.sakuravillager.manga_translator.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sakuravillager.manga_translator.ui.components.SettingsListItem
import com.sakuravillager.manga_translator.ui.components.TopAppBarWithBack

@Composable
fun SettingsAboutScreen(
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 8.dp)
    ) {
        TopAppBarWithBack(
            title = "About",
            onBack = onBack
        )

        Column(
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            SettingsListItem(
                icon = Icons.Default.Info,
                title = "Version",
                subtitle = "v0.8.4-beta",
                onClick = {}
            )

            SettingsListItem(
                icon = Icons.Default.Link,
                title = "GitHub Repository",
                subtitle = "https://github.com/comictrans/comictrans",
                onClick = {}
            )
        }
    }
}