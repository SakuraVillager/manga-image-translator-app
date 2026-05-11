package com.sakuravillager.manga_translator.translation.translator

import com.sakuravillager.manga_translator.translation.model.ModelDownloadManager
import com.sakuravillager.manga_translator.translation.model.ModelInfo
import com.sakuravillager.manga_translator.translation.onnx.OnnxSessionManager

/**
 * M2M100-1.2B multilingual neural machine translation model.
 *
 * Larger variant of [M2M100Translator] with 1.2B parameters (vs 418M).
 * Uses the same architecture and tokenizer, but with wider hidden layers
 * and more attention heads, providing better translation quality at the
 * cost of higher memory usage and slower inference.
 *
 * ### Differences from M2M100-418M
 *
 * | Property           | M2M100-418M | M2M100-1.2B  |
 * |--------------------|-------------|--------------|
 * | Parameters         | 418M        | 1.2B         |
 * | Hidden size        | 1024        | 2048         |
 * | Attention heads    | 8           | 16           |
 * | Encoder layers     | 6           | 12           |
 * | Decoder layers     | 6           | 12           |
 * | Download size (CT2)| ~818 MB     | ~2.4 GB      |
 *
 * The ONNX model files follow the same naming convention:
 * - `m2m100_12b.encoder.onnx`
 * - `m2m100_12b.decoder.onnx`
 * - `sentencepiece.model` (shared with the 418M variant)
 *
 * @param modelDownloadManager Used to download and verify model files.
 * @param onnxSessionManager  Singleton ONNX session factory.
 */
class M2M100BigTranslator(
    modelDownloadManager: ModelDownloadManager,
    onnxSessionManager: OnnxSessionManager,
) : M2M100Translator(modelDownloadManager, onnxSessionManager) {

    // ─── Model Info ───────────────────────────────────────────────────────

    /**
     * ONNX model metadata for M2M100-1.2B.
     *
     * **TODO**: Replace with the actual ONNX model URL after conversion.
     *
     * Upstream Python CT2 model:
     * - URL: https://github.com/zyddnys/manga-image-translator/releases/download/beta-0.3/m2m100_12b_ct2.zip
     * - SHA256: 742d5380c2837affd3680339145d37fc78f537ad633958347b76e9be9c577662
     */
    override val modelInfo: ModelInfo = ModelInfo(
        name = MODEL_NAME,
        url = "https://github.com/zyddnys/manga-image-translator/releases/download/beta-0.3/m2m100_12b_ct2.zip",
        sha256 = "742d5380c2837affd3680339145d37fc78f537ad633958347b76e9be9c577662",
        sizeBytes = 2_400_000_000L, // ~2.4 GB for the CT2 model; ONNX expected similar
    )

    // ─── Override model file names for 1.2B variant ───────────────────────

    override val encoderModelName: String = "$MODEL_NAME.encoder.onnx"
    override val decoderModelName: String = "$MODEL_NAME.decoder.onnx"

    // ─── Decoding parameters (tuned for larger model) ─────────────────────

    /**
     * Larger model benefits from slightly larger beam.
     */
    override val beamSize: Int = 5

    /**
     * Repetition penalty kept same as 418M variant.
     */
    override val repetitionPenalty: Float = 1.2f

    companion object {
        private const val TAG = "M2M100BigTranslator"

        /** Model name for M2M100-1.2B. */
        const val MODEL_NAME = "m2m100_12b"
    }
}
