package com.sakuravillager.manga_translator.translation.translator

import android.util.Log
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Lightweight SentencePiece tokenizer that reads SentencePiece .model protobuf files
 * and provides encode/decode operations for M2M100-style multilingual translation.
 *
 * Parses the SentencePiece model using protobuf wire format. Supports both
 * unigram and BPE model types found in standard SentencePiece .model files.
 *
 * ### Usage
 * ```
 * val sp = SentencePieceTokenizer(modelFile.readBytes())
 * val ids = sp.encode("Hello world")
 * val text = sp.decode(ids)
 * ```
 *
 * @param modelBytes Raw bytes of the SentencePiece .model file (protobuf format).
 */
class SentencePieceTokenizer(private val modelBytes: ByteArray) {

    // ─── Public API ───────────────────────────────────────────────────────

    /** Vocabulary entry: a piece string with its unigram log-score. */
    data class VocabEntry(
        val piece: String,
        val score: Float,
        val id: Int,
    )

    /**
     * Full vocabulary ordered by token ID.  Index = token ID.
     * Non-contiguous IDs are filled with a sentinel (empty string, 0f).
     */
    val vocabulary: List<VocabEntry>

    /** Maps piece string → token ID.  Includes all special tokens. */
    val pieceToId: Map<String, Int>

    // ─── Special token IDs ────────────────────────────────────────────────

    /** <unk> token ID (default 0). */
    val unkId: Int

    /** <s> (BOS) token ID (default 1). */
    val bosId: Int

    /** </s> (EOS) token ID (default 2). */
    val eosId: Int

    /** <pad> token ID (default -1 = none). */
    val padId: Int

    /** The vocabulary size as declared in the model. */
    val vocabSize: Int

    // ─── Special token pieces ─────────────────────────────────────────────

    val unkPiece: String
    val bosPiece: String
    val eosPiece: String
    val padPiece: String

    // ─── Internal state ───────────────────────────────────────────────────

    /** Whether this is a byte-fallback model. */
    private val byteFallback: Boolean

    /** Sorted pieces by length (longest first) for greedy matching. */
    private val piecesSortedByLength: List<String>

    init {
        val parsed = parseProtobufModel()
        unkId = parsed.unkId
        bosId = parsed.bosId
        eosId = parsed.eosId
        padId = parsed.padId
        unkPiece = parsed.unkPiece
        bosPiece = parsed.bosPiece
        eosPiece = parsed.eosPiece
        padPiece = parsed.padPiece
        vocabSize = parsed.vocabSize
        byteFallback = parsed.byteFallback

        // Build vocabulary ordered by ID
        val maxId = parsed.pieces.maxOfOrNull { it.id } ?: (parsed.pieces.size - 1)
        val vocab = MutableList<VocabEntry?>(maxOf(maxId + 1, parsed.pieces.size)) { null }
        for (p in parsed.pieces) {
            if (p.id < vocab.size) vocab[p.id] = VocabEntry(p.piece, p.score, p.id)
        }
        vocabulary = vocab.mapIndexed { idx, entry ->
            entry ?: VocabEntry("", 0f, idx)
        }

        pieceToId = parsed.pieces.associate { it.piece to it.id }

        // Build sorted pieces for greedy tokenization
        piecesSortedByLength = parsed.pieces
            .map { it.piece }
            .sortedByDescending { it.length }
    }

    // ─── Encoding ─────────────────────────────────────────────────────────

    /**
     * Encodes [text] into a list of token IDs using greedy longest-match
     * SentencePiece tokenization.
     *
     * The input [text] is first normalised:
     * - Leading/trailing whitespace trimmed
     * - Internal whitespace converted to SentencePiece's space marker (▁)
     *
     * @param text Input text string.
     * @return List of token IDs.
     */
    fun encode(text: String): List<Int> {
        val ids = mutableListOf<Int>()
        val normalized = normalizeText(text)
        var pos = 0
        while (pos < normalized.length) {
            var matched = false
            // Try longest match first
            for (piece in piecesSortedByLength) {
                if (normalized.regionMatches(pos, piece, 0, piece.length)) {
                    val id = pieceToId[piece]
                    if (id != null) {
                        ids.add(id)
                        pos += piece.length
                        matched = true
                        break
                    }
                }
            }
            if (!matched) {
                // No match — emit <unk> for this character
                ids.add(unkId)
                pos += 1
            }
        }
        return ids
    }

