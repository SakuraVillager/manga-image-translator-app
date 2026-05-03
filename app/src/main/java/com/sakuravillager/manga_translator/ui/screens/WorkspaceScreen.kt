package com.sakuravillager.manga_translator.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.sakuravillager.manga_translator.data.model.ViewState
import com.sakuravillager.manga_translator.ui.components.LanguageSelectorCard
import com.sakuravillager.manga_translator.ui.components.PillToggle
import com.sakuravillager.manga_translator.ui.components.TopAppBarWithBack
import com.sakuravillager.manga_translator.ui.theme.CardGreenBackground
import com.sakuravillager.manga_translator.ui.viewmodel.WorkspaceViewModel

@Composable
fun WorkspaceScreen(
    onBack: () -> Unit,
    viewModel: WorkspaceViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
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
                AsyncImage(
                    model = "https://picsum.photos/400/600",
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                // Translation bubbles overlay (only show when TRANSLATED)
                if (uiState.viewState == ViewState.TRANSLATED) {
                    Box(Modifier.offset(x = (0.1f * 300).dp, y = (0.2f * 400).dp)) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            shadowElevation = 2.dp,
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Text(
                                text = "What is this?",
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                    Box(Modifier.offset(x = (0.6f * 300).dp, y = (0.4f * 400).dp)) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            shadowElevation = 2.dp,
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Text(
                                text = "Amazing!",
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
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
