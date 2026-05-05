package com.sakuravillager.manga_translator.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.sakuravillager.manga_translator.data.preferences.AppPreferences
import com.sakuravillager.manga_translator.data.preferences.PreferencesProvider
import com.sakuravillager.manga_translator.ui.components.TopAppBarWithBack
import kotlinx.coroutines.launch

@Composable
fun TranslatorPlatformDetailScreen(
    platform: String,
    onBack: () -> Unit
) {
    val preferences by PreferencesProvider.repository.getPreferences().collectAsState(initial = AppPreferences())
    val repository = PreferencesProvider.repository
    val scope = rememberCoroutineScope()
    val platformSpec = translatorPlatformSpec(platform)

    if (platformSpec == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 8.dp)
        ) {
            TopAppBarWithBack(
                title = "Translator Platform",
                onBack = onBack
            )
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
            ) {
                Text(
                    text = "Unknown platform: $platform",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        return
    }

    var apiKey by remember(platform, preferences.apiKey) { mutableStateOf(preferences.apiKey ?: "") }
    var apiBase by remember(platform, preferences.apiBase) { mutableStateOf(preferences.apiBase ?: "") }
    var modelName by remember(platform, preferences.modelName) { mutableStateOf(preferences.modelName ?: "") }
    var baiduAppId by remember(platform, preferences.baiduAppId) { mutableStateOf(preferences.baiduAppId ?: "") }
    var baiduSecretKey by remember(platform, preferences.baiduSecretKey) { mutableStateOf(preferences.baiduSecretKey ?: "") }
    var youdaoAppKey by remember(platform, preferences.youdaoAppKey) { mutableStateOf(preferences.youdaoAppKey ?: "") }
    var youdaoAppSecret by remember(platform, preferences.youdaoAppSecret) { mutableStateOf(preferences.youdaoAppSecret ?: "") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 8.dp)
    ) {
        TopAppBarWithBack(
            title = platformSpec.title,
            onBack = onBack
        )

        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = platformSpec.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            when (platformSpec.id) {
                "gpt_compatible" -> {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("API Key") },
                        placeholder = { Text("sk-...") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = apiBase,
                        onValueChange = { apiBase = it },
                        label = { Text("API Base URL (Optional)") },
                        placeholder = { Text("https://api.openai.com/v1") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = modelName,
                        onValueChange = { modelName = it },
                        label = { Text("Model Name (Optional)") },
                        placeholder = { Text("gpt-4o, gpt-4o-mini, etc.") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                "deepl" -> {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("DeepL Auth Key") },
                        placeholder = { Text("Your DeepL API key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                }

                "baidu" -> {
                    OutlinedTextField(
                        value = baiduAppId,
                        onValueChange = { baiduAppId = it },
                        label = { Text("Baidu App ID") },
                        placeholder = { Text("App ID") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = baiduSecretKey,
                        onValueChange = { baiduSecretKey = it },
                        label = { Text("Baidu Secret Key") },
                        placeholder = { Text("Secret Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                }

                "youdao" -> {
                    OutlinedTextField(
                        value = youdaoAppKey,
                        onValueChange = { youdaoAppKey = it },
                        label = { Text("Youdao App Key") },
                        placeholder = { Text("App Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = youdaoAppSecret,
                        onValueChange = { youdaoAppSecret = it },
                        label = { Text("Youdao App Secret") },
                        placeholder = { Text("App Secret") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    scope.launch {
                        when (platformSpec.id) {
                            "gpt_compatible" -> {
                                if (apiKey.isNotBlank()) {
                                    repository.updateApiKey(apiKey)
                                }
                                if (apiBase.isNotBlank()) {
                                    repository.updateApiBase(apiBase)
                                }
                                if (modelName.isNotBlank()) {
                                    repository.updateModelName(modelName)
                                }
                            }

                            "deepl" -> {
                                if (apiKey.isNotBlank()) {
                                    repository.updateApiKey(apiKey)
                                }
                            }

                            "baidu" -> {
                                repository.updateBaiduAppId(baiduAppId)
                                repository.updateBaiduSecretKey(baiduSecretKey)
                            }

                            "youdao" -> {
                                repository.updateYoudaoAppKey(youdaoAppKey)
                                repository.updateYoudaoAppSecret(youdaoAppSecret)
                            }
                        }
                        onBack()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save Config")
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

private data class TranslatorPlatformSpec(
    val id: String,
    val title: String,
    val description: String,
)

private fun translatorPlatformSpec(platform: String): TranslatorPlatformSpec? {
    return when (platform.lowercase()) {
        "gpt_compatible" -> TranslatorPlatformSpec(
            id = "gpt_compatible",
            title = "GPT-4 Vision",
            description = "Configure an OpenAI-compatible translator endpoint.",
        )
        "deepl" -> TranslatorPlatformSpec(
            id = "deepl",
            title = "DeepL",
            description = "Configure your DeepL API credentials.",
        )
        "baidu" -> TranslatorPlatformSpec(
            id = "baidu",
            title = "Baidu",
            description = "Configure Baidu Translate credentials.",
        )
        "youdao" -> TranslatorPlatformSpec(
            id = "youdao",
            title = "Youdao",
            description = "Configure Youdao Translate credentials.",
        )
        else -> null
    }
}
