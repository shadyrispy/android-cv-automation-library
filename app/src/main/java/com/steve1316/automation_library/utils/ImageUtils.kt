package com.steve1316.automation_library.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PointF
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.core.graphics.scale
import com.steve1316.automation_library.data.SharedData
import com.steve1316.automation_library.utils.ocr.OcrEngine
import com.steve1316.automation_library.utils.ocr.OcrResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.*
import kotlin.collections.ArrayList
import kotlin.math.abs
import kotlin.math.max

/**
 * Utility and helper functions for image processing via CV like OpenCV.
 *
 * @property context The application context.
 */
open class ImageUtils(protected val context: Context) {
    private val tag: String = "${SharedData.loggerTag}ImageUtils"

    protected open var matchMethod: Int = Imgproc.TM_CCOEFF_NORMED
    protected open var matchFilePath: String = ""
    protected open val decimalFormat = DecimalFormat("#.###", DecimalFormatSymbols(Locale.US))

    // Coordinates for swipe behavior to generate new images.
    private var oldXSwipe: Float = 500f
    private var oldYSwipe: Float = 500f
    private var newXSwipe: Float = 500f
    private var newYSwipe: Float = 400f
    private var durationSwipe: Long = 100L

    // //////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////
    // Use SharedPreferences or something else to set these values to what you want.
    open var confidence: Double = 0.8
    open var confidenceAll: Double = 0.8
    open var debugMode: Boolean = false
    open var customScale: Double = 1.0

    // //////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////
    // Device configuration
    open val displayWidth: Int = SharedData.displayWidth
    open val displayHeight: Int = SharedData.displayHeight
    open val is1080p: Boolean = (displayWidth == 1080) // 1080p Portrait
    open val is720p: Boolean = (displayWidth == 720) // 720p Portrait
    open val isTabletPortrait: Boolean = (displayWidth == 1600) // Galaxy Tab S7 1600x2560 Portrait Mode
    open val isTabletLandscape: Boolean = (displayWidth == 2560) // Galaxy Tab S7 1600x2560 Landscape Mode
    open val isTablet: Boolean = isTabletPortrait || isTabletLandscape

    // Scales (in terms of 720p and the dimensions from the Galaxy Tab S7)
    protected open val lowerEndScales: MutableList<Double> =
        generateSequence(0.50) { it + 0.01 }
            .takeWhile { it <= 0.70 }
            .toMutableList()
    protected open val middleEndScales: MutableList<Double> =
        generateSequence(0.50) { it + 0.01 }
            .takeWhile { it <= 3.00 }
            .toMutableList()
    protected open val tabletScales: MutableList<Double> =
        generateSequence(1.00) { it + 0.01 }
            .takeWhile { it <= 2.00 }
            .toMutableList()

    // Define template matching regions of the screen.
    open val regionTopHalf: IntArray = intArrayOf(0, 0, displayWidth, displayHeight / 2)
    open val regionBottomHalf: IntArray = intArrayOf(0, displayHeight / 2, displayWidth, displayHeight / 2)
    open val regionMiddle: IntArray = intArrayOf(0, displayHeight / 4, displayWidth, displayHeight / 2)

    // //////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////
    // OCR configuration

    // PP-OCRv6 ONNX Runtime engine (lazy singleton loaded via OcrEngine.get).
    // Replaces the previous ML Kit + Tesseract dual-engine setup.
    protected val ocrEngine: OcrEngine by lazy { OcrEngine.get(context) }

    init {
        // Set the match file path to the bot's internal temp folder.
        val tempMatchFilePath: String = context.filesDir.absolutePath + "/temp"
        Log.d(tag, "Setting the temp file path for ImageUtils to \"$tempMatchFilePath\".")
        matchFilePath = tempMatchFilePath
    }

    /**
     * Wait the specified seconds.
     *
     * @param seconds Number of seconds to pause execution.
     */
    protected open fun wait(seconds: Double) {
        runBlocking {
            delay((seconds * 1000).toLong())
        }
    }

    // //////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////

    data class ScaleConfidenceResult(
        val scale: Double,
        val confidence: Double,
    )

    // //////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////

    /**
     * Starts a test to determine what scales are working on this device by looping through some template images.
     *
     * @param mapping A mapping of template image names used to test and their lists of working scales to be modified in-place.
     * @return A mapping of template image names used to test and their lists of working scales.
     */
    open fun startTemplateMatchingTest(mapping: MutableMap<String, MutableList<ScaleConfidenceResult>>): MutableMap<String, MutableList<ScaleConfidenceResult>> {
        val defaultConfidence = 0.8
        val testScaleDecimalFormat = DecimalFormat("#.##")
        val testConfidenceDecimalFormat = DecimalFormat("#.##")

        for (key in mapping.keys) {
            val (sourceBitmap, templateBitmap) = getBitmaps(key)

            // First, try the default values of 1.0 for scale and 0.8 for confidence.
            val (success, _) = match(sourceBitmap, templateBitmap!!, key, useSingleScale = true, customConfidence = defaultConfidence, testScale = 1.0)
            if (success) {
                MessageLog.d(tag, "[TEST] Initial test for $key succeeded at the default values.")
                mapping[key]?.add(ScaleConfidenceResult(1.0, defaultConfidence))
                continue // If it works, skip to the next template.
            }

            // If not, try all scale/confidence combinations.
            val scalesToTest = mutableListOf<Double>()
            var scale = 0.5
            while (scale <= 3.0) {
                scalesToTest.add(testScaleDecimalFormat.format(scale).toDouble())
                scale += 0.1
            }

            for (testScale in scalesToTest) {
                var confidence = 0.6
                while (confidence <= 1.0) {
                    val formattedConfidence = testConfidenceDecimalFormat.format(confidence).toDouble()
                    val (testSuccess, _) = match(sourceBitmap, templateBitmap, key, useSingleScale = true, customConfidence = formattedConfidence, testScale = testScale)
                    if (testSuccess) {
                        MessageLog.d(tag, "[TEST] Test for $key succeeded at scale $testScale and confidence $formattedConfidence.")
                        mapping[key]?.add(ScaleConfidenceResult(testScale, formattedConfidence))
                    }
                    confidence += 0.1
                }
            }
        }

        return mapping
    }

    // //////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////
    // Template matching

