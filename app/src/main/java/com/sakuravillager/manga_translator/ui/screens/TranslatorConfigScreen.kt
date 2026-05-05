package com.sakuravillager.manga_translator.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sakuravillager.manga_translator.data.preferences.AppPreferences
import com.sakuravillager.manga_translator.data.preferences.PreferencesProvider
import com.sakuravillager.manga_translator.ui.components.TopAppBarWithBack
import com.sakuravillager.manga_translator.ui.theme.SuccessGreen
import kotlinx.coroutines.launch

@Composable
fun TranslatorConfigScreen(
    onBack: () -> Unit,
    onPlatformClick: (String) -> Unit
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
            title = "Translator Config",
            onBack = onBack
        )

        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "Choose a platform to configure it. Use the row action to enable it for translation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            translatorPlatformEntries.forEachIndexed { index, platform ->
                TranslatorListItem(
                    icon = platform.icon,
                    title = platform.title,
                    subtitle = platform.description,
                    isSelected = preferences.translatorType == platform.id,
                    onClick = { onPlatformClick(platform.id) },
                    onEnableClick = {
                        scope.launch {
                            repository.updateTranslatorType(platform.id)
                        }
                    }
                )

                if (index != translatorPlatformEntries.lastIndex) {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun TranslatorListItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    onEnableClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        color = if (isSelected) SuccessGreen.copy(alpha = 0.1f) else Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                tint = SuccessGreen
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp)
            ) {
                Text(
                    text = title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Normal,
                    color = Color(0xFF1A1C19)
                )
                Text(
                    text = subtitle,
                    fontSize = 13.5.sp,
                    color = if (isSelected) SuccessGreen else Color(0xFF424944),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = SuccessGreen,
                    modifier = Modifier.size(24.dp)
                )
            } else {
                TextButton(onClick = onEnableClick) {
                    Text("Enable")
                }
            }
        }
    }
}

private data class TranslatorPlatformEntry(
    val id: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
)

private val translatorPlatformEntries = listOf(
    TranslatorPlatformEntry(
        id = "gpt_compatible",
        title = "GPT-4 Vision",
        description = "OpenAI-compatible endpoint, key, base URL, model",
        icon = Icons.Default.Translate,
    ),
    TranslatorPlatformEntry(
        id = "deepl",
        title = "DeepL",
        description = "DeepL auth key and endpoint settings",
        icon = Icons.Default.Translate,
    ),
    TranslatorPlatformEntry(
        id = "baidu",
        title = "Baidu",
        description = "Baidu app id and secret key",
        icon = Icons.Default.Person,
    ),
    TranslatorPlatformEntry(
        id = "youdao",
        title = "Youdao",
        description = "Youdao app key and app secret",
        icon = Icons.Default.Person,
    ),
)
