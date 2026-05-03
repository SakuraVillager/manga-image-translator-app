package com.sakuravillager.manga_translator.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.sakuravillager.manga_translator.data.model.TranslationHistory
import com.sakuravillager.manga_translator.data.model.TranslationStatus
import com.sakuravillager.manga_translator.ui.theme.SuccessGreen
import java.util.concurrent.TimeUnit

@Composable
fun HistoryListItem(
    history: TranslationHistory,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail (3:4 aspect ratio, 64dp wide)
        Box(
            modifier = Modifier
                .width(64.dp)
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFFE1E5E1)),
            contentAlignment = Alignment.Center
        ) {
            if (history.coverImageUri != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(history.coverImageUri)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color(0xFFE1E5E1))
                )
            }
        }

        Spacer(modifier = Modifier.width(20.dp))

        // Text column: chapter title + time subtitle
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = history.title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 17.sp
                ),
                color = Color(0xFF1A1C19),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = getRelativeTimeString(history.translatedAt),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 13.5.sp
                ),
                color = Color(0xFF424944),
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Green dot + status text
        if (history.status == TranslationStatus.COMPLETED) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(SuccessGreen, CircleShape)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Finished",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    color = SuccessGreen
                )
            }
        }
    }
}

private fun getRelativeTimeString(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    val days = TimeUnit.MILLISECONDS.toDays(diff)
    return when {
        days == 0L -> "Translated today"
        days == 1L -> "Translated yesterday"
        days < 7 -> "Translated $days days ago"
        days < 30 -> "Translated ${days / 7} weeks ago"
        else -> "Translated ${days / 30} months ago"
    }
}