    /**
     * Match between the source Bitmap from /files/temp/ and the template Bitmap from the assets folder.
     *
     * @param sourceBitmap Bitmap from the /files/temp/ folder.
     * @param templateBitmap Bitmap from the assets folder.
     * @param templateName Name of the template image to use in debugging log messages.
     * @param region Specify the region consisting of (x, y, width, height) of the source screenshot to template match. Defaults to (0, 0, 0, 0) which is equivalent to searching the full image.
     * @param useSingleScale Whether to use only the single custom scale or to use a range based off of it. Otherwise, it will use the customScale value. Defaults to false.
     * @param customConfidence Specify a custom confidence. Defaults to the confidence set in the app's settings.
     * @param testScale Scale used by testing. Defaults to 0.0 which will fallback to the other scale conditions.
     * @return Pair of (success: Boolean, location: Point?) where success indicates if a match was found and location contains the match coordinates if found.
     */
    protected open fun match(
        sourceBitmap: Bitmap,
        templateBitmap: Bitmap,
        templateName: String,
        region: IntArray = intArrayOf(0, 0, 0, 0),
        useSingleScale: Boolean = false,
        customConfidence: Double = 0.0,
        testScale: Double = 0.0,
    ): Pair<Boolean, Point?> {
        // If a custom region was specified, crop the source screenshot.
        val srcBitmap =
            if (!region.contentEquals(intArrayOf(0, 0, 0, 0))) {
                // Validate region bounds to prevent IllegalArgumentException with creating a crop area that goes beyond the source Bitmap.
                val x = max(0, region[0].coerceAtMost(sourceBitmap.width))
                val y = max(0, region[1].coerceAtMost(sourceBitmap.height))
                val width = region[2].coerceAtMost(sourceBitmap.width - x)
                val height = region[3].coerceAtMost(sourceBitmap.height - y)

                createSafeBitmap(sourceBitmap, x, y, width, height, "match region crop") ?: sourceBitmap
            } else {
                sourceBitmap
            }

        val setConfidence: Double =
            if (customConfidence == 0.0) {
                confidence
            } else {
                customConfidence
            }

        // Scale images if the device is not 1080p which is supported by default.
        val scales: MutableList<Double> =
            when {
                testScale != 0.0 -> {
                    mutableListOf(testScale)
                }
                customScale != 1.0 && !useSingleScale -> {
                    mutableListOf(customScale - 0.02, customScale - 0.01, customScale, customScale + 0.01, customScale + 0.02, customScale + 0.03, customScale + 0.04)
                }
                customScale != 1.0 && useSingleScale -> {
                    mutableListOf(customScale)
                }
                is720p -> {
                    lowerEndScales.toMutableList()
                }
                !is720p && !is1080p && !isTablet -> {
                    middleEndScales.toMutableList()
                }
                isTablet -> {
                    tabletScales.toMutableList()
                }
                else -> {
                    mutableListOf(1.0)
                }
            }

        while (scales.isNotEmpty()) {
            if (!BotService.isRunning) {
                throw InterruptedException()
            }

            val newScale: Double = decimalFormat.format(scales.removeAt(0)).toDouble()

            val tmp: Bitmap =
                if (newScale != 1.0) {
                    templateBitmap.scale((templateBitmap.width * newScale).toInt(), (templateBitmap.height * newScale).toInt())
                } else {
                    templateBitmap
                }

            // Create the Mats of both source and template images.
            val sourceMat = Mat()
            val templateMat = Mat()
            var clampedTemplateMat: Mat = templateMat
            var resultMat: Mat? = null
            try {
                Utils.bitmapToMat(srcBitmap, sourceMat)
                Utils.bitmapToMat(tmp, templateMat)

                // Clamp template dimensions to source dimensions if template is too large.
                clampedTemplateMat =
                    if (templateMat.cols() > sourceMat.cols() || templateMat.rows() > sourceMat.rows()) {
                        Log.d(tag, "Image sizes for match assertion failed - sourceMat: ${sourceMat.size()}, templateMat: ${templateMat.size()}")
                        // Create a new Mat with clamped dimensions.
                        val clampedWidth = minOf(templateMat.cols(), sourceMat.cols())
                        val clampedHeight = minOf(templateMat.rows(), sourceMat.rows())
                        Mat(templateMat, Rect(0, 0, clampedWidth, clampedHeight))
                    } else {
                        templateMat
                    }

                // Make the Mats grayscale for the source and the template.
                Imgproc.cvtColor(sourceMat, sourceMat, Imgproc.COLOR_BGR2GRAY)
                Imgproc.cvtColor(clampedTemplateMat, clampedTemplateMat, Imgproc.COLOR_BGR2GRAY)

                // Create the result matrix.
                val resultColumns: Int = sourceMat.cols() - clampedTemplateMat.cols() + 1
                val resultRows: Int = sourceMat.rows() - clampedTemplateMat.rows() + 1
                resultMat = Mat(resultRows, resultColumns, CvType.CV_32FC1)

                // Now perform the matching and localize the result.
                Imgproc.matchTemplate(sourceMat, clampedTemplateMat, resultMat, matchMethod)
                val mmr: Core.MinMaxLocResult = Core.minMaxLoc(resultMat)

                var matchLocation = Point()
                var matchCheck = false

                // Format minVal or maxVal.
                val minVal: Double = decimalFormat.format(mmr.minVal).toDouble()
                val maxVal: Double = decimalFormat.format(mmr.maxVal).toDouble()

                // Depending on which matching method was used, the algorithms determine which location was the best.
                if ((matchMethod == Imgproc.TM_SQDIFF || matchMethod == Imgproc.TM_SQDIFF_NORMED) && mmr.minVal <= (1.0 - setConfidence)) {
                    matchLocation = mmr.minLoc
                    matchCheck = true
                    if (debugMode) {
                        MessageLog.d(tag, "Match found for \"$templateName\" with $minVal <= ${1.0 - setConfidence} at Point $matchLocation using scale: $newScale.")
                    }
                } else if ((matchMethod != Imgproc.TM_SQDIFF && matchMethod != Imgproc.TM_SQDIFF_NORMED) && mmr.maxVal >= setConfidence) {
                    matchLocation = mmr.maxLoc
                    matchCheck = true
                    if (debugMode) {
                        MessageLog.d(tag, "Match found for \"$templateName\" with $maxVal >= $setConfidence at Point $matchLocation using scale: $newScale.")
                    }
                } else {
                    if (debugMode) {
                        if ((matchMethod != Imgproc.TM_SQDIFF && matchMethod != Imgproc.TM_SQDIFF_NORMED)) {
                            MessageLog.d(tag, "Match not found for \"$templateName\" with $maxVal not >= $setConfidence at Point ${mmr.maxLoc} using scale $newScale.")
                        } else {
                            MessageLog.d(tag, "Match not found for \"$templateName\" with $minVal not <= ${1.0 - setConfidence} at Point ${mmr.minLoc} using scale $newScale.")
                        }
                    }
                }

                if (matchCheck) {
                    if (debugMode) {
                        // Draw a rectangle around the supposed best matching location and then save the match into a file in /files/temp/ directory. This is for debugging purposes to see if this
                        // algorithm found the match accurately or not.
                        if (matchFilePath != "") {
                            Imgproc.rectangle(sourceMat, matchLocation, Point(matchLocation.x + templateMat.cols(), matchLocation.y + templateMat.rows()), Scalar(0.0, 128.0, 0.0), 10)
                            Imgcodecs.imwrite("$matchFilePath/match.png", sourceMat)
                        }
                    }

                    // Center the coordinates so that any tap gesture would be directed at the center of that match location instead of the default
                    // position of the top left corner of the match location.
                    matchLocation.x += (templateMat.cols() / 2)
                    matchLocation.y += (templateMat.rows() / 2)

                    // If a custom region was specified, readjust the coordinates to reflect the fullscreen source screenshot.
                    if (!region.contentEquals(intArrayOf(0, 0, 0, 0))) {
                        matchLocation.x = sourceBitmap.width - (sourceBitmap.width - (region[0] + matchLocation.x))
                        matchLocation.y = sourceBitmap.height - (sourceBitmap.height - (region[1] + matchLocation.y))
                    }

                    return Pair(true, matchLocation)
                }
            } finally {
                // Release native Mat memory in all paths (success, failure, exception).
                sourceMat.release()
                templateMat.release()
                // clampedTemplateMat may alias templateMat when no clamping was needed; avoid double-free.
                if (clampedTemplateMat !== templateMat) clampedTemplateMat.release()
                resultMat?.release()
                // Release the scaled tmp Bitmap if it was newly created (not aliased to templateBitmap).
                if (tmp !== templateBitmap) tmp.recycle()
            }
        }

        // Release the region-cropped srcBitmap if it was newly created (not aliased to sourceBitmap).
        if (srcBitmap !== sourceBitmap) srcBitmap.recycle()

        return Pair(false, null)
    }

