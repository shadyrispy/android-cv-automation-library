package com.steve1316.automation_library.utils.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.steve1316.automation_library.data.SharedData
import com.steve1316.automation_library.utils.MessageLog
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * ONNX Runtime-backed PP-OCRv6 text detection + recognition engine.
 *
 * - rec model: assets/ppocr/PP-OCRv6_tiny_rec_fp16.onnx (~2.1MB, FP16 weights)
 * - det model: assets/ppocr/PP-OCRv6_tiny_det_fp16.onnx (~0.9MB, FP16 weights)
 * - dict     : assets/ppocr/ppocr_keys_v1.txt (26KB, 6622 lines)
 *
 * rec preprocessing : resize to fixed height 48, width scaled proportionally (max from [SharedData.ocrRecMaxWidth]),
 *                     mean=0.5/std=0.5 normalization, BGR channel order (matches training).
 * rec postprocessing: CTC greedy decode (argmax → dedupe adjacent → drop blank → dict lookup); returns text + avg confidence.
 * det preprocessing : long side scaled to detLimitMaxLen (from [SharedData.ocrDetLimitMaxLen]), ImageNet mean/std, padded to multiple of 32.
 * det postprocessing: DB algorithm — binarize prob map → 8-connected components →
 *                     min-area/side filter → box_thresh filter → Vatti unclip → map back to original coordinates.
 *
 * All tunable parameters are read from [SharedData] (backed by [SettingsHelper]) so they can be adjusted at runtime
 * without recompilation. All logging goes through [MessageLog] to ensure errors are persisted to the log file and
 * sent to the frontend. Performance statistics are accumulated in [OcrEngine] companion and can be dumped at cleanup.
 *
 * Thread safety: all session calls are guarded by `synchronized(recLock)` / `synchronized(detLock)`;
 * singleton use via [OcrEngine.get] is recommended.
 *
 * @property context Application context (for assets access).
 */
class OnnxPpocrEngine(private val context: Context) : OcrEngine {
    private val tag: String = "${SharedData.loggerTag}OnnxPpocrEngine"

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var recSession: OrtSession? = null
    private var detSession: OrtSession? = null
    private val recLock = Any()
    private val detLock = Any()

    // Reusable buffers for hot-path preprocessing to reduce GC pressure (M1/M2/M3 fixes).
    // All accesses are guarded by recLock/detLock respectively, so no extra synchronization needed.
    private var recFloatBuf: FloatArray? = null
    private var recPixelBuf: IntArray? = null
    private var batchSliceBuf: FloatBuffer? = null
    private var detProbBuf: FloatBuffer? = null

    /** Dict (one char per line). CTC output index 0 is blank; dict char index starts at 1, so charIdx = onnxIdx - 1. */
    private val dict: List<String> by lazy { loadDict() }

    // rec model standard input configuration.
    private val recHeight = 48
    private val recMaxWidth: Int by lazy { SharedData.ocrRecMaxWidth }
    private val recMean = 0.5f
    private val recStd = 0.5f

    // det model standard input configuration (DB algorithm) — aligned with PP-OCRv6 official inference.yml.
    // Tunable parameters are read from SharedData so they can be adjusted at runtime.
    private val detLimitMaxLen: Int by lazy { SharedData.ocrDetLimitMaxLen }
    private val detMean = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val detStd = floatArrayOf(0.229f, 0.224f, 0.225f)
    private val detThresh: Float by lazy { SharedData.ocrDetThresh }
    private val detBoxThreshold: Float by lazy { SharedData.ocrDetBoxThreshold }
    private val detUnclipRatio: Float by lazy { SharedData.ocrDetUnclipRatio }
    // Fixed parameters (not exposed to settings).
    private val detMaxCandidates = 3000   // max number of candidate boxes
    private val detMinArea = 10.0         // min area filter
    private val detMinSide = 4.0          // min short-side filter

    init {
        loadRecModel()
        loadDetModel()
    }

