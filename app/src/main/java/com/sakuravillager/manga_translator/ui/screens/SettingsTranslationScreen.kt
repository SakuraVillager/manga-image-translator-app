package com.sakuravillager.manga_translator.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Translate
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sakuravillager.manga_translator.ui.components.SettingsListItem
import com.sakuravillager.manga_translator.ui.components.TopAppBarWithBack

@Composable
fun SettingsTranslationScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current

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
                subtitle = "GPT-4 Vision",
                onClick = {
                    Toast.makeText(context, "[test] 设置项暂不可修改", Toast.LENGTH_SHORT).show()
                }
            )

            SettingsListItem(
                icon = Icons.Default.TextFields,
                title = "Text Direction",
                subtitle = "Auto Detect Vertical",
                onClick = {
                    Toast.makeText(context, "[test] 设置项暂不可修改", Toast.LENGTH_SHORT).show()
                }
            )

            SettingsListItem(
                icon = Icons.Default.DocumentScanner,
                title = "Text Detector",
                subtitle = "Default Contour",
                onClick = {
                    Toast.makeText(context, "[test] 设置项暂不可修改", Toast.LENGTH_SHORT).show()
                }
            )

            SettingsListItem(
                icon = Icons.Default.DocumentScanner,
                title = "OCR Engine",
                subtitle = "Google Cloud Vision",
                onClick = {
                    Toast.makeText(context, "[test] 设置项暂不可修改", Toast.LENGTH_SHORT).show()
                }
            )

            SettingsListItem(
                icon = Icons.Default.Image,
                title = "Image Repair",
                subtitle = "Inpaint Lama",
                onClick = {
                    Toast.makeText(context, "[test] 设置项暂不可修改", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}