    /**
     * Search through the whole source screenshot for all matches to the template image.
     *
     * @param sourceBitmap Bitmap from the /files/temp/ folder.
     * @param templateBitmap Bitmap from the assets folder.
     * @param region Specify the region consisting of (x, y, width, height) of the source screenshot to template match. Defaults to (0, 0, 0, 0) which is equivalent to searching the full image.
     * @param customConfidence Specify a custom confidence. Defaults to the confidence set in the app's settings.
     * @return ArrayList of Point objects that represents the matches found on the source screenshot.
     */
    protected open fun matchAll(sourceBitmap: Bitmap, templateBitmap: Bitmap, region: IntArray = intArrayOf(0, 0, 0, 0), customConfidence: Double = 0.0): ArrayList<Point> {
        // Create a local matchLocations list for this method
        var matchLocation: Point
        val matchLocations = arrayListOf<Point>()

        // If a custom region was specified, crop the source screenshot.
        val srcBitmap =
            if (!region.contentEquals(intArrayOf(0, 0, 0, 0))) {
                // Validate region bounds to prevent IllegalArgumentException with creating a crop area that goes beyond the source Bitmap.
                val x = max(0, region[0].coerceAtMost(sourceBitmap.width))
                val y = max(0, region[1].coerceAtMost(sourceBitmap.height))
                val width = region[2].coerceAtMost(sourceBitmap.width - x)
                val height = region[3].coerceAtMost(sourceBitmap.height - y)

                createSafeBitmap(sourceBitmap, x, y, width, height, "matchAll region crop") ?: sourceBitmap
            } else {
                sourceBitmap
            }

        // Scale images if the device is not 1080p which is supported by default.
        val scales: MutableList<Double> =
            when {
                customScale != 1.0 -> {
                    mutableListOf(customScale - 0.02, customScale - 0.01, customScale, customScale + 0.01, customScale + 0.02, customScale + 0.03, customScale + 0.04)
                }
                is720p -> {
                    lowerEndScales.toMutableList()
                }
                !is720p && !is1080p && !isTablet -> {
                    middleEndScales.toMutableList()
                }
                isTablet -> {
                    tabletScales.toMutableList()
                }
                else -> {
                    mutableListOf(1.0)
                }
            }

        val setConfidence: Double =
            if (customConfidence == 0.0) {
                confidenceAll
            } else {
                customConfidence
            }

        var matchCheck = false
        var newScale = 0.0
        val sourceMat = Mat()
        val templateMat = Mat()
        var resultMat = Mat()
        var clampedTemplateMat: Mat? = null

        try {
            // Set templateMat at whatever scale it found the very first match for the next while loop.
            while (!matchCheck && scales.isNotEmpty()) {
            if (!BotService.isRunning) {
                throw InterruptedException()
            }

            newScale = decimalFormat.format(scales.removeAt(0)).toDouble()

            val tmp: Bitmap =
                if (newScale != 1.0) {
                    templateBitmap.scale((templateBitmap.width * newScale).toInt(), (templateBitmap.height * newScale).toInt())
                } else {
                    templateBitmap
                }

            // Create the Mats of both source and template images.
            Utils.bitmapToMat(srcBitmap, sourceMat)
            Utils.bitmapToMat(tmp, templateMat)
            // Release the scaled tmp Bitmap immediately after copying into the Mat (C3 fix).
            if (tmp !== templateBitmap) tmp.recycle()

            // Clamp template dimensions to source dimensions if template is too large.
            clampedTemplateMat =
                if (templateMat.cols() > sourceMat.cols() || templateMat.rows() > sourceMat.rows()) {
                    Log.d(tag, "Image sizes for matchAll assertion failed - sourceMat: ${sourceMat.size()}, templateMat: ${templateMat.size()}")
                    // Create a new Mat with clamped dimensions.
                    val clampedWidth = minOf(templateMat.cols(), sourceMat.cols())
                    val clampedHeight = minOf(templateMat.rows(), sourceMat.rows())
                    Mat(templateMat, Rect(0, 0, clampedWidth, clampedHeight))
                } else {
                    templateMat
                }

            // Make the Mats grayscale for the source and the template.
            Imgproc.cvtColor(sourceMat, sourceMat, Imgproc.COLOR_BGR2GRAY)
            Imgproc.cvtColor(clampedTemplateMat, clampedTemplateMat, Imgproc.COLOR_BGR2GRAY)

            // Create the result matrix.
            val resultColumns: Int = sourceMat.cols() - clampedTemplateMat.cols() + 1
            val resultRows: Int = sourceMat.rows() - clampedTemplateMat.rows() + 1
            if (resultColumns < 0 || resultRows < 0) {
                break
            }

            // 释放上一轮的 resultMat,避免重新赋值时泄漏 native 内存
            resultMat.release()
            resultMat = Mat(resultRows, resultColumns, CvType.CV_32FC1)

            // Now perform the matching and localize the result.
            Imgproc.matchTemplate(sourceMat, clampedTemplateMat, resultMat, matchMethod)
            val mmr: Core.MinMaxLocResult = Core.minMaxLoc(resultMat)

            // Depending on which matching method was used, the algorithms determine which location was the best.
            if ((matchMethod == Imgproc.TM_SQDIFF || matchMethod == Imgproc.TM_SQDIFF_NORMED) && mmr.minVal <= (1.0 - setConfidence)) {
                matchLocation = mmr.minLoc
                matchCheck = true

                // Draw a rectangle around the match on the source Mat. This will prevent false positives and infinite looping on subsequent matches.
                Imgproc.rectangle(sourceMat, matchLocation, Point(matchLocation.x + clampedTemplateMat.cols(), matchLocation.y + clampedTemplateMat.rows()), Scalar(0.0, 0.0, 0.0), 20)

                // Center the location coordinates and then save it.
                matchLocation.x += (clampedTemplateMat.cols() / 2)
                matchLocation.y += (clampedTemplateMat.rows() / 2)

                // If a custom region was specified, readjust the coordinates to reflect the fullscreen source screenshot.
                if (!region.contentEquals(intArrayOf(0, 0, 0, 0))) {
                    matchLocation.x = sourceBitmap.width - (sourceBitmap.width - (region[0] + matchLocation.x))
                    matchLocation.y = sourceBitmap.height - (sourceBitmap.height - (region[1] + matchLocation.y))
                }

                matchLocations.add(matchLocation)
            } else if ((matchMethod != Imgproc.TM_SQDIFF && matchMethod != Imgproc.TM_SQDIFF_NORMED) && mmr.maxVal >= setConfidence) {
                matchLocation = mmr.maxLoc
                matchCheck = true

                // Draw a rectangle around the match on the source Mat. This will prevent false positives and infinite looping on subsequent matches.
                Imgproc.rectangle(sourceMat, matchLocation, Point(matchLocation.x + clampedTemplateMat.cols(), matchLocation.y + clampedTemplateMat.rows()), Scalar(0.0, 0.0, 0.0), 20)

                // Center the location coordinates and then save it.
                matchLocation.x += (clampedTemplateMat.cols() / 2)
                matchLocation.y += (clampedTemplateMat.rows() / 2)

                // If a custom region was specified, readjust the coordinates to reflect the fullscreen source screenshot.
                if (!region.contentEquals(intArrayOf(0, 0, 0, 0))) {
                    matchLocation.x = sourceBitmap.width - (sourceBitmap.width - (region[0] + matchLocation.x))
                    matchLocation.y = sourceBitmap.height - (sourceBitmap.height - (region[1] + matchLocation.y))
                }

                matchLocations.add(matchLocation)
            }
        }

        // Loop until all other matches are found and break out when there are no more to be found.
        while (matchCheck) {
            if (!BotService.isRunning) {
                throw InterruptedException()
            }

            // Now perform the matching and localize the result.
            Imgproc.matchTemplate(sourceMat, clampedTemplateMat, resultMat, matchMethod)
            val mmr: Core.MinMaxLocResult = Core.minMaxLoc(resultMat)

            // Format minVal or maxVal.
            val minVal: Double = decimalFormat.format(mmr.minVal).toDouble()
            val maxVal: Double = decimalFormat.format(mmr.maxVal).toDouble()

            if (clampedTemplateMat != null && (matchMethod == Imgproc.TM_SQDIFF || matchMethod == Imgproc.TM_SQDIFF_NORMED) && mmr.minVal <= (1.0 - setConfidence)) {
                val tempMatchLocation: Point = mmr.minLoc

                // Draw a rectangle around the match on the source Mat. This will prevent false positives and infinite looping on subsequent matches.
                Imgproc.rectangle(sourceMat, tempMatchLocation, Point(tempMatchLocation.x + clampedTemplateMat.cols(), tempMatchLocation.y + clampedTemplateMat.rows()), Scalar(0.0, 0.0, 0.0), 20)

                if (debugMode) {
                    MessageLog.d(tag, "Match found with $minVal <= ${1.0 - setConfidence} at Point $tempMatchLocation with scale: $newScale.")
                    Imgcodecs.imwrite("$matchFilePath/matchAll.png", sourceMat)
                }

                // Center the location coordinates and then save it.
                tempMatchLocation.x += (clampedTemplateMat.cols() / 2)
                tempMatchLocation.y += (clampedTemplateMat.rows() / 2)

                // If a custom region was specified, readjust the coordinates to reflect the fullscreen source screenshot.
                if (!region.contentEquals(intArrayOf(0, 0, 0, 0))) {
                    tempMatchLocation.x = sourceBitmap.width - (sourceBitmap.width - (region[0] + tempMatchLocation.x))
                    tempMatchLocation.y = sourceBitmap.height - (sourceBitmap.height - (region[1] + tempMatchLocation.y))
                }

                if (!matchLocations.contains(tempMatchLocation) &&
                    !matchLocations.contains(Point(tempMatchLocation.x + 1.0, tempMatchLocation.y)) &&
                    !matchLocations.contains(Point(tempMatchLocation.x, tempMatchLocation.y + 1.0)) &&
                    !matchLocations.contains(Point(tempMatchLocation.x + 1.0, tempMatchLocation.y + 1.0))
                ) {
                    matchLocations.add(tempMatchLocation)
                } else if (matchLocations.contains(tempMatchLocation)) {
                    // Prevent infinite looping if the same location is found over and over again.
                    break
                }
            } else if (clampedTemplateMat != null && (matchMethod != Imgproc.TM_SQDIFF && matchMethod != Imgproc.TM_SQDIFF_NORMED) && mmr.maxVal >= setConfidence) {
                val tempMatchLocation: Point = mmr.maxLoc

                // Draw a rectangle around the match on the source Mat. This will prevent false positives and infinite looping on subsequent matches.
                Imgproc.rectangle(sourceMat, tempMatchLocation, Point(tempMatchLocation.x + clampedTemplateMat.cols(), tempMatchLocation.y + clampedTemplateMat.rows()), Scalar(0.0, 0.0, 0.0), 20)

                if (debugMode) {
                    MessageLog.d(tag, "Match found with $maxVal >= $setConfidence at Point $tempMatchLocation with scale: $newScale.")
                    Imgcodecs.imwrite("$matchFilePath/matchAll.png", sourceMat)
                }

                // Center the location coordinates and then save it.
                tempMatchLocation.x += (clampedTemplateMat.cols() / 2)
                tempMatchLocation.y += (clampedTemplateMat.rows() / 2)

                // If a custom region was specified, readjust the coordinates to reflect the fullscreen source screenshot.
                if (!region.contentEquals(intArrayOf(0, 0, 0, 0))) {
                    tempMatchLocation.x = sourceBitmap.width - (sourceBitmap.width - (region[0] + tempMatchLocation.x))
                    tempMatchLocation.y = sourceBitmap.height - (sourceBitmap.height - (region[1] + tempMatchLocation.y))
                }

                if (!matchLocations.contains(tempMatchLocation) &&
                    !matchLocations.contains(Point(tempMatchLocation.x + 1.0, tempMatchLocation.y)) &&
                    !matchLocations.contains(Point(tempMatchLocation.x, tempMatchLocation.y + 1.0)) &&
                    !matchLocations.contains(Point(tempMatchLocation.x + 1.0, tempMatchLocation.y + 1.0))
                ) {
                    matchLocations.add(tempMatchLocation)
                } else if (matchLocations.contains(tempMatchLocation)) {
                    // Prevent infinite looping if the same location is found over and over again.
                    break
                }
            } else {
                val tempMatchLocation =
                    if ((matchMethod == Imgproc.TM_SQDIFF || matchMethod == Imgproc.TM_SQDIFF_NORMED) && mmr.minVal <= (1.0 - setConfidence)) {
                        mmr.minLoc
                    } else {
                        mmr.maxLoc
                    }

                // Draw a rectangle around the match on the source Mat. This will prevent false positives and infinite looping on subsequent matches.
                Imgproc.rectangle(sourceMat, tempMatchLocation, Point(tempMatchLocation.x + templateMat.cols(), tempMatchLocation.y + templateMat.rows()), Scalar(0.0, 0.0, 0.0), 20)

                if (debugMode) {
                    if ((matchMethod == Imgproc.TM_SQDIFF || matchMethod == Imgproc.TM_SQDIFF_NORMED) && mmr.minVal > (1.0 - setConfidence)) {
                        MessageLog.d(tag, "Match not found with ${mmr.minVal} > ${(1.0 - setConfidence)} at Point $tempMatchLocation with scale: $newScale.")
                    } else if ((matchMethod != Imgproc.TM_SQDIFF && matchMethod != Imgproc.TM_SQDIFF_NORMED) && mmr.maxVal < setConfidence) {
                        MessageLog.d(tag, "Match not found with ${mmr.maxVal} < $setConfidence at Point $tempMatchLocation with scale: $newScale.")
                    }

                    Imgcodecs.imwrite("$matchFilePath/matchAll.png", sourceMat)
                }

                break
            }
        }

        return matchLocations
        } finally {
            // 确保 throw InterruptedException 或正常返回时都释放 native Mat
            sourceMat.release()
            templateMat.release()
            clampedTemplateMat?.release()
            resultMat.release()
            // Release the region-cropped srcBitmap if it was newly created (C5 fix).
            if (srcBitmap !== sourceBitmap) srcBitmap.recycle()
        }
    }