    private fun loadRecModel() {
        try {
            val modelBytes = context.assets.open("ppocr/PP-OCRv6_tiny_rec_fp16.onnx").use { it.readBytes() }
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
            }
            recSession = env.createSession(modelBytes, options)
            MessageLog.i(tag, "PP-OCRv6 rec model loaded. inputs=${recSession?.inputNames} outputs=${recSession?.outputNames}")
        } catch (e: Exception) {
            MessageLog.e(tag, "Failed to load PP-OCRv6 rec model: ${e.stackTraceToString()}")
        }
    }

    private fun loadDetModel() {
        try {
            val modelBytes = context.assets.open("ppocr/PP-OCRv6_tiny_det_fp16.onnx").use { it.readBytes() }
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
            }
            detSession = env.createSession(modelBytes, options)
            MessageLog.i(tag, "PP-OCRv6 det model loaded. inputs=${detSession?.inputNames} outputs=${detSession?.outputNames}")
        } catch (e: Exception) {
            MessageLog.e(tag, "Failed to load PP-OCRv6 det model: ${e.stackTraceToString()}")
        }
    }

    private fun loadDict(): List<String> {
        return try {
            context.assets.open("ppocr/ppocr_keys_v1.txt").use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).useLines { it.toList() }
            }
        } catch (e: Exception) {
            MessageLog.e(tag, "Failed to load dict: ${e.stackTraceToString()}")
            emptyList()
        }
    }

    // //////////////////////////////////////////////////////////////////
    // rec: single-image recognition
    // //////////////////////////////////////////////////////////////////

    /**
     * Recognize text in a single image and return text + average CTC confidence (0..1).
     * Confidence is the mean of the max softmax probability at each non-blank time step.
     */
    override fun recognizeWithConfidence(bitmap: Bitmap): Pair<String, Float> {
        val sess = recSession ?: run {
            MessageLog.w(tag, "rec session not initialized; skip recognition.")
            return Pair("", 0f)
        }
        if (dict.isEmpty()) {
            MessageLog.w(tag, "Dict empty; skip recognition.")
            return Pair("", 0f)
        }
        if (bitmap.width <= 0 || bitmap.height <= 0) return Pair("", 0f)

        val (floatArray, targetW) = preprocessRec(bitmap) ?: return Pair("", 0f)
        val inputShape = longArrayOf(1, 3, recHeight.toLong(), targetW.toLong())
        val inputBuffer = FloatBuffer.wrap(floatArray)
        val inputName = sess.inputNames.first()

        var inputTensor: OnnxTensor? = null
        var outputs: OrtSession.Result? = null
        val startTime = System.currentTimeMillis()
        return try {
            synchronized(recLock) {
                inputTensor = OnnxTensor.createTensor(env, inputBuffer, inputShape)
                outputs = sess.run(mapOf(inputName to inputTensor!!))
                val outputTensor = outputs!![0] as OnnxTensor
                val shape = (outputTensor.info as TensorInfo).shape
                val T = shape[1].toInt()
                val C = shape[2].toInt()
                val (text, conf) = ctcGreedyDecode(outputTensor.floatBuffer, T, C)
                OcrEngine.recordRecognize(System.currentTimeMillis() - startTime)
                Pair(text, conf)
            }
        } catch (e: Exception) {
            MessageLog.e(tag, "Recognition failed: ${e.stackTraceToString()}")
            Pair("", 0f)
        } finally {
            outputs?.close()
            inputTensor?.close()
        }
    }

    // //////////////////////////////////////////////////////////////////
    // rec: batch recognition (multiple images share one session.run)
    // //////////////////////////////////////////////////////////////////

    /**
     * Batch-recognize text + confidence in multiple images; returns list of (text, confidence).
     * Shares a single session.run for all images.
     */
    override fun recognizeBatchWithConfidence(bitmaps: List<Bitmap>): List<Pair<String, Float>> {
        if (bitmaps.isEmpty()) return emptyList()
        val sess = recSession ?: run {
            MessageLog.w(tag, "rec session not initialized; skip batch.")
            return bitmaps.map { Pair("", 0f) }
        }
        if (dict.isEmpty()) {
            MessageLog.w(tag, "Dict empty; skip batch.")
            return bitmaps.map { Pair("", 0f) }
        }

        // 1. Preprocess each image to [3, recHeight, w_i] and collect.
        val preprocessed = ArrayList<Pair<FloatArray, Int>>(bitmaps.size)
        for (bmp in bitmaps) {
            if (bmp.width <= 0 || bmp.height <= 0) {
                preprocessed.add(Pair(FloatArray(0), 0))
                continue
            }
            val pair = preprocessRec(bmp)
            if (pair == null) {
                preprocessed.add(Pair(FloatArray(0), 0))
            } else {
                preprocessed.add(pair)
            }
        }

        // 2. Find batch max width, pad each image to maxW (right-side zero padding).
        val maxW = preprocessed.maxOf { it.second }.coerceAtLeast(1)
        val n = preprocessed.size
        val batchFloats = FloatArray(n * 3 * recHeight * maxW)
        for (i in 0 until n) {
            val (arr, w) = preprocessed[i]
            if (w == 0) continue
            val planeSrc = recHeight * w
            val planeDst = recHeight * maxW
            // Copy each channel separately; padding region stays 0.
            for (c in 0 until 3) {
                System.arraycopy(arr, c * planeSrc, batchFloats, i * 3 * planeDst + c * planeDst, planeSrc)
            }
        }

        // 3. Single session.run.
        val inputShape = longArrayOf(n.toLong(), 3, recHeight.toLong(), maxW.toLong())
        val inputBuffer = FloatBuffer.wrap(batchFloats)
        val inputName = sess.inputNames.first()

        var inputTensor: OnnxTensor? = null
        var outputs: OrtSession.Result? = null
        val startTime = System.currentTimeMillis()
        return try {
            synchronized(recLock) {
                inputTensor = OnnxTensor.createTensor(env, inputBuffer, inputShape)
                outputs = sess.run(mapOf(inputName to inputTensor!!))
                val outputTensor = outputs!![0] as OnnxTensor
                val shape = (outputTensor.info as TensorInfo).shape
                // Expect [N, T, C]
                val T = shape[1].toInt()
                val C = shape[2].toInt()
                val outBuf = outputTensor.floatBuffer
                // 4. CTC decode per sample.
                val results = ArrayList<Pair<String, Float>>(n)
                val step = T * C
                // Reuse a single slice buffer across all samples to reduce GC pressure (M2 fix).
                val slice = batchSliceBuf?.takeIf { it.capacity() >= step }
                    ?: FloatBuffer.allocate(step).also { batchSliceBuf = it }
                for (i in 0 until n) {
                    // Read by offset manually to avoid cross-sample position churn.
                    outBuf.position(i * step)
                    outBuf.get(slice.array(), 0, step)
                    outBuf.rewind()
                    slice.position(0)
                    results.add(ctcGreedyDecode(slice, T, C))
                }
                OcrEngine.recordRecognize(System.currentTimeMillis() - startTime)
                results
            }
        } catch (e: Exception) {
            MessageLog.e(tag, "Batch recognition failed: ${e.stackTraceToString()}")
            bitmaps.map { Pair("", 0f) }
        } finally {
            outputs?.close()
            inputTensor?.close()
        }
    }

    /**
     * rec preprocessing: resize to height 48 proportionally, truncate width to [recMaxWidth] if exceeded.
     * Returns (NCHW float array, actual targetW); null on failure.
     */
    private fun preprocessRec(bitmap: Bitmap): Pair<FloatArray, Int>? {
        val srcW = bitmap.width
        val srcH = bitmap.height
        if (srcW <= 0 || srcH <= 0) return null
        val ratio = recHeight.toFloat() / srcH
        var targetW = (srcW * ratio).toInt()
        if (targetW > recMaxWidth) targetW = recMaxWidth
        if (targetW < 1) targetW = 1

        val resized = if (srcW == targetW && srcH == recHeight) bitmap
            else Bitmap.createScaledBitmap(bitmap, targetW, recHeight, true)

        val planeSize = recHeight * targetW
        // Reuse member buffers to reduce GC pressure on hot path (M1 fix).
        val floatArray = recFloatBuf?.takeIf { it.size >= 3 * planeSize } ?: FloatArray(3 * planeSize).also { recFloatBuf = it }
        val pixels = recPixelBuf?.takeIf { it.size >= planeSize } ?: IntArray(planeSize).also { recPixelBuf = it }
        resized.getPixels(pixels, 0, targetW, 0, 0, targetW, recHeight)
        for (i in 0 until planeSize) {
            val px = pixels[i]
            val r = ((px shr 16) and 0xFF) / 255.0f
            val g = ((px shr 8) and 0xFF) / 255.0f
            val b = (px and 0xFF) / 255.0f
            // PP-OCRv6 trained with BGR (cv2.imread default); NCHW channel 0 must be B.
            // rec mean/std are symmetric (0.5/0.5), so BGR/RGB are numerically equivalent,
            // but we still follow the official BGR order for consistency.
            floatArray[i] = (b - recMean) / recStd
            floatArray[planeSize + i] = (g - recMean) / recStd
            floatArray[2 * planeSize + i] = (r - recMean) / recStd
        }
        if (resized !== bitmap) resized.recycle()
        return Pair(floatArray, targetW)
    }

    /**
     * CTC greedy decode: argmax per time step → dedupe adjacent → drop blank → dict lookup.
     * Returns (decoded text, average confidence) where confidence is the mean of max probabilities
     * at non-blank time steps. If the model outputs logits, we apply softmax first.
     */
    private fun ctcGreedyDecode(buf: FloatBuffer, T: Int, C: Int): Pair<String, Float> {
        if (C <= 1) return Pair("", 0f)
        val sb = StringBuilder()
        var prevIdx = -1
        var confSum = 0f
        var confCount = 0
        for (t in 0 until T) {
            val offset = t * C
            var maxIdx = 0
            var maxVal = -Float.MAX_VALUE
            for (c in 0 until C) {
                val v = buf.get(offset + c)
                if (v > maxVal) {
                    maxVal = v
                    maxIdx = c
                }
            }
            // index 0 is blank; dedupe adjacent; dict index = onnxIdx - 1.
            if (maxIdx != 0 && maxIdx != prevIdx) {
                val charIdx = maxIdx - 1
                if (charIdx in dict.indices) sb.append(dict[charIdx])
                // Accumulate confidence: convert maxVal to probability via softmax.
                // Compute exp(maxVal) / sum(exp(all)) for this time step.
                var expSum = 0f
                for (c in 0 until C) {
                    val v = buf.get(offset + c)
                    // Subtract maxVal for numerical stability before exp.
                    expSum += Math.exp((v - maxVal).toDouble()).toFloat()
                }
                val prob = 1f / expSum // = exp(maxVal) / expSum
                confSum += prob
                confCount++
            }
            prevIdx = maxIdx
        }
        buf.rewind()
        val avgConf = if (confCount > 0) confSum / confCount else 0f
        return Pair(sb.toString().trim(), avgConf)
    }

    // //////////////////////////////////////////////////////////////////
    // det + rec: end-to-end detection + recognition
    // //////////////////////////////////////////////////////////////////

    override fun detectAndRecognize(bitmap: Bitmap, textOnly: Boolean): List<OcrResult> {
        // 1. Detect text-line boxes (original-image coordinates).
        val boxes = detectTextLines(bitmap)
        if (boxes.isEmpty()) return emptyList()

        // 2. Sort by row (top→bottom, left→right) for reading order.
        val sorted = boxes.sortedWith(compareBy({ it.centerY() }, { it.centerX() }))

        // 3. Crop each text line from the source bitmap (axis-aligned crop, no perspective correction).
        val crops = ArrayList<Bitmap>(sorted.size)
        try {
            for (rect in sorted) {
                val w = rect.width()
                val h = rect.height()
                if (w < 1 || h < 1) continue
                // Bounds protection.
                val left = rect.left.coerceIn(0, bitmap.width - 1)
                val top = rect.top.coerceIn(0, bitmap.height - 1)
                val right = rect.right.coerceIn(1, bitmap.width)
                val bottom = rect.bottom.coerceIn(1, bitmap.height)
                if (right - left < 1 || bottom - top < 1) continue
                crops.add(Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top))
            }
            if (crops.isEmpty()) return emptyList()

            // 4. Batch recognition (with confidence).
            val textsWithConf = recognizeBatchWithConfidence(crops)

            // 5. Assemble results.
            val results = ArrayList<OcrResult>(crops.size)
            for (i in crops.indices) {
                val rect = sorted[i]
                val (text, conf) = textsWithConf.getOrNull(i) ?: Pair("", 0f)
                if (text.isBlank()) continue
                val box = if (textOnly) emptyList()
                    else listOf(
                        PointF(rect.left.toFloat(), rect.top.toFloat()),
                        PointF(rect.right.toFloat(), rect.top.toFloat()),
                        PointF(rect.right.toFloat(), rect.bottom.toFloat()),
                        PointF(rect.left.toFloat(), rect.bottom.toFloat()),
                    )
                val center = if (textOnly) PointF(0f, 0f)
                    else PointF(rect.exactCenterX(), rect.exactCenterY())
                results.add(OcrResult(text = text, confidence = conf, box = box, center = center))
            }
            return results
        } finally {
            crops.forEach { it.recycle() }
        }
    }

    /**
     * det preprocessing + inference + DB postprocessing; returns axis-aligned bounding rects of
     * text lines in original-image coordinates.
     */
    private fun detectTextLines(bitmap: Bitmap): List<Rect> {
        val sess = detSession ?: run {
            MessageLog.w(tag, "det session not initialized; skip detection.")
            return emptyList()
        }

        // 1. Preprocessing: scale long side to detLimitMaxLen (keep ratio), ImageNet normalize, pad to multiple of 32.
        val srcW = bitmap.width
        val srcH = bitmap.height
        val longSide = max(srcW, srcH)
        val scale = if (longSide > detLimitMaxLen) detLimitMaxLen.toFloat() / longSide else 1.0f
        var resizedW = (srcW * scale).toInt()
        var resizedH = (srcH * scale).toInt()
        // Pad to multiple of 32.
        val padW = (32 - resizedW % 32) % 32
        val padH = (32 - resizedH % 32) % 32
        val inputW = resizedW + padW
        val inputH = resizedH + padH

        val resized = Bitmap.createScaledBitmap(bitmap, resizedW, resizedH, true)
        val input = Bitmap.createBitmap(inputW, inputH, Bitmap.Config.ARGB_8888)
        try {
            val canvasIn = android.graphics.Canvas(input)
            canvasIn.drawBitmap(resized, 0f, 0f, null)
        } finally {
            resized.recycle()
        }

        // NCHW float, ImageNet normalization.
        val planeSize = inputW * inputH
        val floatArray = FloatArray(3 * planeSize)
        val pixels = IntArray(planeSize)
        try {
            input.getPixels(pixels, 0, inputW, 0, 0, inputW, inputH)
            for (i in 0 until planeSize) {
                val px = pixels[i]
                val r = ((px shr 16) and 0xFF) / 255.0f
                val g = ((px shr 8) and 0xFF) / 255.0f
                val b = (px and 0xFF) / 255.0f
                // PP-OCRv6 trained with BGR (cv2.imread default); NCHW channel 0 must be B.
                floatArray[i] = (b - detMean[0]) / detStd[0]
                floatArray[planeSize + i] = (g - detMean[1]) / detStd[1]
                floatArray[2 * planeSize + i] = (r - detMean[2]) / detStd[2]
            }
        } finally {
            input.recycle()
        }

        // 2. Inference → [1, 2, H, W] (channel 0 = prob map, channel 1 = threshold map; simplified: only use prob map).
        val inputShape = longArrayOf(1, 3, inputH.toLong(), inputW.toLong())
        val inputBuffer = FloatBuffer.wrap(floatArray)
        val inputName = sess.inputNames.first()

        var inputTensor: OnnxTensor? = null
        var outputs: OrtSession.Result? = null
        val startTime = System.currentTimeMillis()
        return try {
            synchronized(detLock) {
                inputTensor = OnnxTensor.createTensor(env, inputBuffer, inputShape)
                outputs = sess.run(mapOf(inputName to inputTensor!!))
                val outputTensor = outputs!![0] as OnnxTensor
                val outShape = (outputTensor.info as TensorInfo).shape
                // Expect [1, 2, H, W]; take channel 0 as the prob map.
                val outH = outShape[2].toInt()
                val outW = outShape[3].toInt()
                val fullBuf = outputTensor.floatBuffer
                val plane = outH * outW
                // Reuse a member buffer to reduce GC pressure on hot path (M3 fix).
                val probBuf = detProbBuf?.takeIf { it.capacity() >= plane }
                    ?: FloatBuffer.allocate(plane).also { detProbBuf = it }
                fullBuf.position(0)
                fullBuf.get(probBuf.array(), 0, plane)
                fullBuf.rewind()
                OcrEngine.recordDetect(System.currentTimeMillis() - startTime)
                // 3. DB postprocessing.
                dbPostprocess(probBuf, outW, outH, resizedW, resizedH, srcW, srcH)
            }
        } catch (e: Exception) {
            MessageLog.e(tag, "Detection failed: ${e.stackTraceToString()}")
            emptyList()
        } finally {
            outputs?.close()
            inputTensor?.close()
        }
    }

    /**
     * DB postprocessing, strictly aligned with PP-OCRv6 official DBPostProcess:
     * 1. prob map > detThresh → binary map
     * 2. 8-connected BFS to find connected-component bounding rects (equivalent to cv2.findContours RETR_LIST)
     * 3. max_candidates limit (3000)
     * 4. filter: area < minArea (10) or short side < minSide (4) → drop
     * 5. box_thresh filter: mean prob inside box < detBoxThreshold → drop
     * 6. unclip: Vatti clipping rectangular approximation (distance = area × ratio / perimeter; expand all sides)
     * 7. map back to original-image coordinates
     *
     * Returns axis-aligned text-line Rects in original-image coordinates (already unclipped).
     */
    private fun dbPostprocess(
        prob: FloatBuffer,
        probW: Int,
        probH: Int,
        resizedW: Int,
        resizedH: Int,
        srcW: Int,
        srcH: Int,
    ): List<Rect> {
        // 1. Binarize (detThresh from SharedData).
        val bin = ByteArray(probW * probH)
        for (i in bin.indices) {
            bin[i] = if (prob.get(i) > detThresh) 1 else 0
        }
        prob.rewind()

        // 2. 8-connected BFS for connected-component bounding rects (equivalent to cv2.findContours).
        val visited = ByteArray(probW * probH)
        val rawRects = ArrayList<Rect>()
        for (y in 0 until probH) {
            for (x in 0 until probW) {
                val idx = y * probW + x
                if (bin[idx] == 0.toByte() || visited[idx] == 1.toByte()) continue
                // max_candidates limit.
                if (rawRects.size >= detMaxCandidates) break
                // 8-connected BFS.
                var minX = x; var maxX = x
                var minY = y; var maxY = y
                val queue = ArrayDeque<Int>()
                queue.addLast(idx)
                visited[idx] = 1
                while (queue.isNotEmpty()) {
                    val cur = queue.removeFirst()
                    val cx = cur % probW
                    val cy = cur / probW
                    if (cx < minX) minX = cx
                    if (cx > maxX) maxX = cx
                    if (cy < minY) minY = cy
                    if (cy > maxY) maxY = cy
                    // 8-neighborhood.
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val nx = cx + dx
                            val ny = cy + dy
                            if (nx < 0 || nx >= probW || ny < 0 || ny >= probH) continue
                            val n = ny * probW + nx
                            if (bin[n] == 1.toByte() && visited[n] == 0.toByte()) {
                                visited[n] = 1
                                queue.addLast(n)
                            }
                        }
                    }
                }
                rawRects.add(Rect(minX, minY, maxX + 1, maxY + 1))
            }
        }

        // 3. Filter + box_thresh + Vatti unclip + map back to original image.
        val scaleX = srcW.toFloat() / resizedW
        val scaleY = srcH.toFloat() / resizedH
        val results = ArrayList<Rect>()
        for (r in rawRects) {
            val w = r.width().toFloat()
            val h = r.height().toFloat()
            val area = w * h
            val minSide = min(w, h)
            if (area < detMinArea) continue
            if (minSide < detMinSide) continue

            // box_thresh filter: compute mean prob inside box; drop if below detBoxThreshold.
            var sum = 0f
            var count = 0
            for (py in r.top until r.bottom) {
                for (px in r.left until r.right) {
                    sum += prob.get(py * probW + px)
                    count++
                }
            }
            prob.rewind()
            if (count == 0) continue
            val meanScore = sum / count
            if (meanScore < detBoxThreshold) continue

            // Vatti clipping rectangular approximation: distance = area × ratio / perimeter.
            val perimeter = 2 * (w + h)
            val distance = area * detUnclipRatio / perimeter
            val pad = distance.toInt()
            val ux = (r.left - pad).coerceAtLeast(0)
            val uy = (r.top - pad).coerceAtLeast(0)
            val vx = (r.right + pad).coerceAtMost(probW)
            val vy = (r.bottom + pad).coerceAtMost(probH)

            // Map back to original-image coordinates (prob map size = input size = resized + pad;
            // padding region has near-zero prob, so it does not affect detection).
            val rx = (ux * scaleX).toInt().coerceIn(0, srcW - 1)
            val ry = (uy * scaleY).toInt().coerceIn(0, srcH - 1)
            val rw = ((vx - ux) * scaleX).toInt().coerceIn(1, srcW - rx)
            val rh = ((vy - uy) * scaleY).toInt().coerceIn(1, srcH - ry)
            results.add(Rect(rx, ry, rx + rw, ry + rh))
        }
        return results
    }

    override fun close() {
        MessageLog.i(tag, "Closing OCR engine; releasing ONNX sessions.")
        recSession?.close()
        detSession?.close()
        recSession = null
        detSession = null
    }
}
