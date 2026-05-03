package com.sakuravillager.manga_translator.ui.screens

import android.os.Build
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.sakuravillager.manga_translator.data.logging.AppLogger
import com.sakuravillager.manga_translator.ui.components.SelectPhotoButton
import com.sakuravillager.manga_translator.ui.components.TopAppBarWithBack
import com.sakuravillager.manga_translator.ui.theme.SuccessGreen
import com.sakuravillager.manga_translator.ui.viewmodel.SelectPhotoViewModel

@Composable
fun SelectPhotoScreen(
    onBack: () -> Unit,
    onNavigateToWorkspace: () -> Unit,
    viewModel: SelectPhotoViewModel = viewModel()
) {
    val selectedImages by viewModel.selectedImages.collectAsState()

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
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    SelectPhotoButton(
                        selectedCount = selectedImages.size,
                        onClick = onNavigateToWorkspace
                    )
                }
            }
        }
    ) { paddingValues ->
        if (selectedImages.isEmpty()) {
            // Empty state
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
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
                    .fillMaxSize()
                    .padding(paddingValues)
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