    // //////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////
    // Relative coordinate translation

    /**
     * Convert absolute x-coordinate from baseline resolution to relative coordinate for the current device.
     *
     * @param oldX The old absolute x-coordinate based off of the baseline resolution.
     * @return The new relative x-coordinate based off of the current resolution.
     */
    open fun relWidth(oldX: Int): Int {
        return if (SharedData.displayWidth.toInt() == SharedData.baselineWidth.toInt()) oldX else (oldX.toDouble() * (displayWidth.toDouble() / SharedData.baselineWidth)).toInt()
    }

    /**
     * Convert absolute y-coordinate from baseline resolution to relative coordinate for the current device.
     *
     * @param oldY The old absolute y-coordinate based off of the baseline resolution.
     * @return The new relative y-coordinate based off of the current resolution.
     */
    open fun relHeight(oldY: Int): Int {
        return if (SharedData.displayWidth.toInt() == SharedData.baselineWidth.toInt()) oldY else (oldY.toDouble() * (displayHeight.toDouble() / SharedData.baselineHeight)).toInt()
    }

    /**
     * Helper function to calculate the x-coordinate with relative offset.
     *
     * @param baseX The base x-coordinate.
     * @param offset The offset to add/subtract from the base coordinate and to make relative to.
     * @return The calculated relative x-coordinate.
     */
    open fun relX(baseX: Double, offset: Int): Int {
        return baseX.toInt() + relWidth(offset)
    }

    /**
     * Helper function to calculate relative y-coordinate with relative offset.
     *
     * @param baseY The base y-coordinate.
     * @param offset The offset to add/subtract from the base coordinate and to make relative to.
     * @return The calculated relative y-coordinate.
     */
    open fun relY(baseY: Double, offset: Int): Int {
        return baseY.toInt() + relHeight(offset)
    }

    // //////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////
    // Fetching Bitmaps

