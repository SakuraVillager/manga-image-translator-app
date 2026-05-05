package com.sakuravillager.manga_translator.ui.screens

import android.os.Build
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.sakuravillager.manga_translator.data.logging.AppLogger
import com.sakuravillager.manga_translator.ui.components.SelectPhotoButton
import com.sakuravillager.manga_translator.ui.components.TopAppBarWithBack
import com.sakuravillager.manga_translator.ui.components.TranslationOptionsCard
import com.sakuravillager.manga_translator.ui.theme.SuccessGreen
import com.sakuravillager.manga_translator.ui.viewmodel.SelectPhotoViewModel

@Composable
fun SelectPhotoScreen(
    onBack: () -> Unit,
    onNavigateToWorkspace: (List<Uri>) -> Unit,
    viewModel: SelectPhotoViewModel = viewModel()
) {
    val selectedImages by viewModel.selectedImages.collectAsState()
    val translationOptions by viewModel.translationOptions.collectAsState()

    var showTranslatorDialog by remember { mutableStateOf(false) }
    var showTextDirectionDialog by remember { mutableStateOf(false) }
    var showDetectorDialog by remember { mutableStateOf(false) }
    var showOcrDialog by remember { mutableStateOf(false) }

    // API 33+: PickMultipleVisualMedia (max 10)
    val pickMedia = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10)
    ) { uris ->
        try {
            AppLogger.i("SelectPhoto", "Selected ${uris.size} images")
            uris.forEach { uri -> viewModel.addImage(uri) }
        } catch (e: Exception) {
            AppLogger.e("SelectPhoto", "Photo selection failed", e)
        }
    }

    // API 28-32 fallback: GetMultipleContents
    val getContent = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        try {
            AppLogger.i("SelectPhoto", "Selected ${uris.size} images")
            uris.forEach { uri -> viewModel.addImage(uri) }
        } catch (e: Exception) {
            AppLogger.e("SelectPhoto", "Photo selection failed", e)
        }
    }

    Scaffold(
        topBar = {
            TopAppBarWithBack(
                title = "Select Image",
                onBack = onBack,
                actions = {
                    IconButton(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= 33) {
                                pickMedia.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            } else {
                                getContent.launch("image/*")
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add images"
                        )
                    }
                }
            )
        },
        bottomBar = {
            AnimatedVisibility(
                visible = selectedImages.isNotEmpty(),
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    SelectPhotoButton(
                        selectedCount = selectedImages.size,
                        onClick = {
                            AppLogger.i(
                                "SelectPhoto",
                                "Translate button clicked with ${selectedImages.size} images"
                            )
                            onNavigateToWorkspace(selectedImages)
                        }
                    )
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            // Translation Options Card
            TranslationOptionsCard(
                translatorType = translationOptions.translatorType,
                textDirection = translationOptions.textDirection,
                detectorType = translationOptions.detectorType,
                ocrEngineType = translationOptions.ocrEngineType,
                onTranslatorClick = { showTranslatorDialog = true },
                onTextDirectionClick = { showTextDirectionDialog = true },
                onDetectorClick = { showDetectorDialog = true },
                onOcrEngineClick = { showOcrDialog = true },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Image count info
            if (selectedImages.isEmpty()) {
                // Empty state
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Tap + to select images",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(selectedImages, key = { it.toString() }) { uri ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(3f / 4f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFFE1E5E1))
                                .border(2.dp, SuccessGreen, RoundedCornerShape(14.dp))
                                .clickable { viewModel.removeImage(uri) }
                        ) {
                            AsyncImage(
                                model = uri,
                                contentDescription = "Selected image",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                            // Selected overlay
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(SuccessGreen.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = Color.White,
                                    modifier = Modifier
                                        .size(32.dp)
                                        .background(SuccessGreen, CircleShape)
                                        .padding(4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // --- Dialogs ---

    if (showTranslatorDialog) {
        SettingsOptionDialog(
            title = "Select Translator",
            options = translatorOptionsList,
            currentValue = translationOptions.translatorType,
            onOptionSelected = { value ->
                viewModel.updateTranslatorType(value)
                showTranslatorDialog = false
            },
            onDismiss = { showTranslatorDialog = false }
        )
    }

    if (showTextDirectionDialog) {
        SettingsOptionDialog(
            title = "Select Text Direction",
            options = textDirectionOptionsList,
            currentValue = translationOptions.textDirection,
            onOptionSelected = { value ->
                viewModel.updateTextDirection(value)
                showTextDirectionDialog = false
            },
            onDismiss = { showTextDirectionDialog = false }
        )
    }

    if (showDetectorDialog) {
        SettingsOptionDialog(
            title = "Select Text Detector",
            options = detectorOptionsList,
            currentValue = translationOptions.detectorType,
            onOptionSelected = { value ->
                viewModel.updateDetectorType(value)
                showDetectorDialog = false
            },
            onDismiss = { showDetectorDialog = false }
        )
    }

    if (showOcrDialog) {
        SettingsOptionDialog(
            title = "Select OCR Engine",
            options = ocrOptionsList,
            currentValue = translationOptions.ocrEngineType,
            onOptionSelected = { value ->
                viewModel.updateOcrEngineType(value)
                showOcrDialog = false
            },
            onDismiss = { showOcrDialog = false }
        )
    }
}

private val translatorOptionsList = listOf(
    "GPT-4 Vision" to "gpt_compatible",
    "DeepL" to "deepl",
    "Baidu" to "baidu",
    "Youdao" to "youdao",
    "None" to "none",
    "Original" to "original"
)

private val textDirectionOptionsList = listOf(
    "Auto Detect" to "auto_detect_vertical",
    "Horizontal (LTR)" to "horizontal",
    "Vertical" to "vertical",
    "Horizontal (RTL)" to "horizontal_rtl"
)

private val detectorOptionsList = listOf(
    "CTD" to "ctd",
    "Default" to "default_contour",
    "DBConvNext" to "dbconvnext",
    "CRAFT" to "craft",
    "Paddle" to "paddle",
    "None" to "none"
)

private val ocrOptionsList = listOf(
    "Model 48px" to "model_48px",
    "Model 32px" to "model_32px",
    "Model 48px CTC" to "model_48px_ctc",
    "MOCR" to "mocr"
)

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