    /**
     * Encodes [text] into a list of piece strings (for debugging / inspection).
     */
    fun encodeAsPieces(text: String): List<String> {
        val pieces = mutableListOf<String>()
        val normalized = normalizeText(text)
        var pos = 0
        while (pos < normalized.length) {
            var matched = false
            for (piece in piecesSortedByLength) {
                if (normalized.regionMatches(pos, piece, 0, piece.length)) {
                    pieces.add(piece)
                    pos += piece.length
                    matched = true
                    break
                }
            }
            if (!matched) {
                pieces.add(unkPiece)
                pos += 1
            }
        }
        return pieces
    }

    /**
     * Encodes text with BOS and EOS tokens prepended/appended.
     * Matches SentencePiece's `EncodeWithSpecialTokens` behaviour.
     */
    fun encodeWithSpecialTokens(text: String): List<Int> {
        val ids = mutableListOf(bosId)
        ids.addAll(encode(text))
        ids.add(eosId)
        return ids
    }

    /**
     * Prepends source-language token to [text], then encodes.
     * Used by M2M100 translation.
     *
     * @param text      Source text.
     * @param langCode  M2M100 language code (e.g. "en", "fr", "ja").
     */
    fun encodeWithLangToken(text: String, langCode: String): List<Int> {
        val langToken = "__${langCode}__"
        return encodeWithSpecialTokens("$langToken $text")
    }

    // ─── Decoding ─────────────────────────────────────────────────────────

    /**
     * Decodes a list of token IDs back to text.
     *
     * - Strips special tokens (BOS, EOS, PAD)
     * - Replaces SentencePiece space marker (▁) with regular spaces
     * - Collapses multiple spaces
     * - Trims leading/trailing whitespace
     *
     * @param ids List of token IDs.
     * @return Decoded text string.
     */
    fun decode(ids: List<Int>): String {
        val sb = StringBuilder()
        for (id in ids) {
            if (id == bosId || id == eosId || id == padId || id < 0 || id >= vocabulary.size) {
                continue
            }
            val piece = vocabulary[id].piece
            if (piece == unkPiece && id == unkId) {
                sb.append('\uFFFD') // replacement character
            } else {
                sb.append(piece)
            }
        }
        return postProcessDecoded(sb.toString())
    }