    /**
     * Open the source and template image files and return Bitmaps for them. Also executes swipes in order to generate new images if necessary.
     *
     * @param templateName File name of the template image.
     * @param templatePath Path name of the subfolder in /assets/ that the template image is in. Defaults to the default template subfolder path name.
     * @return A Pair of source and template Bitmaps.
     */
    open fun getBitmaps(templateName: String, templatePath: String = SharedData.templateSubfolderPathName): Pair<Bitmap, Bitmap?> {
        // Acquire the source bitmap. MediaProjectionService handles caching and retries internally,
        // so we no longer need to swipe aggressively to force new images.
        var sourceBitmap = MediaProjectionService.takeScreenshotNow()

        if (sourceBitmap == null) {
            Log.w(tag, "Source Bitmap is null on initial capture. Waiting a moment before trying again.")
            sourceBitmap = MediaProjectionService.takeScreenshotNow()
        }

        if (sourceBitmap == null) {
            throw IllegalStateException("Failed to acquire a source bitmap even after caching and retries.")
        }

        var templateBitmap: Bitmap?
        val newTemplatePath =
            if (templatePath.last() != '/') {
                "$templatePath/"
            } else {
                templatePath
            }

        // Get the Bitmap from the template image file inside the specified folder.
        val assetFilePath = "${newTemplatePath}$templateName.${SharedData.templateImageExt}"
        context.assets?.open(assetFilePath).use { inputStream ->
            // Get the Bitmap from the template image file and then start matching.
            templateBitmap = BitmapFactory.decodeStream(inputStream)
        }

        return if (templateBitmap != null) {
            Pair(sourceBitmap, templateBitmap)
        } else {
            Log.e(tag, "The template Bitmap is null.")
            Pair(sourceBitmap, null)
        }
    }

    /**
     * Loads only the template bitmap from assets without any side effects (no screenshots, no swipes).
     * Useful for getting template dimensions for calculating object positions.
     *
     * @param templateName File name of the template image.
     * @param templatePath Path name of the subfolder in /assets/ that the template image is in. Defaults to the default template subfolder path name.
     * @return The template Bitmap, or null if loading fails.
     */
    open fun getTemplateBitmap(templateName: String, templatePath: String = SharedData.templateSubfolderPathName): Bitmap? {
        val newTemplatePath =
            if (templatePath.last() != '/') {
                "$templatePath/"
            } else {
                templatePath
            }

        // Get the Bitmap from the template image file inside the specified folder.
        val assetFilePath = "${newTemplatePath}$templateName.${SharedData.templateImageExt}"
        var templateBitmap: Bitmap? = null
        context.assets?.open(assetFilePath).use { inputStream ->
            // Get the Bitmap from the template image file.
            templateBitmap = BitmapFactory.decodeStream(inputStream)
        }

        if (templateBitmap == null) {
            Log.e(tag, "The template Bitmap is null.")
        }

        return templateBitmap
    }

    /**
     * Safely creates a bitmap with bounds checking to prevent IllegalArgumentException.
     * Clamps individual dimensions to source bitmap bounds if they exceed limits.
     *
     * @param sourceBitmap The source bitmap to crop from.
     * @param x The x coordinate for the crop.
     * @param y The y coordinate for the crop.
     * @param width The width of the crop.
     * @param height The height of the crop.
     * @param context String describing the context for error logging.
     * @return The cropped bitmap or null if bounds are still invalid after clamping.
     */
    open fun createSafeBitmap(sourceBitmap: Bitmap, x: Int, y: Int, width: Int, height: Int, context: String): Bitmap? {
        // Clamp starting coordinates to source bitmap bounds.
        val clampedX = x.coerceIn(0, sourceBitmap.width)
        val clampedY = y.coerceIn(0, sourceBitmap.height)

        // Calculate maximum possible dimensions for the crop based on clamped coordinates.
        val maxWidth = sourceBitmap.width - clampedX
        val maxHeight = sourceBitmap.height - clampedY

        // If the available area is effectively empty, return null instead of crashing.
        if (maxWidth < 1 || maxHeight < 1) {
            Log.w(
                tag,
                "Cannot create bitmap for $context: remaining space is too small (width=$maxWidth, height=$maxHeight) at (x=$clampedX, y=$clampedY), sourceBitmap=${sourceBitmap.width}x${sourceBitmap.height}",
            )
            return null
        }

        // Clamp width and height to available area.
        val clampedWidth = width.coerceIn(1, maxWidth)
        val clampedHeight = height.coerceIn(1, maxHeight)

        // Check if any dimensions were clamped and log a warning.
        if (x != clampedX || y != clampedY || width != clampedWidth || height != clampedHeight) {
            Log.w(
                tag,
                "Clamped bounds for $context: original(x=$x, y=$y, width=$width, height=$height) -> clamped(x=$clampedX, y=$clampedY, width=$clampedWidth, height=$clampedHeight), sourceBitmap=${sourceBitmap.width}x${sourceBitmap.height}",
            )
        }

        // Final validation to ensure the clamped dimensions are still valid.
        if (clampedX < 0 ||
            clampedY < 0 ||
            clampedWidth <= 0 ||
            clampedHeight <= 0 ||
            clampedX + clampedWidth > sourceBitmap.width ||
            clampedY + clampedHeight > sourceBitmap.height
        ) {
            Log.e(tag, "Invalid bounds for $context after clamping: x=$clampedX, y=$clampedY, width=$clampedWidth, height=$clampedHeight, sourceBitmap=${sourceBitmap.width}x${sourceBitmap.height}")
            return null
        }

        return Bitmap.createBitmap(sourceBitmap, clampedX, clampedY, clampedWidth, clampedHeight)
    }

    /**
     * Adjusts the coordinates for the swiping behavior to generate a new image for getBitmaps().
     *
     * @param oldX The x coordinate of the old position. Defaults to 500f.
     * @param oldY The y coordinate of the old position. Defaults to 500f.
     * @param newX The x coordinate of the new position. Defaults to 500f.
     * @param newY The y coordinate of the new position. Defaults to 400f
     * @param duration How long the swipe should take. Defaults to 100L.
     */
    protected open fun adjustTriggerNewImageSwipeBehavior(oldX: Float, oldY: Float, newX: Float, newY: Float, duration: Long = 100L) {
        oldXSwipe = oldX
        oldYSwipe = oldY
        newXSwipe = newX
        newYSwipe = newY
        durationSwipe = duration
    }

    /**
     * Acquire the Bitmap for only the source screenshot. Note that it will keep swiping the screen a bit to generate a new image for ImageReader to grab.
     *
     * @return Bitmap of the source screenshot.
     */
    open fun getSourceBitmap(): Bitmap {
        // Acquire the source bitmap. MediaProjectionService handles caching and retries internally,
        // so we no longer need to swipe aggressively to force new images.
        var bitmap = MediaProjectionService.takeScreenshotNow(saveImage = debugMode)

        if (bitmap == null) {
            Log.w(tag, "Source bitmap is null on initial capture. Waiting a moment before trying again.")
            bitmap = MediaProjectionService.takeScreenshotNow(saveImage = debugMode)
        }

        return bitmap ?: throw IllegalStateException("Failed to acquire a source bitmap even after caching and retries.")
    }

    /**
     * Capture a specific region of the screen and return its Bitmap.
     *
     * @param x The x-coordinate of the upper-left corner of the region.
     * @param y The y-coordinate of the upper-left corner of the region.
     * @param w The width of the region.
     * @param h The height of the region.
     * @return The Bitmap of the captured region.
     */
    open fun getRegionBitmap(x: Int, y: Int, w: Int, h: Int): Bitmap {
        var bitmap = MediaProjectionService.captureArea(x, y, w, h, saveImage = debugMode)

        if (bitmap == null) {
            Log.w(tag, "Region bitmap is null on initial capture. Waiting a moment before trying again.")
            bitmap = MediaProjectionService.captureArea(x, y, w, h, saveImage = debugMode)
        }

        return bitmap ?: throw IllegalStateException("Failed to acquire a region bitmap even after caching and retries.")
    }

