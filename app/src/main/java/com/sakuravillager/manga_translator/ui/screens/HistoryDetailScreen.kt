package com.sakuravillager.manga_translator.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.sakuravillager.manga_translator.ui.components.PillToggle
import com.sakuravillager.manga_translator.ui.components.TopAppBarWithBack
import com.sakuravillager.manga_translator.ui.viewmodel.HistoryViewModel

@Composable
fun HistoryDetailScreen(
    historyId: Long,
    onBack: () -> Unit,
    viewModel: HistoryViewModel = viewModel()
) {
    val history by viewModel.currentHistory.collectAsState()
    val viewState by viewModel.viewState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(historyId) {
        viewModel.loadHistory(historyId)
    }

    Scaffold(
        topBar = {
            TopAppBarWithBack(
                title = history?.let { "Ch. ${it.title} Result" } ?: "Result",
                onBack = onBack,
                actions = {
                    IconButton(onClick = {
                        Toast.makeText(context, "Download coming soon", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Download"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 3:4 aspect ratio image with rounded corners
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFFE1E5E1)),
                contentAlignment = Alignment.Center
            ) {
                val currentHistory = history
                if (currentHistory != null) {
                    val imageUri = when (viewState) {
                        com.sakuravillager.manga_translator.data.model.ViewState.SOURCE ->
                            currentHistory.imagePath
                        com.sakuravillager.manga_translator.data.model.ViewState.TRANSLATED ->
                            currentHistory.resultImagePath ?: currentHistory.coverImageUri
                    }
                    AsyncImage(
                        model = imageUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        text = "Loading...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Pill toggle
            PillToggle(
                currentState = viewState,
                onStateChange = { viewModel.setViewState(it) }
            )
        }
    }
}
