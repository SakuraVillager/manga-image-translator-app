package com.sakuravillager.manga_translator.translation.api

import java.io.File

/**
 * Unified interface for model download, load, unload, and inference.
 * Matches Python's ModelWrapper from utils/inference.py (L62-90).
 */
abstract class ModelWrapper {
    companion object {
        /** Root directory for all model files. */
        var MODEL_DIR: String = File("models").absolutePath
    }

    /** Sub-directory under MODEL_DIR for this model type */
    protected abstract val _MODEL_SUB_DIR: String

    /**
     * Maps model identifiers to download metadata.
     * Format: { "model_id" to ModelMetadata(url="...", hash="...", fileName="...") }
     */
    protected abstract val _MODEL_MAPPING: Map<String, ModelMetadata>

    /**
     * Downloads model files if not already present.
     * Matches Python: async download(force=False) (L79).
     */
    abstract suspend fun download(force: Boolean = false)

    /**
     * Loads model into memory/device.
     * Matches Python: async load(device, *args) (L84).
     */
    abstract suspend fun load(device: String, vararg args: Any?)

    /**
     * Unloads model from memory.
     * Matches Python: async unload() (L88).
     */
    abstract suspend fun unload()

    /**
     * Runs inference on the loaded model.
     * Matches Python: async infer(*args, **kwargs) → _infer(...) (L92).
     */
    abstract suspend fun infer(vararg args: Any?, kwargs: Map<String, Any?> = emptyMap()): Any?

    /**
     * Reloads the model (unload + load).
     * Matches Python: async reload(device, *args) (L96).
     */
    open suspend fun reload(device: String, vararg args: Any?) {
        unload()
        load(device, *args)
    }
}

data class ModelMetadata(
    val url: String,
    val hash: String? = null,
    val fileName: String,
)