    /**
     * Acquire a Bitmap from the URL image file.
     *
     * @return A new Bitmap.
     */
    open fun getBitmapFromURL(url: URL): Bitmap {
        Log.d(tag, "\nStarting process to create a Bitmap from the image url: $url")

        // Open up a HTTP connection to the URL.
        val connection: HttpURLConnection = url.openConnection() as HttpURLConnection
        try {
            connection.doInput = true
            connection.connect()

            // Download the image from the URL. Use .use{} to ensure the stream is closed (H2 fix).
            connection.inputStream.use { input ->
                return BitmapFactory.decodeStream(input)
            }
        } finally {
            connection.disconnect()
        }
    }

    // //////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////
    // Finder functions

    /**
     * Finds the location of the specified image inside assets.
     *
     * @param templateName File name of the template image.
     * @param tries Number of tries before failing. Defaults to 5.
     * @param confidence Custom confidence for template matching. Defaults to 0.0 which will use the confidence set in the app's settings.
     * @param region Specify the region consisting of (x, y, width, height) of the source screenshot to template match. Defaults to (0, 0, 0, 0) which is equivalent to searching the full image.
     * @param suppressError Whether or not to suppress saving error messages to the log. Defaults to false.
     * @param testMode Flag to test and get a valid scale for device compatibility.
     * @return Pair object consisting of the Point object containing the location of the match (null if not found) and the source screenshot.
     *
     * **Ownership note:** the returned Bitmap is a live reference to the MediaProjectionService screenshot cache (`lastBitmap`), NOT a copy. Callers must NOT call `recycle()` on it, and must finish using it before the next `takeScreenshotNow()` call, which recycles the previous cached bitmap.
     */
    open fun findImage(
        templateName: String,
        tries: Int = 5,
        confidence: Double = 0.0,
        region: IntArray = intArrayOf(0, 0, 0, 0),
        suppressError: Boolean = false,
        testMode: Boolean = false,
    ): Pair<Point?, Bitmap> {
        var numberOfTries = tries

        if (debugMode) {
            MessageLog.d(tag, "\nStarting process to find the ${templateName.uppercase()} image...")
        }

        // If Test Mode is enabled, prepare for it by setting initial scale.
        if (testMode) {
            numberOfTries = 80
            customScale = 0.20
        }

        val (sourceBitmap, templateBitmap) = getBitmaps(templateName)
        try {
            while (numberOfTries > 0) {
                if (templateBitmap != null) {
                    val (resultFlag, matchLocation) = match(sourceBitmap, templateBitmap, templateName, region, useSingleScale = true, customConfidence = confidence)
                    if (!resultFlag) {
                        if (testMode) {
                            // Increment scale by 0.01 until a match is found if Test Mode is enabled.
                            customScale += 0.01
                            customScale = decimalFormat.format(customScale).toDouble()
                        }

                        numberOfTries -= 1
                        if (numberOfTries <= 0) {
                            if (!suppressError) {
                                MessageLog.w(tag, "Failed to find the ${templateName.uppercase()} image.")
                            }

                            break
                        }
                    } else {
                        if (testMode) {
                            // Create a range of scales for user recommendation.
                            val scale0: Double = decimalFormat.format(customScale).toDouble()
                            val scale1: Double = decimalFormat.format(scale0 + 0.01).toDouble()
                            val scale2: Double = decimalFormat.format(scale0 + 0.02).toDouble()
                            val scale3: Double = decimalFormat.format(scale0 + 0.03).toDouble()
                            val scale4: Double = decimalFormat.format(scale0 + 0.04).toDouble()

                            MessageLog.i(
                                tag,
                                "[SUCCESS] Found the ${templateName.uppercase()} at $matchLocation with scale $scale0.\n\nRecommended to use scale $scale1, $scale2, $scale3 or $scale4.",
                            )
                        } else if (debugMode) {
                            MessageLog.d(tag, "[SUCCESS] Found the ${templateName.uppercase()} at $matchLocation.")
                        }

                        return Pair(matchLocation, sourceBitmap)
                    }
                } else {
                    // 模板图像为 null(assets 缺失/路径错误)时递减计数,避免无限循环
                    MessageLog.w(tag, "Template bitmap is null for ${templateName.uppercase()}. Cannot find image.")
                    numberOfTries -= 1
                }
            }

            return Pair(null, sourceBitmap)
        } finally {
            // Release the template Bitmap decoded from assets (C4 fix).
            // Do NOT recycle sourceBitmap — it is a cache reference from MediaProjectionService.
            templateBitmap?.recycle()
        }
    }

    /**
     * Finds all occurrences of the specified image.
     *
     * @param templateName File name of the template image.
     * @param region Specify the region consisting of (x, y, width, height) of the source screenshot to template match. Defaults to (0, 0, 0, 0) which is equivalent to searching the full image.
     * @param confidence Accuracy threshold for matching. Defaults to 0.0 which will use the confidence set in the app's settings.
     * @return An ArrayList of Point objects containing all the occurrences of the specified image. Empty if none found.
     */
    open fun findAll(templateName: String, region: IntArray = intArrayOf(0, 0, 0, 0), confidence: Double = 0.0): ArrayList<Point> {
        if (debugMode) {
            MessageLog.d(tag, "\nStarting process to find all ${templateName.uppercase()} images...")
        }

        val (sourceBitmap, templateBitmap) = getBitmaps(templateName)
        try {
            if (templateBitmap != null) {
                val matchLocations = matchAll(sourceBitmap, templateBitmap, region = region, customConfidence = confidence)

                // Sort the match locations by ascending x and y coordinates.
                matchLocations.sortBy { it.x }
                matchLocations.sortBy { it.y }

                if (debugMode) {
                    MessageLog.d(tag, "Found match locations for $templateName: $matchLocations.")
                } else {
                    Log.d(tag, "Found match locations for $templateName: $matchLocations.")
                }

                return matchLocations
            }

            return arrayListOf()
        } finally {
            // Release the template Bitmap decoded from assets (C4 fix).
            templateBitmap?.recycle()
        }
    }

    /**
     * Waits for the specified image to vanish from the screen.
     *
     * @param templateName File name of the template image.
     * @param timeout Amount of time to wait before timing out. Default is 5 seconds.
     * @param region Specify the region consisting of (x, y, width, height) of the source screenshot to template match. Defaults to (0, 0, 0, 0) which is equivalent to searching the full image.
     * @param suppressError Whether or not to suppress saving error messages to the log.
     * @return True if the specified image vanished from the screen. False otherwise.
     */
    open fun waitVanish(templateName: String, timeout: Int = 5, region: IntArray = intArrayOf(0, 0, 0, 0), suppressError: Boolean = false): Boolean {
        MessageLog.i(tag, "Now waiting for $templateName to vanish from the screen...")

        var remaining = timeout
        if (findImage(templateName, tries = 1, region = region, suppressError = suppressError).first == null) {
            return true
        } else {
            while (findImage(templateName, tries = 1, region = region, suppressError = suppressError).first != null) {
                wait(1.0)
                remaining -= 1
                if (remaining <= 0) {
                    return false
                }
            }

            return true
        }
    }

    /**
     * Pixel search by its RGB value.
     *
     * @param bitmap Bitmap of the image to search for the specific pixel.
     * @param red The pixel's Red value.
     * @param blue The pixel's Blue value.
     * @param green The pixel's Green value.
     * @return A Pair object of the (x,y) coordinates on the Bitmap for the matched pixel.
     */
    open fun pixelSearch(bitmap: Bitmap, red: Int, blue: Int, green: Int, suppressError: Boolean = false): Pair<Int, Int> {
        if (debugMode) {
            MessageLog.d(tag, "\nStarting process to find the specified pixel ($red, $blue, $green)...")
        }

        var x = 0
        var y = 0

        // Iterate through each pixel in the Bitmap and compare RGB values.
        while (x < bitmap.width) {
            while (y < bitmap.height) {
                val pixel = bitmap[x, y]

                if (Color.red(pixel) == red && Color.blue(pixel) == blue && Color.green(pixel) == green) {
                    if (debugMode) {
                        MessageLog.d(tag, "Found matching pixel at ($x, $y).")
                    }

                    return Pair(x, y)
                }

                y++
            }

            x++
            y = 0
        }

        if (!suppressError) {
            MessageLog.w(tag, "Failed to find the specified pixel ($red, $blue, $green).")
        }

        return Pair(-1, -1)
    }

