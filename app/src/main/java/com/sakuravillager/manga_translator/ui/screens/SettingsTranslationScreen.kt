package com.sakuravillager.manga_translator.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
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
    onBack: () -> Unit
) {
    val preferences by PreferencesProvider.repository.getPreferences().collectAsState(initial = AppPreferences())
    val repository = PreferencesProvider.repository
    val scope = rememberCoroutineScope()

    var showTranslatorDialog by remember { mutableStateOf(false) }
    var showTextDirectionDialog by remember { mutableStateOf(false) }
    var showDetectorDialog by remember { mutableStateOf(false) }
    var showOcrDialog by remember { mutableStateOf(false) }
    var showInpainterDialog by remember { mutableStateOf(false) }

    // Display name mappings for user-friendly subtitles
    val translatorNames = mapOf(
        "gpt_compatible" to "GPT-4 Vision",
        "deepl" to "DeepL",
        "baidu" to "Baidu",
        "youdao" to "Youdao",
        "none" to "None",
        "original" to "Original"
    )
    val textDirectionNames = mapOf(
        "auto" to "Auto Detect",
        "horizontal" to "Horizontal (LTR)",
        "vertical" to "Vertical",
        "horizontal_rtl" to "Horizontal (RTL)"
    )
    val detectorNames = mapOf(
        "ctd" to "CTD",
        "default" to "Default",
        "dbconvnext" to "DBConvNext",
        "craft" to "CRAFT",
        "paddle" to "Paddle",
        "none" to "None"
    )
    val ocrNames = mapOf(
        "model_48px" to "Model 48px",
        "model_32px" to "Model 32px",
        "model_48px_ctc" to "Model 48px CTC",
        "mocr" to "MOCR"
    )
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
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            SettingsListItem(
                icon = Icons.Default.Translate,
                title = "Translator",
                subtitle = translatorNames[preferences.translatorType] ?: preferences.translatorType,
                onClick = { showTranslatorDialog = true }
            )

            SettingsListItem(
                icon = Icons.Default.TextFields,
                title = "Text Direction",
                subtitle = textDirectionNames[preferences.textDirection] ?: preferences.textDirection,
                onClick = { showTextDirectionDialog = true }
            )

            SettingsListItem(
                icon = Icons.Default.DocumentScanner,
                title = "Text Detector",
                subtitle = detectorNames[preferences.detectorType] ?: preferences.detectorType,
                onClick = { showDetectorDialog = true }
            )

            SettingsListItem(
                icon = Icons.Default.DocumentScanner,
                title = "OCR Engine",
                subtitle = ocrNames[preferences.ocrEngineType] ?: preferences.ocrEngineType,
                onClick = { showOcrDialog = true }
            )

            SettingsListItem(
                icon = Icons.Default.Image,
                title = "Image Repair",
                subtitle = inpainterNames[preferences.inpainterType] ?: preferences.inpainterType,
                onClick = { showInpainterDialog = true }
            )
        }
    }

    // --- Dialogs ---

    if (showTranslatorDialog) {
        SettingsOptionDialog(
            title = "Select Translator",
            options = translatorNames.entries.map { it.value to it.key },
            currentValue = preferences.translatorType,
            onOptionSelected = { value ->
                scope.launch { repository.updateTranslatorType(value) }
                showTranslatorDialog = false
            },
            onDismiss = { showTranslatorDialog = false }
        )
    }

    if (showTextDirectionDialog) {
        SettingsOptionDialog(
            title = "Select Text Direction",
            options = textDirectionNames.entries.map { it.value to it.key },
            currentValue = preferences.textDirection,
            onOptionSelected = { value ->
                scope.launch { repository.updateTextDirection(value) }
                showTextDirectionDialog = false
            },
            onDismiss = { showTextDirectionDialog = false }
        )
    }

    if (showDetectorDialog) {
        SettingsOptionDialog(
            title = "Select Text Detector",
            options = detectorNames.entries.map { it.value to it.key },
            currentValue = preferences.detectorType,
            onOptionSelected = { value ->
                scope.launch { repository.updateDetectorType(value) }
                showDetectorDialog = false
            },
            onDismiss = { showDetectorDialog = false }
        )
    }

    if (showOcrDialog) {
        SettingsOptionDialog(
            title = "Select OCR Engine",
            options = ocrNames.entries.map { it.value to it.key },
            currentValue = preferences.ocrEngineType,
            onOptionSelected = { value ->
                scope.launch { repository.updateOcrEngineType(value) }
                showOcrDialog = false
            },
            onDismiss = { showOcrDialog = false }
        )
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
