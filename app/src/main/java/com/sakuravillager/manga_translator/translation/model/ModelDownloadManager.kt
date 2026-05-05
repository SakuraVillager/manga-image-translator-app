package com.sakuravillager.manga_translator.translation.model

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

sealed interface DownloadStatus {
    data object Idle : DownloadStatus
    data class Downloading(val progress: Float) : DownloadStatus
    data object Verifying : DownloadStatus
    data object Ready : DownloadStatus
    data class Error(val message: String) : DownloadStatus
}

class ModelDownloadManager(
    private val context: Context,
) {
    private val modelsDir: File = File(context.filesDir, "models")

    private val _downloadStatus = MutableStateFlow<DownloadStatus>(DownloadStatus.Idle)
    val downloadStatus: StateFlow<DownloadStatus> = _downloadStatus.asStateFlow()

    @Volatile
    private var currentDownloadConnection: HttpURLConnection? = null

    @Volatile
    private var isDownloadCancelled = false

    fun cancelDownload() {
        isDownloadCancelled = true
        currentDownloadConnection?.disconnect()
        currentDownloadConnection = null
        _downloadStatus.value = DownloadStatus.Idle
    }

    init {
        modelsDir.mkdirs()
    }

    /**
     * Returns the model file. If the file exists and its SHA-256 matches, returns it.
     * Otherwise downloads the model, verifies integrity, and returns the file.
     *
     * @param modelInfo   model descriptor with default URL and SHA-256
     * @param overrideUrl if non-null, overrides [ModelInfo.url] (e.g. from settings)
     */
    suspend fun ensureModel(modelInfo: ModelInfo, overrideUrl: String? = null): File = withContext(Dispatchers.IO) {
        val file = getModelFile(modelInfo.name)

        // Check if a valid file already exists
        if (file.exists()) {
            val actualHash = computeSha256(file)
            if (actualHash.equals(modelInfo.sha256, ignoreCase = true)) {
                _downloadStatus.value = DownloadStatus.Ready
                return@withContext file
            }
            // File is corrupted — delete it
            file.delete()
        }

        // Download the model (use override URL if provided)
        downloadModel(modelInfo, overrideUrl)

        file
    }

    /**
     * Checks whether the model file exists on disk and its SHA-256 matches.
     */
    fun isModelReady(modelName: String): Boolean {
        val file = getModelFile(modelName)
        if (!file.exists()) return false
        val modelInfo = ModelRegistry.getModel(modelName) ?: return false
        return try {
            computeSha256(file).equals(modelInfo.sha256, ignoreCase = true)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Computes the SHA-256 digest of a file and returns it as a lowercase hex string.
     */
    suspend fun verifySha256(file: File, expectedHash: String): Boolean = withContext(Dispatchers.IO) {
        try {
            computeSha256(file).equals(expectedHash, ignoreCase = true)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Deletes the model file and any partial download file.
     */
    suspend fun deleteModel(modelName: String) = withContext(Dispatchers.IO) {
        getModelFile(modelName).delete()
        getPartFile(modelName).delete()
    }

    /**
     * Returns a [File] reference for the given model name (no guarantee it exists).
     */
    fun getModelFile(modelName: String): File = File(modelsDir, modelName)

    // ------------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------------

    private fun getPartFile(modelName: String): File = File(modelsDir, "$modelName.part")

    /**
     * Synchronously computes the SHA-256 hash of a file.
     */
    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte)
        }
    }

    /**
     * Downloads a model from its URL to a `.part` file, verifies the SHA-256,
     * then renames the `.part` to the final name.
     *
     * @param modelInfo   model descriptor with default URL and SHA-256
     * @param overrideUrl if non-null, overrides [ModelInfo.url] (e.g. from settings)
     */
    private fun downloadModel(modelInfo: ModelInfo, overrideUrl: String? = null) {
        val partFile = getPartFile(modelInfo.name)
        val finalFile = getModelFile(modelInfo.name)
        val downloadUrl = overrideUrl ?: modelInfo.url
        isDownloadCancelled = false

        val existingBytes = if (partFile.exists()) partFile.length() else 0L
        _downloadStatus.value = DownloadStatus.Downloading(0f)

        val url = URL(downloadUrl)
        val connection = url.openConnection() as HttpURLConnection
        currentDownloadConnection = connection
        try {
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000

            if (existingBytes > 0L) {
                connection.setRequestProperty("Range", "bytes=$existingBytes-")
            }
            connection.connect()

            val responseCode = connection.responseCode
            val isResume = responseCode == HttpURLConnection.HTTP_PARTIAL
            val actualExistingBytes: Long
            if (!isResume && existingBytes > 0L) {
                partFile.delete()
                actualExistingBytes = 0L
            } else {
                actualExistingBytes = existingBytes
            }

            val contentLength = connection.contentLengthLong
            val totalBytes = if (isResume && contentLength > 0) {
                actualExistingBytes + contentLength
            } else {
                contentLength
            }

            connection.inputStream.use { input ->
                FileOutputStream(partFile, actualExistingBytes > 0L).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalRead = actualExistingBytes
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (totalBytes > 0) {
                            _downloadStatus.value = DownloadStatus.Downloading(
                                (totalRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                            )
                        }
                    }
                }
            }

            _downloadStatus.value = DownloadStatus.Verifying
            val actualHash = computeSha256(partFile)
            if (!actualHash.equals(modelInfo.sha256, ignoreCase = true)) {
                partFile.delete()
                val msg = "SHA-256 mismatch for ${modelInfo.name}: expected ${modelInfo.sha256}, got $actualHash"
                _downloadStatus.value = DownloadStatus.Error(msg)
                throw IOException(msg)
            }

            if (!partFile.renameTo(finalFile)) {
                finalFile.outputStream().use { out -> partFile.inputStream().use { inp -> inp.copyTo(out) } }
                partFile.delete()
            }
            _downloadStatus.value = DownloadStatus.Ready

        } catch (e: Exception) {
            if (isDownloadCancelled) {
                throw CancellationException("Download cancelled by user")
            }
            if (e is IOException && e.message?.startsWith("SHA-256 mismatch") == true) throw e
            _downloadStatus.value = DownloadStatus.Error(e.message ?: "Unknown error downloading ${modelInfo.name}")
            throw e
        } finally {
            if (currentDownloadConnection === connection) {
                currentDownloadConnection = null
            }
            connection.disconnect()
        }
    }
}
