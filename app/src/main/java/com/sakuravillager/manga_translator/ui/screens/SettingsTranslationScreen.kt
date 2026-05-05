package com.sakuravillager.manga_translator.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sakuravillager.manga_translator.data.preferences.AppPreferences
import com.sakuravillager.manga_translator.data.preferences.PreferencesProvider
import com.sakuravillager.manga_translator.ui.components.SettingsListItem
import com.sakuravillager.manga_translator.ui.components.TopAppBarWithBack
import kotlinx.coroutines.launch

@Composable
fun SettingsTranslationScreen(
    onBack: () -> Unit,
    onNavigateToTranslatorConfig: () -> Unit
) {
    val preferences by PreferencesProvider.repository.getPreferences().collectAsState(initial = AppPreferences())
    val repository = PreferencesProvider.repository
    val scope = rememberCoroutineScope()

    var showInpainterDialog by remember { mutableStateOf(false) }
    var showCtdUrlDialog by remember { mutableStateOf(false) }
    var showOcrUrlDialog by remember { mutableStateOf(false) }
    var showAlphaUrlDialog by remember { mutableStateOf(false) }
    var ctdUrl by remember(preferences.modelCtdUrl) { mutableStateOf(preferences.modelCtdUrl ?: "") }
    var ocrUrl by remember(preferences.modelOcrUrl) { mutableStateOf(preferences.modelOcrUrl ?: "") }
    var alphaUrl by remember(preferences.modelAlphabetUrl) { mutableStateOf(preferences.modelAlphabetUrl ?: "") }

    val inpainterNames = mapOf(
        "lama_large" to "Lama Large",
        "lama_mpe" to "Lama MPE",
        "aot" to "AOT",
        "simple_fill" to "Simple Fill",
        "none" to "None"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 8.dp)
    ) {
        TopAppBarWithBack(
            title = "Translation",
            onBack = onBack
        )

        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsListItem(
                icon = Icons.Default.Translate,
                title = "Translator Config",
                subtitle = "GPT-4 Vision, DeepL, Baidu, Youdao",
                onClick = onNavigateToTranslatorConfig
            )

            SettingsListItem(
                icon = Icons.Default.Image,
                title = "Image Repair",
                subtitle = inpainterNames[preferences.inpainterType] ?: preferences.inpainterType,
                onClick = { showInpainterDialog = true }
            )

        // ── Advanced: Model URL overrides ─────────────────────────────────
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Text(
            "Advanced — Model URLs (leave empty for defaults)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
        )

        SettingsListItem(
            icon = Icons.Default.Link,
            title = "CTD Model URL",
            subtitle = if (ctdUrl.isBlank()) "Default (upstream GitHub)" else ctdUrl,
            onClick = { showCtdUrlDialog = true }
        )
        SettingsListItem(
            icon = Icons.Default.Link,
            title = "OCR Model URL",
            subtitle = if (ocrUrl.isBlank()) "Default (bundled in assets)" else ocrUrl,
            onClick = { showOcrUrlDialog = true }
        )
        SettingsListItem(
            icon = Icons.Default.Link,
            title = "Alphabet URL",
            subtitle = if (alphaUrl.isBlank()) "Default (bundled in assets)" else alphaUrl,
            onClick = { showAlphaUrlDialog = true }
        )

        Spacer(Modifier.height(32.dp))
        }
    }

    if (showInpainterDialog) {
        SettingsOptionDialog(
            title = "Select Image Repair",
            options = inpainterNames.entries.map { it.value to it.key },
            currentValue = preferences.inpainterType,
            onOptionSelected = { value ->
                scope.launch { repository.updateInpainterType(value) }
                showInpainterDialog = false
            },
            onDismiss = { showInpainterDialog = false }
        )
    }

    // ── Advanced URL dialogs ──
    if (showCtdUrlDialog) {
        UrlInputDialog(
            title = "CTD Model URL",
            current = ctdUrl,
            onSave = { ctdUrl = it; scope.launch { repository.updateModelCtdUrl(it.ifBlank { null }) }; showCtdUrlDialog = false },
            onDismiss = { showCtdUrlDialog = false }
        )
    }
    if (showOcrUrlDialog) {
        UrlInputDialog(
            title = "OCR Model URL",
            current = ocrUrl,
            onSave = { ocrUrl = it; scope.launch { repository.updateModelOcrUrl(it.ifBlank { null }) }; showOcrUrlDialog = false },
            onDismiss = { showOcrUrlDialog = false }
        )
    }
    if (showAlphaUrlDialog) {
        UrlInputDialog(
            title = "Alphabet URL",
            current = alphaUrl,
            onSave = { alphaUrl = it; scope.launch { repository.updateModelAlphabetUrl(it.ifBlank { null }) }; showAlphaUrlDialog = false },
            onDismiss = { showAlphaUrlDialog = false }
        )
    }
}

@Composable
private fun SettingsOptionDialog(
    title: String,
    options: List<Pair<String, String>>,
    currentValue: String,
    onOptionSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                options.forEach { (display, value) ->
                    TextButton(
                        onClick = { onOptionSelected(value) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = display,
                            fontWeight = if (value == currentValue) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun UrlInputDialog(
    title: String,
    current: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(current) { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Download URL") },
                placeholder = { Text("Leave empty for default") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
