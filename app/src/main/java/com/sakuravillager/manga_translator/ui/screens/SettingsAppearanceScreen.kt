package com.sakuravillager.manga_translator.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sakuravillager.manga_translator.data.preferences.AppPreferences
import com.sakuravillager.manga_translator.data.preferences.PreferencesProvider
import com.sakuravillager.manga_translator.ui.components.ThemePreviewCard
import com.sakuravillager.manga_translator.ui.components.TopAppBarWithBack
import com.sakuravillager.manga_translator.ui.theme.GreenApplePrimary
import com.sakuravillager.manga_translator.ui.theme.SuccessGreen
import com.sakuravillager.manga_translator.ui.theme.TaupePrimary
import kotlinx.coroutines.launch

@Composable
fun SettingsAppearanceScreen(
    onBack: () -> Unit
) {
    val preferences by PreferencesProvider.repository.getPreferences().collectAsState(initial = AppPreferences())
    val repository = PreferencesProvider.repository
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 8.dp)
    ) {
        TopAppBarWithBack(
            title = "Appearance",
            onBack = onBack
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            // Theme Section
            Text(
                text = "Theme",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth()
            ) {
                val themes = listOf("System", "Light", "Dark")
                themes.forEachIndexed { index, theme ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = themes.size
                        ),
                        onClick = { scope.launch { repository.updateThemeMode(theme.lowercase()) } },
                        selected = preferences.themeMode == theme.lowercase()
                    ) {
                        Text(text = theme)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Color Scheme Section
            Text(
                text = "Color Scheme",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ThemePreviewCard(
                    name = "Default",
                    primaryColor = TaupePrimary,
                    isSelected = preferences.colorScheme == "default",
                    onClick = { scope.launch { repository.updateColorScheme("default") } },
                    modifier = Modifier.weight(1f)
                )

                ThemePreviewCard(
                    name = "Dynamic",
                    primaryColor = SuccessGreen,
                    isSelected = preferences.colorScheme == "dynamic",
                    onClick = { scope.launch { repository.updateColorScheme("dynamic") } },
                    modifier = Modifier.weight(1f)
                )

                ThemePreviewCard(
                    name = "Green Apple",
                    primaryColor = GreenApplePrimary,
                    isSelected = preferences.colorScheme == "green_apple",
                    onClick = { scope.launch { repository.updateColorScheme("green_apple") } },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Pure Black Dark Mode
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Pure Black Dark Mode",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Use pure black in dark theme",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Switch(
                    checked = preferences.pureBlackDarkMode,
                    onCheckedChange = { scope.launch { repository.updatePureBlackDarkMode(it) } }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // App Language
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "App Language",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "English",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Tablet Interface
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Tablet Interface",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Auto",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}
