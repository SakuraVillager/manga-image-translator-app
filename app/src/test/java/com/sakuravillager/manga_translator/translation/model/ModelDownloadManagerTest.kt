package com.sakuravillager.manga_translator.translation.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlin.test.assertNotNull

/**
 * JVM unit tests for [ModelDownloadManager].
 *
 * Since ModelDownloadManager requires an Android Context in its constructor,
 * we use `sun.misc.Unsafe.allocateInstance()` to create an instance without
 * calling the constructor, then set fields via reflection.
 *
 * SHA-256 computation is verified both independently (via java.security.MessageDigest)
 * and via the class's private computeSha256 method.
 */
class ModelDownloadManagerTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = createTempDir("model-test-")
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // ── Unsafe-based factory (via reflection to avoid module-access issues) ──

    /**
     * Creates a [ModelDownloadManager] instance without calling its constructor.
     * Uses sun.misc.Unsafe via reflection to bypass the Android Context dependency.
     * Sets the `modelsDir` field to point to [tempDir] so that file-based
     * operations work correctly.
     */
    private fun createManager(modelsDir: File = tempDir): ModelDownloadManager {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafeField = unsafeClass.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null)
        val allocateMethod = unsafeClass.getMethod("allocateInstance", Class::class.java)
        val manager = allocateMethod.invoke(unsafe, ModelDownloadManager::class.java) as ModelDownloadManager

        // Set the modelsDir field so getModelFile() works
        val modelsDirField = ModelDownloadManager::class.java.getDeclaredField("modelsDir")
        modelsDirField.isAccessible = true
        modelsDirField.set(manager, modelsDir)

        // Initialize _downloadStatus (field initializers are skipped by Unsafe)
        val downloadStatusField = ModelDownloadManager::class.java.getDeclaredField("_downloadStatus")
        downloadStatusField.isAccessible = true
        downloadStatusField.set(manager, MutableStateFlow(DownloadStatus.Idle))

        val downloadStatusPublicField = ModelDownloadManager::class.java.getDeclaredField("downloadStatus")
        downloadStatusPublicField.isAccessible = true
        downloadStatusPublicField.set(manager, MutableStateFlow(DownloadStatus.Idle).asStateFlow())

        return manager
    }

    /**
     * Calls the private [ModelDownloadManager.computeSha256] method via reflection.
     */
    private fun callComputeSha256(manager: ModelDownloadManager, file: File): String {
        val method = ModelDownloadManager::class.java.getDeclaredMethod("computeSha256", File::class.java)
        method.isAccessible = true
        return method.invoke(manager, file) as String
    }

    // ── SHA-256 computation tests ─────────────────────────────────────

    @Test
    fun `computeSha256 matches known hash`() {
        val content = "Hello, World!"
        val expectedHash = "dffd6021bb2bd5b0af676290809ec3a53191dd81c7f70a4b28688a362182986f"

        val tempFile = File(tempDir, "test.bin").apply { writeText(content) }
        val manager = createManager()
        val actualHash = callComputeSha256(manager, tempFile)

        assertEquals(expectedHash, actualHash)
    }

    @Test
    fun `computeSha256 matches MessageDigest reference`() {
        val content = "The quick brown fox jumps over the lazy dog"

        val tempFile = File(tempDir, "test.bin").apply { writeText(content) }
        val manager = createManager()
        val actualHash = callComputeSha256(manager, tempFile)

        // Compute reference hash using java.security.MessageDigest
        val referenceHash = computeSha256Reference(tempFile)

        assertEquals(referenceHash, actualHash)
    }

    @Test
    fun `computeSha256 different files produce different hashes`() {
        val fileA = File(tempDir, "a.bin").apply { writeText("Content A") }
        val fileB = File(tempDir, "b.bin").apply { writeText("Content B") }
        val manager = createManager()

        val hashA = callComputeSha256(manager, fileA)
        val hashB = callComputeSha256(manager, fileB)

        assertNotEquals(hashA, hashB)
    }

    @Test
    fun `computeSha256 empty file`() {
        val emptyFile = File(tempDir, "empty.bin").apply { createNewFile() }
        val manager = createManager()
        val hash = callComputeSha256(manager, emptyFile)

        // SHA-256 of empty string
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            hash,
        )
    }

    @Test
    fun `computeSha256 binary content`() {
        val binaryContent = ByteArray(256) { it.toByte() }
        val binFile = File(tempDir, "binary.bin").apply { writeBytes(binaryContent) }
        val manager = createManager()
        val hash = callComputeSha256(manager, binFile)

        val referenceHash = computeSha256Reference(binFile)
        assertEquals(referenceHash, hash)
    }

    @Test
    fun `computeSha256 large content uses 8KB buffer`() {
        // 100KB of data — exercises the buffer-based streaming
        val largeContent = ByteArray(100_000) { (it % 256).toByte() }
        val largeFile = File(tempDir, "large.bin").apply { writeBytes(largeContent) }
        val manager = createManager()
        val hash = callComputeSha256(manager, largeFile)

        val referenceHash = computeSha256Reference(largeFile)
        assertEquals(referenceHash, hash)
    }

    // ── isModelReady tests ─────────────────────────────────────────────

    @Test
    fun `isModelReady returns false for non-existent model`() {
        val manager = createManager()
        // A model file that doesn't exist should return false
        assertFalse(manager.isModelReady("non_existent_model"))
    }

    @Test
    fun `isModelReady returns false for model not in registry`() {
        val manager = createManager()
        // Create a file but with a name not in ModelRegistry
        val unknownFile = File(tempDir, "unknown_model")
        unknownFile.writeText("some content")
        assertFalse(manager.isModelReady("unknown_model"))
    }

    @Test
    fun `isModelReady returns false when sha256 does not match`() {
        // Create a file that exists but has wrong content (hash won't match)
        val modelName = "ctd"
        val modelFile = File(tempDir, modelName)
        modelFile.writeText("wrong content")

        val manager = createManager()
        assertFalse(manager.isModelReady(modelName))
    }

    // ── getModelFile tests ─────────────────────────────────────────────

    @Test
    fun `getModelFile returns file in modelsDir`() {
        val manager = createManager()
        val file = manager.getModelFile("test_model")
        assertEquals(File(tempDir, "test_model"), file)
    }

    @Test
    fun `getModelFile does not create the file`() {
        val manager = createManager()
        val file = manager.getModelFile("uncreated")
        assertFalse(file.exists())
    }

    // ── verifySha256 tests ─────────────────────────────────────────────

    @Test
    fun `verifySha256 returns true for matching hash`() = runBlocking {
        val content = "verify me"

        val tempFile = File(tempDir, "verify.bin").apply { writeText(content) }
        val manager = createManager()

        val expectedHash = computeSha256Reference(tempFile)

        val result = manager.verifySha256(tempFile, expectedHash)
        assertTrue(result)
    }

    @Test
    fun `verifySha256 returns false for mismatching hash`() = runBlocking {
        val content = "verify me"
        val wrongHash = "0000000000000000000000000000000000000000000000000000000000000000"

        val tempFile = File(tempDir, "verify.bin").apply { writeText(content) }
        val manager = createManager()

        val result = manager.verifySha256(tempFile, wrongHash)
        assertFalse(result)
    }

    @Test
    fun `verifySha256 returns false for non-existent file`() = runBlocking {
        val nonExistent = File(tempDir, "ghost.bin")
        val manager = createManager()

        val result = manager.verifySha256(nonExistent, "anything")
        assertFalse(result)
    }

    // ── ModelInfo and ModelRegistry tests ──────────────────────────────

    @Test
    fun `ModelRegistry contains expected models`() {
        assertNotNull(ModelRegistry.getModel("comictextdetector"))
        assertNotNull(ModelRegistry.getModel("ocr_ctc_48px"))
        assertNotNull(ModelRegistry.getModel("alphabet_v5"))
        assertNotNull(ModelRegistry.getModel("noto_sans_cjk_kr_regular"))
        assertNotNull(ModelRegistry.getModel("aot_inpainting"))
        assertEquals(5, ModelRegistry.allModels.size)
    }

    @Test
    fun `ModelRegistry returns null for unknown model`() {
        assertNull(ModelRegistry.getModel("non_existent"))
    }

    @Test
    fun `ModelInfo data class properties`() {
        val info = ModelRegistry.CTD_MODEL
        assertEquals("comictextdetector", info.name)
        assertTrue(info.url.startsWith("https://"))
        assertEquals(64, info.sha256.length) // SHA-256 hex string is 64 chars
        assertTrue(info.sizeBytes > 0)
    }

    // ── Manager state ──────────────────────────────────────────────────

    @Test
    fun `downloadStatus initial state is Idle`() {
        val manager = createManager()
        assertEquals(DownloadStatus.Idle, manager.downloadStatus.value)
    }

    // ── Helpers ────────────────────────────────────────────────────────

    /**
     * Computes the SHA-256 hex digest of a file using the standard library —
     * mirrors the algorithm in [ModelDownloadManager.computeSha256].
     */
    private fun computeSha256Reference(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
