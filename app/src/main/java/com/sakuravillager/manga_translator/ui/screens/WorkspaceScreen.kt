package com.sakuravillager.manga_translator.ui.screens

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.sakuravillager.manga_translator.data.logging.AppLogger
import com.sakuravillager.manga_translator.data.model.ViewState
import com.sakuravillager.manga_translator.translation.pipeline.TranslationPipeline
import com.sakuravillager.manga_translator.translation.pipeline.TranslationProgress
import com.sakuravillager.manga_translator.ui.components.LanguageSelectorCard
import com.sakuravillager.manga_translator.ui.components.PillToggle
import com.sakuravillager.manga_translator.ui.components.TopAppBarWithBack
import com.sakuravillager.manga_translator.ui.theme.CardGreenBackground
import com.sakuravillager.manga_translator.ui.viewmodel.WorkspaceViewModel
import org.koin.java.KoinJavaComponent

@Composable
fun WorkspaceScreen(
    imageUris: List<String> = emptyList(),
    onBack: () -> Unit,
    viewModel: WorkspaceViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val pipeline = KoinJavaComponent.get<TranslationPipeline>(TranslationPipeline::class.java)
                return WorkspaceViewModel(pipeline) as T
            }
        }
    )
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(imageUris) {
        if (imageUris.isEmpty()) {
            AppLogger.w("WorkspaceScreen", "Workspace opened without image URIs")
        } else {
            AppLogger.i(
                "WorkspaceScreen",
                "Workspace opened with ${imageUris.size} image URI(s): ${imageUris.joinToString()}"
            )
            val uri = Uri.parse(imageUris.first())
            try {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    if (bitmap != null) {
                        viewModel.startTranslation(bitmap)
                    } else {
                        AppLogger.e("WorkspaceScreen", "Failed to decode bitmap from URI: $uri")
                    }
                }
            } catch (e: Exception) {
                AppLogger.e("WorkspaceScreen", "Error loading bitmap from URI: $uri", e)
            }
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBarWithBack(
                title = "Translation",
                onBack = onBack,
                actions = {
                    Button(
                        onClick = { viewModel.saveTranslation() },
                        shape = RoundedCornerShape(percent = 50),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CardGreenBackground
                        )
                    ) {
                        Text(
                            text = "Save",
                            color = Color(0xFF1A1C19),
                            fontSize = 13.sp
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Language Selector Card
            LanguageSelectorCard(
                onClick = { /* TODO: Open language selector dialog */ },
                language = uiState.selectedLanguage,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            // Manga Image with Translation Bubbles
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFFE1E5E1))
            ) {
                if (uiState.resultBitmap != null && uiState.viewState == ViewState.TRANSLATED) {
                    Image(
                        bitmap = uiState.resultBitmap!!.asImageBitmap(),
                        contentDescription = "Translated manga image",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    AsyncImage(
                        model = imageUris.firstOrNull(),
                        contentDescription = "Selected manga image",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Progress & Cancel area
            when (val progress = uiState.progress) {
                is TranslationProgress.Loading -> {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = progress.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                is TranslationProgress.Processing -> {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        LinearProgressIndicator(
                            progress = { progress.progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${(progress.progress * 100).toInt()}% - ${progress.message}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(onClick = { viewModel.cancelTranslation() }) {
                                Text("Cancel", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                is TranslationProgress.Done -> {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        LinearProgressIndicator(
                            progress = { 1f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "翻译完成 ✓",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                else -> { /* Idle — 不显示任何内容 */ }
            }

            // No text detected warning
            if (uiState.noTextDetected) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFFFF3E0),
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    Text(
                        text = "未在图片中检测到文字区域，原图已保留",
                        color = Color(0xFFE65100),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            // Error message display
            if (uiState.errorMessage != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFFFEBEE),
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    Text(
                        text = uiState.errorMessage!!,
                        color = Color(0xFFC62828),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            // Pill Toggle for Original / Translated
            PillToggle(
                currentState = uiState.viewState,
                onStateChange = { viewModel.setViewState(it) },
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 80.dp)
            )
        }
    }
}
