package com.sakuravillager.manga_translator.translation.translator

import android.util.Log
import com.sakuravillager.manga_translator.translation.model.ModelDownloadManager
import com.sakuravillager.manga_translator.translation.model.ModelInfo
import com.sakuravillager.manga_translator.translation.onnx.OnnxSessionManager

/**
 * NLLB-200-distilled-1.3B ONNX translator — larger variant of [NllbTranslator].
 *
 * Meta's No Language Left Behind model with 1.3B parameters, providing better
 * translation quality at the cost of higher memory usage and slower inference.
 *
 * ### Differences from NLLB-600M
 *
 * | Property           | NLLB-600M    | NLLB-1.3B    |
 * |--------------------|-------------|--------------|
 * | Parameters         | 600M        | 1.3B         |
 * | Hidden size        | 1024        | 2048         |
 * | Attention heads    | 8           | 16           |
 * | Encoder layers     | 12          | 24           |
 * | Decoder layers     | 12          | 24           |
 * | Download size      | ~600 MB     | ~1.3 GB      |
 *
 * The ONNX model files follow the same naming convention:
 * - `nllb_1.3b_encoder.onnx`
 * - `nllb_1.3b_decoder.onnx`
 * - `nllb_1.3b_tokenizer.spm` (typically shared with the 600M variant)
 *
 * The language code map, tokenization, and inference pipeline are all
 * inherited from [NllbTranslator].
 *
 * @param modelDownloadManager Used to download and verify model files.
 * @param onnxSessionManager  Singleton ONNX session factory.
 */
class NllbBigTranslator(
    modelDownloadManager: ModelDownloadManager,
    onnxSessionManager: OnnxSessionManager,
) : NllbTranslator(modelDownloadManager, onnxSessionManager) {

    companion object {
        private const val TAG = "NllbBigTranslator"

        /** Model name for NLLB-200-distilled-1.3B. */
        private const val BIG_MODEL_NAME = "nllb_1.3b"

        // ─── Model definitions ──────────────────────────────────────────────
        /**
         * Encoder ONNX model metadata for NLLB-200-distilled-1.3B.
         *
         * ⚠️ PLACEHOLDER — SHA-256 hash and URL are not yet finalised.
         */
        val NLLB_BIG_ENCODER_MODEL = ModelInfo(
            name = "${BIG_MODEL_NAME}_encoder",
            url = "https://github.com/zyddnys/manga-image-translator/releases/download/beta-0.3/${BIG_MODEL_NAME}_encoder.onnx",
            sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
            sizeBytes = 1_300_000_000L,
        )

        /**
         * Decoder ONNX model metadata for NLLB-200-distilled-1.3B.
         *
         * ⚠️ PLACEHOLDER — SHA-256 hash and URL are not yet finalised.
         */
        val NLLB_BIG_DECODER_MODEL = ModelInfo(
            name = "${BIG_MODEL_NAME}_decoder",
            url = "https://github.com/zyddnys/manga-image-translator/releases/download/beta-0.3/${BIG_MODEL_NAME}_decoder.onnx",
            sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
            sizeBytes = 1_300_000_000L,
        )

        /**
         * SentencePiece tokenizer model metadata for NLLB-200-distilled-1.3B.
         *
         * Typically shared with the 600M variant; defined separately for
         * flexibility if a different tokenizer is needed.
         *
         * ⚠️ PLACEHOLDER — SHA-256 hash and URL are not yet finalised.
         */
        val NLLB_BIG_TOKENIZER_MODEL = ModelInfo(
            name = "${BIG_MODEL_NAME}_tokenizer",
            url = "https://github.com/zyddnys/manga-image-translator/releases/download/beta-0.3/${BIG_MODEL_NAME}_tokenizer.spm",
            sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
            sizeBytes = 1_000_000L,
        )
    }
}