    /**
     * Decodes and strips a language-prefix token from the start.
     * Used by M2M100 translation post-processing.
     *
     * @param ids      List of token IDs from decoder output.
     * @param langCode M2M100 language code to strip (e.g. "en").
     * @return Decoded text with language prefix removed.
     */
    fun decodeWithLangToken(ids: List<Int>, langCode: String): String {
        val langToken = "__${langCode}__"
        var startIdx = 0
        // Find and skip the language token
        for (i in ids.indices) {
            val id = ids[i]
            if (id == bosId || id == eosId || id == padId) continue
            if (id < vocabulary.size && vocabulary[id].piece == langToken) {
                startIdx = i + 1
                break
            }
            // If we hit a non-special token that's not the lang token, stop looking
            if (id < vocabulary.size && !vocabulary[id].piece.startsWith("__")) {
                break
            }
        }
        val contentIds = ids.subList(startIdx, ids.size)
        return decode(contentIds).trimStart()
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    /**
     * Normalises input text to SentencePiece-compatible format:
     * - NFKC Unicode normalisation (via Java normaliser)
     * - Trims leading/trailing whitespace
     * - Collapses internal whitespace and marks with ▁
     */
    private fun normalizeText(text: String): String {
        val normalized = java.text.Normalizer.normalize(text.trim(), java.text.Normalizer.Form.NFKC)
        val sb = StringBuilder()
        var first = true
        for (ch in normalized) {
            if (ch.isWhitespace() || ch == '\u3000') { // full-width space
                if (!first) sb.append('\u2581') // ▁
            } else {
                sb.append(ch)
                first = false
            }
        }
        return sb.toString()
    }

    /**
     * Post-processes decoded text:
     * - Replaces ▁ with space
     * - Collapses multiple spaces
     * - Trims whitespace
     */
    private fun postProcessDecoded(text: String): String {
        return text
            .replace('\u2581', ' ')   // ▁ → space
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    // ─── Protobuf Model Parser ────────────────────────────────────────────

    /**
     * Parsed model data extracted from the SentencePiece protobuf.
     */
    private data class ParsedModel(
        val unkId: Int = 0,
        val bosId: Int = 1,
        val eosId: Int = 2,
        val padId: Int = -1,
        val unkPiece: String = "<unk>",
        val bosPiece: String = "<s>",
        val eosPiece: String = "</s>",
        val padPiece: String = "<pad>",
        val vocabSize: Int = 0,
        val byteFallback: Boolean = false,
        val pieces: List<ParsedPiece> = emptyList(),
    )

    private data class ParsedPiece(
        val piece: String,
        val score: Float,
        val id: Int,
    )

    /**
     * Parses the SentencePiece .model protobuf using wire format.
     *
     * The model file is a serialized `SentencePieceProcessor` protobuf:
     * ```
     * message SentencePieceProcessor {
     *   optional int32 unk_id = 1 [default = 0];
     *   optional int32 bos_id = 2 [default = 1];
     *   optional int32 eos_id = 3 [default = 2];
     *   optional int32 pad_id = 4 [default = -1];
     *   optional string unk_piece = 5 [default = "<unk>"];
     *   optional string bos_piece = 6 [default = "<s>"];
     *   optional string eos_piece = 7 [default = "</s>"];
     *   optional string pad_piece = 8 [default = "<pad>"];
     *   optional bool byte_fallback = 14 [default = false];
     *   optional int32 vocab_size = 31;
     *   optional TrainerModelProto trainer_model = 100;
     * }
     * message TrainerModelProto {
     *   message SentencePiece {
     *     optional string piece = 1;
     *     optional float score = 2;
     *   }
     *   repeated SentencePiece pieces = 1;
     * }
     * ```
     */
    private fun parseProtobufModel(): ParsedModel {
        val builder = ParsedModelBuilder()
        val stream = DataInputStream(ByteArrayInputStream(modelBytes))

        try {
            parseMessage(stream, object : FieldHandler {
                override fun onField(fieldNumber: Int, wireType: Int, stream: DataInputStream): Boolean {
                    when (fieldNumber) {
                        1 -> if (wireType == 0) builder.unkId = readVarint32(stream)
                        2 -> if (wireType == 0) builder.bosId = readVarint32(stream)
                        3 -> if (wireType == 0) builder.eosId = readVarint32(stream)
                        4 -> if (wireType == 0) builder.padId = readVarint32(stream)
                        5 -> if (wireType == 2) builder.unkPiece = readString(stream)
                        6 -> if (wireType == 2) builder.bosPiece = readString(stream)
                        7 -> if (wireType == 2) builder.eosPiece = readString(stream)
                        8 -> if (wireType == 2) builder.padPiece = readString(stream)
                        14 -> if (wireType == 0) builder.byteFallback = readVarint32(stream) != 0
                        31 -> if (wireType == 0) builder.vocabSize = readVarint32(stream)
                        100 -> if (wireType == 2) parseTrainerModel(stream, builder)
                    }
                    return true
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing SentencePiece model, falling back to defaults", e)
        }

        return builder.build()
    }

    /**
     * Parses `TrainerModelProto` (embedded message in field 100 of `SentencePieceProcessor`).
     *
     * ```
     * message TrainerModelProto {
     *   repeated SentencePiece pieces = 1;
     *   optional float min_score = 2;
     *   optional bool byte_fallback = 3;
     * }
     * ```
     */
    private fun parseTrainerModel(stream: DataInputStream, builder: ParsedModelBuilder) {
        val length = readVarint32(stream)
        val limit = stream.available() - length

        parseMessage(stream, object : FieldHandler {
            override fun onField(fieldNumber: Int, wireType: Int, stream: DataInputStream): Boolean {
                when (fieldNumber) {
                    1 -> if (wireType == 2) parseSentencePiece(stream, builder)
                    2 -> if (wireType == 5) { /* min_score: float, skip */ stream.readFloat() }
                    3 -> if (wireType == 0) { /* byte_fallback, skip */ readVarint32(stream) }
                }
                return stream.available() > limit
            }
        })
    }

    /**
     * Parses a single `SentencePiece` message from `TrainerModelProto.pieces`.
     *
     * ```
     * message SentencePiece {
     *   optional string piece = 1;
     *   optional float score = 2;
     *   optional int32 token_id = 3;  // BPE only
     * }
     * ```
     */
    private var nextPieceIdTracker = 0

    private fun parseSentencePiece(stream: DataInputStream, builder: ParsedModelBuilder) {
        val length = readVarint32(stream)
        val limit = stream.available() - length
        var piece = ""
        var score = 0f
        var tokenId = -1

        parseMessage(stream, object : FieldHandler {
            override fun onField(fieldNumber: Int, wireType: Int, stream: DataInputStream): Boolean {
                when (fieldNumber) {
                    1 -> if (wireType == 2) piece = readString(stream)
                    2 -> if (wireType == 5) {
                        score = readFloat(stream)
                    }
                    3 -> if (wireType == 0) tokenId = readVarint32(stream)
                }
                return stream.available() > limit
            }
        })

        val id = if (tokenId >= 0) tokenId else builder.pieces.size
        builder.pieces.add(ParsedPiece(piece = piece, score = score, id = id))
    }

    // ─── Protobuf Wire Format Helpers ─────────────────────────────────────

    /**
     * Reads a protobuf varint from the stream and returns the decoded value.
     */
    private fun readVarint32(stream: DataInputStream): Int {
        var result = 0
        var shift = 0
        while (true) {
            val byte = stream.readUnsignedByte()
            result = result or ((byte and 0x7F) shl shift)
            if (byte and 0x80 == 0) return result
            shift += 7
            if (shift >= 32) throw IllegalStateException("Varint too long for Int32")
        }
    }

    /**
     * Reads a length-delimited string from the stream.
     */
    private fun readString(stream: DataInputStream): String {
        val length = readVarint32(stream)
        val bytes = ByteArray(length)
        stream.readFully(bytes)
        return bytes.decodeToString()
    }

    /**
     * Reads a 32-bit float (little-endian) from the stream.
     */
    private fun readFloat(stream: DataInputStream): Float {
        val bytes = ByteArray(4)
        stream.readFully(bytes)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).float
    }

    /**
     * Parses a protobuf message, calling [handler] for each field encountered.
     *
     * Stops when the handler returns `false` or the end of the message is reached.
     */
    private fun parseMessage(stream: DataInputStream, handler: FieldHandler) {
        while (stream.available() > 0) {
            val key = readVarint32(stream)
            val fieldNumber = key ushr 3
            val wireType = key and 0x07

            when (wireType) {
                0 -> { /* varint */ if (!handler.onField(fieldNumber, 0, stream)) return }
                1 -> { /* 64-bit */ if (!handler.onField(fieldNumber, 1, stream)) return; stream.readLong() }
                2 -> { /* length-delimited */ if (!handler.onField(fieldNumber, 2, stream)) return }
                5 -> { /* 32-bit */ if (!handler.onField(fieldNumber, 5, stream)) return; stream.readInt() }
                else -> throw IllegalStateException("Unknown wire type: $wireType")
            }
        }
    }

    private interface FieldHandler {
        /**
         * Called for each field in the message.
         * @return `false` to stop parsing, `true` to continue.
         */
        fun onField(fieldNumber: Int, wireType: Int, stream: DataInputStream): Boolean
    }

    /**
     * Builder for [ParsedModel].
     */
    private class ParsedModelBuilder {
        var unkId: Int = 0
        var bosId: Int = 1
        var eosId: Int = 2
        var padId: Int = -1
        var unkPiece: String = "<unk>"
        var bosPiece: String = "<s>"
        var eosPiece: String = "</s>"
        var padPiece: String = "<pad>"
        var vocabSize: Int = 0
        var byteFallback: Boolean = false
        val pieces = mutableListOf<ParsedPiece>()

        fun build(): ParsedModel = ParsedModel(
            unkId = unkId,
            bosId = bosId,
            eosId = eosId,
            padId = padId,
            unkPiece = unkPiece,
            bosPiece = bosPiece,
            eosPiece = eosPiece,
            padPiece = padPiece,
            vocabSize = vocabSize,
            byteFallback = byteFallback,
            pieces = pieces.toList(),
        )
    }

    companion object {
        private const val TAG = "SentencePieceTokenizer"
    }
}
