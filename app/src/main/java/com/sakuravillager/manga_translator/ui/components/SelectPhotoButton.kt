package com.sakuravillager.manga_translator.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sakuravillager.manga_translator.ui.theme.CardGreenBackground

@Composable
fun SelectPhotoButton(
    selectedCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = selectedCount > 0,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Button(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(percent = 50),
            colors = ButtonDefaults.buttonColors(
                containerColor = CardGreenBackground
            )
        ) {
            Text(
                text = "Translate Selected ($selectedCount)",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