    /**
     * Check if the color at the specified coordinates matches the given RGB value.
     *
     * @param x X coordinate to check.
     * @param y Y coordinate to check.
     * @param rgb Expected RGB values as red, blue and green (0-255).
     * @param tolerance Tolerance for color matching (0-255). Defaults to 0 for exact match.
     * @return True if the color at the coordinates matches the expected RGB values within tolerance, false otherwise.
     */
    open fun checkColorAtCoordinates(x: Int, y: Int, rgb: IntArray, tolerance: Int = 0): Boolean {
        val sourceBitmap = getSourceBitmap()

        // Check if coordinates are within bounds.
        if (x < 0 || y < 0 || x >= sourceBitmap.width || y >= sourceBitmap.height) {
            if (debugMode) MessageLog.w(tag, "Coordinates ($x, $y) are out of bounds for bitmap size ${sourceBitmap.width}x${sourceBitmap.height}")
            return false
        }

        // Get the pixel color at the specified coordinates.
        val pixel = sourceBitmap[x, y]

        // Extract RGB values from the pixel.
        val actualRed = Color.red(pixel)
        val actualGreen = Color.green(pixel)
        val actualBlue = Color.blue(pixel)

        // Check if the colors match within the specified tolerance.
        val redMatch = abs(actualRed - rgb[0]) <= tolerance
        val greenMatch = abs(actualGreen - rgb[1]) <= tolerance
        val blueMatch = abs(actualBlue - rgb[2]) <= tolerance

        if (debugMode) {
            MessageLog.d(
                tag,
                "Color check at ($x, $y): Expected RGB(${rgb[0]}, ${rgb[1]}, ${rgb[2]}), Actual RGB($actualRed, $actualGreen, $actualBlue), Match: ${redMatch && greenMatch && blueMatch}",
            )
        }

        return redMatch && greenMatch && blueMatch
    }

    // //////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////
    // OCR with ONNX Runtime + PP-OCRv6

    /**
     * Perform OCR text detection along with some image manipulation via thresholding to make the cropped screenshot black and white using OpenCV.
     *
     * NOTE: As of v2.5.8, the underlying engine has been migrated from Tesseract + Google ML Kit to
     * ONNX Runtime running PP-OCRv6 Tiny. PP-OCRv6 is trained on RGB images and performs its own
     * internal normalization, so grayscale/thresh are now DISABLED by default. Enabling them will
     * route through OpenCV (slow path) and may hurt accuracy. They remain as parameters for legacy
     * callers that explicitly want binary preprocessing. `detectDigitsOnly` is implemented as a
     * post-recognition regex filter.
     *
     * Fast path (default): grayscale=false && thresh=false && scale=1.0 → no OpenCV at all,
     * croppedBitmap is fed directly to the OCR engine.
     *
     * @param cropRegion The region consisting of (x, y, width, height) of the cropped region.
     * @param grayscale Performs grayscale conversion on the cropped region. Defaults to false (PP-OCRv6 wants RGB).
     * @param thresh Performs thresholding on the cropped region. Defaults to false.
     * @param threshold Minimum threshold value. Defaults to 130.
     * @param thresholdMax Maximum threshold value. Defaults to 255.
     * @param scale Scale factor to apply to the processed image. Values > 1 scale up, values < 1 scale down. Clamped to >= 0. Defaults to 1.0.
     * @param sourceBitmap The source bitmap to use for OCR. If null, a new source bitmap will be obtained. Defaults to null.
     * @param detectDigitsOnly True if detection should focus on digits only.
     * @param debugName Optional name for debug image saving. Defaults to "ocr".
     *
     * @return The detected String in the cropped region.
     */
    open fun findText(
        cropRegion: IntArray,
        grayscale: Boolean = false,
        thresh: Boolean = false,
        threshold: Double = 130.0,
        thresholdMax: Double = 255.0,
        scale: Double = 1.0,
        sourceBitmap: Bitmap? = null,
        detectDigitsOnly: Boolean = false,
        debugName: String = "ocr",
    ): String {
        val startTime: Long = System.currentTimeMillis()

        // Abort early if the bot has been stopped; avoids wasted OCR work after interrupt.
        if (!BotService.isRunning) {
            MessageLog.w(tag, "[TEXT_DETECTION] Bot no longer running; abort findText($debugName).")
            return ""
        }

        val finalSourceBitmap: Bitmap = sourceBitmap ?: getSourceBitmap()

        MessageLog.d(tag, "[TEXT_DETECTION] Starting text detection for '$debugName'...")

        // Crop the source bitmap. PP-OCRv6 expects RGB input; clamp dimensions to a minimum.
        val (x, y, width, height) = cropRegion
        val minDimension = 16
        val clampedWidth = maxOf(width, minDimension).coerceAtMost(finalSourceBitmap.width - x)
        val clampedHeight = maxOf(height, minDimension).coerceAtMost(finalSourceBitmap.height - y)

        if (width < minDimension || height < minDimension) {
            MessageLog.w(tag, "[TEXT_DETECTION] Crop region clamped from ${width}x$height to ${clampedWidth}x$clampedHeight to meet the minimum $minDimension px requirement.")
        }

        val croppedBitmap = Bitmap.createBitmap(finalSourceBitmap, x, y, clampedWidth, clampedHeight)

        try {
            // Debug: save the cropped RGB image for troubleshooting.
            if (debugMode) {
                val cvImage = Mat()
                try {
                    Utils.bitmapToMat(croppedBitmap, cvImage)
                    Imgcodecs.imwrite("$matchFilePath/debug_${debugName}_cropped.png", cvImage)
                } finally {
                    cvImage.release()
                }
            }

            val clampedScale = max(0.0, scale)

            // Produce the final bitmap to feed into the OCR engine.
            // Fast path: skip OpenCV entirely when no grayscale/thresh is requested.
            val needRecycleFinal: Boolean
            val finalBitmap: Bitmap = if (!grayscale && !thresh) {
                if (clampedScale == 1.0) {
                    // finalBitmap aliases croppedBitmap; croppedBitmap.recycle() in the outer finally handles cleanup.
                    needRecycleFinal = false
                    croppedBitmap
                } else {
                    needRecycleFinal = true
                    Bitmap.createScaledBitmap(
                        croppedBitmap,
                        (croppedBitmap.width * clampedScale).toInt(),
                        (croppedBitmap.height * clampedScale).toInt(),
                        true,
                    )
                }
            } else {
                // Slow path: legacy OpenCV preprocessing (grayscale and/or threshold).
                // NOTE: applying grayscale/thresh on RGB input will lose color information and may
                // hurt PP-OCRv6 accuracy. Only enable when you know what you're doing.
                val cvImage = Mat()
                val grayImage = Mat()
                var bwImage: Mat? = null
                try {
                    Utils.bitmapToMat(croppedBitmap, cvImage)

                    val imageForProcessing: Mat =
                        if (grayscale) {
                            Imgproc.cvtColor(cvImage, grayImage, Imgproc.COLOR_RGB2GRAY)
                            grayImage
                        } else {
                            cvImage
                        }

                    val processedMat: Mat =
                        if (thresh) {
                            bwImage = Mat()
                            Imgproc.threshold(imageForProcessing, bwImage, threshold, thresholdMax, Imgproc.THRESH_BINARY)
                            if (debugMode) {
                                Imgcodecs.imwrite("$matchFilePath/debug_${debugName}_threshold.png", bwImage)
                            }
                            bwImage
                        } else {
                            imageForProcessing
                        }

                    val baseBitmap = createBitmap(processedMat.cols(), processedMat.rows())
                    Utils.matToBitmap(processedMat, baseBitmap)

                    if (clampedScale == 1.0) {
                        needRecycleFinal = true
                        baseBitmap
                    } else {
                        val scaled = baseBitmap.scale((baseBitmap.width * clampedScale).toInt(), (baseBitmap.height * clampedScale).toInt())
                        baseBitmap.recycle()
                        needRecycleFinal = true
                        scaled
                    }
                } finally {
                    cvImage.release()
                    grayImage.release()
                    bwImage?.release()
                }
            }

            // Run PP-OCRv6 recognition via the shared ONNX Runtime engine (returns text + CTC confidence).
            val minConf = SharedData.ocrMinConfidence
            val (rawText, conf) = try {
                ocrEngine.recognizeWithConfidence(finalBitmap)
            } catch (e: Exception) {
                MessageLog.e(tag, "[TEXT_DETECTION] OCR engine failed: ${e.stackTraceToString()}")
                Pair("", 0f)
            }

            // Recycle the final bitmap if it was newly created (not aliased to croppedBitmap).
            if (needRecycleFinal) finalBitmap.recycle()

            // Drop low-confidence reads when minConfidence is configured (> 0). Empty results are kept
            // as "" so callers can distinguish "not found" from "filtered out".
            val confidenceFiltered: String = if (minConf > 0f && conf < minConf && rawText.isNotEmpty()) {
                MessageLog.d(tag, "[TEXT_DETECTION] Dropping low-confidence read '$rawText' (conf=$conf < minConf=$minConf).")
                ""
            } else {
                rawText
            }

            // Apply digit-only post-filter when requested (replaces the old digits-only Tesseract model).
            val result: String = if (detectDigitsOnly) {
                val filtered = confidenceFiltered.filter { it.isDigit() }
                if (filtered.isNotEmpty() && filtered != confidenceFiltered) {
                    MessageLog.d(tag, "[TEXT_DETECTION] detectDigitsOnly filtered '$confidenceFiltered' -> '$filtered'")
                }
                filtered
            } else {
                confidenceFiltered
            }

            MessageLog.d(tag, "[TEXT_DETECTION] Detected text with PP-OCRv6: '$result' (conf=$conf) in ${System.currentTimeMillis() - startTime}ms.")

            return result
        } finally {
            // Recycle the cropped Bitmap (C2 fix). In the fast path with scale==1.0,
            // finalBitmap aliased croppedBitmap and needRecycleFinal was false, so it wasn't recycled
            // above — recycle it here. In all other paths, croppedBitmap is a separate Bitmap recycled here.
            croppedBitmap.recycle()
        }
    }

