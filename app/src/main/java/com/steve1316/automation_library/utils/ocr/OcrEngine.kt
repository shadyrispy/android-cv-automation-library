package com.steve1316.automation_library.utils.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF

/**
 * OCR engine abstraction.
 *
 * Concrete implementations (e.g. [OnnxPpocrEngine]) encapsulate model loading, preprocessing,
 * inference and postprocessing, exposing to callers:
 * - single-image recognition via [recognize] / [recognizeWithConfidence]
 * - multi-image batch recognition via [recognizeBatch] / [recognizeBatchWithConfidence] (shares one
 *   session.run, reducing total latency for N regions)
 * - end-to-end detect + recognize via [detectAndRecognize] (detects text boxes on the full image
 *   and recognizes each line)
 *
 * Confidence-aware variants return the average CTC probability (0..1) alongside the text. Callers
 * that need to filter low-quality reads (e.g. via `SharedData.ocrMinConfidence`) should prefer the
 * `*WithConfidence` methods; the plain `recognize` / `recognizeBatch` methods are kept as defaults
 * for backwards compatibility and simply drop the confidence value.
 */
interface OcrEngine {
    /**
     * Recognize text in a single image.
     *
     * @param bitmap Input image (already cropped/preprocessed by the caller).
     * @return Recognized text; empty string on no result or failure.
     */
    fun recognize(bitmap: Bitmap): String = recognizeWithConfidence(bitmap).first

    /**
     * Recognize text in a single image and return text + average CTC confidence (0..1).
     *
     * Default implementation returns `("", 0f)`; concrete engines should override to compute a real
     * confidence. Confidence is the mean of the max softmax probability at each non-blank time step.
     */
    fun recognizeWithConfidence(bitmap: Bitmap): Pair<String, Float> = Pair("", 0f)

    /**
     * Batch-recognize text in multiple images.
     *
     * Shares a single underlying session.run, significantly reducing N native calls and session
     * context switches compared to looping [recognize]. When input shapes differ, images are
     * internally padded to the batch max width (PP-OCRv6 rec is robust to right-side padding;
     * CTC outputs blank).
     *
     * @param bitmaps List of input images (each already cropped/preprocessed). Empty list returns empty list.
     * @return Recognized text per image, in the same order as the input.
     */
    fun recognizeBatch(bitmaps: List<Bitmap>): List<String> =
        recognizeBatchWithConfidence(bitmaps).map { it.first }

    /**
     * Batch-recognize text + confidence in multiple images; returns list of (text, confidence).
     * Default implementation delegates per-image to [recognizeWithConfidence] with 0f confidence;
     * concrete engines should override to share a single session.run.
     */
    fun recognizeBatchWithConfidence(bitmaps: List<Bitmap>): List<Pair<String, Float>> =
        bitmaps.map { recognizeWithConfidence(it) }

    /**
     * End-to-end "detect + recognize" on a full image.
     *
     * First detects text lines with the det model, then crops and batch-recognizes them with the
     * rec model. Returns each line's text, confidence, 4-point polygon (original-image coords),
     * and center point (for easy tapping).
     *
     * @param bitmap Input full image.
     * @param textOnly If true, returns only recognized text (no box/center), slightly faster; default false.
     * @return List of [OcrResult], sorted top→bottom, left→right.
     */
    fun detectAndRecognize(bitmap: Bitmap, textOnly: Boolean = false): List<OcrResult>

    /** Release native resources (ONNX sessions, dict, etc). */
    fun close()

    companion object {
        @Volatile
        private var instance: OcrEngine? = null

        // Performance statistics (cumulative across the bot session).
        @Volatile
        var totalRecognizeCalls: Long = 0
            private set
        @Volatile
        var totalRecognizeMs: Long = 0
            private set
        @Volatile
        var totalDetectCalls: Long = 0
            private set
        @Volatile
        var totalDetectMs: Long = 0
            private set

        /**
         * Get the default OCR engine singleton (ONNX Runtime + PP-OCRv6 Tiny det + rec).
         *
         * First call loads models from assets (det ~0.9MB + rec ~2.1MB) and can be slow; prefer
         * pre-initializing on a background thread (see BotService.onCreate warm-up).
         */
        fun get(context: Context): OcrEngine {
            return instance ?: synchronized(this) {
                instance ?: OnnxPpocrEngine(context.applicationContext).also { instance = it }
            }
        }

        /**
         * Release the singleton's native resources (ONNX sessions, dict).
         *
         * Should be called in [BotService.performCleanUp] to avoid native memory leaks.
         * After close, the next [get] call will re-create the engine.
         */
        fun close() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }

        /** Record a single recognize call's duration for performance statistics. */
        internal fun recordRecognize(elapsedMs: Long) {
            totalRecognizeCalls++
            totalRecognizeMs += elapsedMs
        }

        /** Record a single detect call's duration for performance statistics. */
        internal fun recordDetect(elapsedMs: Long) {
            totalDetectCalls++
            totalDetectMs += elapsedMs
        }

        /** Reset performance statistics; called at the start of each bot session. */
        fun resetStats() {
            totalRecognizeCalls = 0
            totalRecognizeMs = 0
            totalDetectCalls = 0
            totalDetectMs = 0
        }

        /** Format performance statistics as a human-readable summary string. */
        fun statsSummary(): String {
            val avgRec = if (totalRecognizeCalls > 0) totalRecognizeMs.toDouble() / totalRecognizeCalls else 0.0
            val avgDet = if (totalDetectCalls > 0) totalDetectMs.toDouble() / totalDetectCalls else 0.0
            return "OCR stats: rec=${totalRecognizeCalls}x/${totalRecognizeMs}ms (avg ${"%.1f".format(avgRec)}ms), det=${totalDetectCalls}x/${totalDetectMs}ms (avg ${"%.1f".format(avgDet)}ms)"
        }
    }
}

/**
 * End-to-end recognition result for a single text line.
 *
 * @property text Recognized text (blanks removed, adjacent duplicates collapsed).
 * @property confidence Recognition confidence (0..1); currently placeholder -1f (det+rec pipeline
 *   does not compute a separate confidence).
 * @property box 4-point polygon of the text line (original-image coords), clockwise:
 *   top-left → top-right → bottom-right → bottom-left. Empty when [textOnly] = true.
 * @property center Center point of the text line (original-image coords), for direct tap;
 *   (0, 0) when [textOnly] = true.
 */
data class OcrResult(
    val text: String,
    val confidence: Float = -1f,
    val box: List<PointF> = emptyList(),
    val center: PointF = PointF(0f, 0f),
)
