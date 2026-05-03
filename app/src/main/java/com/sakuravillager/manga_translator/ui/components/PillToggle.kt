package com.sakuravillager.manga_translator.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.sakuravillager.manga_translator.data.model.ViewState

@Composable
fun PillToggle(
    currentState: ViewState,
    onStateChange: (ViewState) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(Color(0xFFE1E5E1).copy(alpha = 0.5f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ViewState.entries.forEach { state ->
            val isSelected = currentState == state
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(percent = 50))
                    .then(
                        if (isSelected) Modifier
                            .background(Color.White)
                            .shadow(1.dp, RoundedCornerShape(percent = 50))
                        else Modifier.background(Color.Transparent)
                    )
                    .clickable { onStateChange(state) }
                    .padding(horizontal = 24.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (state == ViewState.SOURCE) "Original" else "Translated",
                    color = if (isSelected) Color(0xFF1A1C19) else Color(0xFF424944),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}