    // //////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////
    // OCR batch + detection APIs (PP-OCRv6 det + rec)

    /**
     * Recognize text in multiple cropped regions in one batch (shares a single session.run).
     *
     * Useful for reading multiple UI values at once (HP/MP/coins etc). Compared to looping [findText],
     * this reduces N-1 native calls and session context switches. Each region is cropped
     * independently with no grayscale/thresh preprocessing (PP-OCRv6 is trained on RGB; binarization
     * would hurt accuracy).
     *
     * @param cropRegions Multiple crop regions, each as [x, y, w, h].
     * @param sourceBitmap Shared source screenshot; null auto-calls [getSourceBitmap].
     * @return Recognized text per region, in the same order as the input. Empty region or failure returns "".
     */
    open fun findTextBatch(
        cropRegions: List<IntArray>,
        sourceBitmap: Bitmap? = null,
    ): List<String> {
        val startTime = System.currentTimeMillis()
        if (cropRegions.isEmpty()) return emptyList()

        // Abort early if the bot has been stopped; avoids wasted OCR work after interrupt.
        if (!BotService.isRunning) {
            MessageLog.w(tag, "[TEXT_BATCH] Bot no longer running; abort findTextBatch.")
            return emptyList()
        }

        MessageLog.d(tag, "[TEXT_BATCH] Starting batch text detection for ${cropRegions.size} region(s)...")

        val src = sourceBitmap ?: getSourceBitmap()
        val minDimension = 16

        // 1. Crop each region with bounds + minimum-size protection.
        val crops = ArrayList<Bitmap>(cropRegions.size)
        try {
            for (region in cropRegions) {
                val x = region.getOrElse(0) { 0 }.coerceIn(0, src.width - 1)
                val y = region.getOrElse(1) { 0 }.coerceIn(0, src.height - 1)
                val w = maxOf(region.getOrElse(2) { 0 }, minDimension).coerceAtMost(src.width - x)
                val h = maxOf(region.getOrElse(3) { 0 }, minDimension).coerceAtMost(src.height - y)
                if (w < 1 || h < 1) {
                    MessageLog.w(tag, "[TEXT_BATCH] Skip invalid region $region")
                    continue
                }
                crops.add(Bitmap.createBitmap(src, x, y, w, h))
            }
            if (crops.isEmpty()) return emptyList()

            // 2. Batch recognition with confidence.
            val minConf = SharedData.ocrMinConfidence
            val results = ocrEngine.recognizeBatchWithConfidence(crops)
            val dropped = if (minConf > 0f) {
                results.count { it.first.isNotEmpty() && it.second < minConf }
            } else 0
            val texts = results.map { (text, conf) ->
                if (minConf > 0f && conf < minConf && text.isNotEmpty()) "" else text
            }
            MessageLog.d(tag, "[TEXT_BATCH] Recognized ${crops.size} regions in ${System.currentTimeMillis() - startTime}ms" +
                (if (minConf > 0f) "; dropped $dropped low-confidence (< $minConf) reads" else "") + ".")
            return texts
        } finally {
            crops.forEach { it.recycle() }
        }
    }

    /**
     * Detect all text lines in the full image and return each line's content + position.
     *
     * Internally runs the end-to-end det (detect text-line boxes) + batch rec (recognize per line)
     * pipeline. Useful for "find a UI element by its text". Results are sorted top→bottom, left→right.
     *
     * @param sourceBitmap Source screenshot; null auto-calls [getSourceBitmap].
     * @param textFilter Optional; only return results containing this substring (case-insensitive). null returns all.
     * @return List of matching [OcrResult].
     */
    open fun findTextLocations(
        sourceBitmap: Bitmap? = null,
        textFilter: String? = null,
    ): List<OcrResult> {
        val startTime = System.currentTimeMillis()

        // Abort early if the bot has been stopped; avoids wasted OCR work after interrupt.
        if (!BotService.isRunning) {
            MessageLog.w(tag, "[TEXT_LOCATIONS] Bot no longer running; abort findTextLocations.")
            return emptyList()
        }

        MessageLog.d(tag, "[TEXT_LOCATIONS] Starting det+rec for filter='$textFilter'...")

        val src = sourceBitmap ?: getSourceBitmap()

        val all = ocrEngine.detectAndRecognize(src, textOnly = textFilter == null)
        val filtered = if (textFilter.isNullOrEmpty()) all
            else all.filter { it.text.contains(textFilter, ignoreCase = true) }

        MessageLog.d(tag, "[TEXT_LOCATIONS] Detected ${all.size} lines, ${filtered.size} matched filter '$textFilter' in ${System.currentTimeMillis() - startTime}ms.")
        return filtered
    }

    /**
     * Find the first position containing the specified text and return its center point (for tapping).
     *
     * Convenience wrapper around [findTextLocations], similar to [findImage] but for text. Useful for
     * "tap the button containing some text".
     *
     * @param text Text to find (case-insensitive).
     * @param sourceBitmap Source screenshot; null auto-calls [getSourceBitmap].
     * @return Center [PointF] of the matching line; null if not found.
     */
    open fun findTextLocation(
        text: String,
        sourceBitmap: Bitmap? = null,
    ): PointF? {
        if (text.isEmpty()) return null
        val matches = findTextLocations(sourceBitmap, text)
        return matches.firstOrNull()?.center
    }
}
