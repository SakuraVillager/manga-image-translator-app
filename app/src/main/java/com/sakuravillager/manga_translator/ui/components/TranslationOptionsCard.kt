package com.sakuravillager.manga_translator.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sakuravillager.manga_translator.ui.theme.SuccessGreen

@Composable
fun TranslationOptionsCard(
    translatorType: String,
    textDirection: String,
    detectorType: String,
    ocrEngineType: String,
    onTranslatorClick: () -> Unit,
    onTextDirectionClick: () -> Unit,
    onDetectorClick: () -> Unit,
    onOcrEngineClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFFE1E5E1).copy(alpha = 0.5f)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Translator
            OptionRow(
                icon = Icons.Default.Translate,
                title = "Translator",
                currentValue = getTranslatorDisplayName(translatorType),
                onClick = onTranslatorClick
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Text Direction
            OptionRow(
                icon = Icons.Default.DocumentScanner,
                title = "Text Direction",
                currentValue = getTextDirectionDisplayName(textDirection),
                onClick = onTextDirectionClick
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Detector
            OptionRow(
                icon = Icons.Default.DocumentScanner,
                title = "Text Detector",
                currentValue = getDetectorDisplayName(detectorType),
                onClick = onDetectorClick
            )

            Spacer(modifier = Modifier.height(12.dp))

            // OCR Engine
            OptionRow(
                icon = Icons.Default.DocumentScanner,
                title = "OCR Engine",
                currentValue = getOcrDisplayName(ocrEngineType),
                onClick = onOcrEngineClick
            )
        }
    }
}

@Composable
private fun OptionRow(
    icon: ImageVector,
    title: String,
    currentValue: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = SuccessGreen
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = Color(0xFF424944)
            )
            Text(
                text = currentValue,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                fontWeight = FontWeight.Medium,
                color = Color(0xFF1A1C19)
            )
        }
    }
}

// Display name mappings
private fun getTranslatorDisplayName(type: String): String = when (type) {
    "gpt_compatible" -> "GPT-4 Vision"
    "deepl" -> "DeepL"
    "baidu" -> "Baidu"
    "youdao" -> "Youdao"
    "none" -> "None"
    "original" -> "Original"
    else -> type
}

private fun getTextDirectionDisplayName(type: String): String = when (type) {
    "auto", "auto_detect_vertical" -> "Auto Detect"
    "horizontal", "ltr" -> "Horizontal (LTR)"
    "vertical" -> "Vertical"
    "horizontal_rtl", "rtl" -> "Horizontal (RTL)"
    else -> type
}

private fun getDetectorDisplayName(type: String): String = when (type) {
    "ctd" -> "CTD"
    "default", "default_contour" -> "Default"
    "dbconvnext" -> "DBConvNext"
    "craft" -> "CRAFT"
    "paddle" -> "Paddle"
    "none" -> "None"
    else -> type
}

private fun getOcrDisplayName(type: String): String = when (type) {
    "model_48px" -> "Model 48px"
    "model_32px" -> "Model 32px"
    "model_48px_ctc" -> "Model 48px CTC"
    "mocr" -> "MOCR"
    "google_cloud_vision" -> "Google Cloud Vision"
    else -> type
}
