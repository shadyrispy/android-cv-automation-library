# Memory Leak & GC Pressure Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate all 30 identified memory leaks and GC pressure issues across the Android automation library, prioritizing native resource (Mat/Bitmap/ONNX Tensor/Cursor/Stream) leaks in hot paths.

**Architecture:** Fix leaks in 7 batches ordered by dependency and risk. Each batch touches a cohesive set of files, compiles independently, and commits separately. Native resources are wrapped in `try/finally`; Bitmap aliases are checked with `!==` before `recycle()`; thread references are tracked and joined on cleanup.

**Tech Stack:** Kotlin, Android (Service/AccessibilityService), OpenCV (opencv-mobile), ONNX Runtime, EventBus, SQLite, Kord (Discord).

**Project context:**
- This is an Android **library** (AAR), not an app. No unit test framework exists — verification is via `./gradlew assembleDebug` + code review.
- User rule: "git commit 之前一定要在本地编译测试无问题" — every commit must pass `./gradlew assembleDebug`.
- User preference: minimal code changes, reuse existing infrastructure (MessageLog, SharedData, BotService.isRunning).
- AAR size must stay under 4MB.
- Comments in English to match existing codebase style.

**Pre-existing uncommitted changes:** 6 files modified in the previous "infrastructure reuse" round (OcrEngine interface, ImageUtils OCR methods, BotService OCR lifecycle, SharedData OCR config, learnings). These are already `BUILD SUCCESSFUL` and will be committed together with Batch 1 since they touch the same files.

---

## File Structure

Files to modify (no new files created — all fixes are in existing files):

| File | Batches | Responsibility |
|------|---------|----------------|
| `app/src/main/java/com/steve1316/automation_library/utils/ImageUtils.kt` | 1 | Mat/Bitmap leak fixes in match/matchAll/findImage/findAll/findText |
| `app/src/main/java/com/steve1316/automation_library/utils/MediaProjectionService.kt` | 2 | lastBitmap recycle, Looper thread, FileOutputStream, GC pressure |
| `app/src/main/java/com/steve1316/automation_library/utils/MyAccessibilityService.kt` | 3 | randomizeTapLocation inJustDecodeBounds, instance nulling |
| `app/src/main/java/com/steve1316/automation_library/utils/ocr/OnnxPpocrEngine.kt` | 4 | Bitmap try/finally, FloatArray/FloatBuffer reuse |
| `app/src/main/java/com/steve1316/automation_library/utils/BotService.kt` | 5 | Thread tracking + join, performCleanUp idempotency |
| `app/src/main/java/com/steve1316/automation_library/utils/DiscordUtils.kt` | 5 | Kord client shutdown on early-exit paths |
| `app/src/main/java/com/steve1316/automation_library/utils/SQLiteSettingsManager.kt` | 6 | Cursor .use{}, close on app exit |
| `app/src/main/java/com/steve1316/automation_library/utils/GlobalExceptionHandler.kt` | 6 | Prevent duplicate registration |
| `app/src/main/java/com/steve1316/automation_library/utils/NotificationUtils.kt` | 7 | lateinit → nullable var |
| `app/src/main/java/com/steve1316/automation_library/utils/AndroidComponents.kt` | 7 | Reuse static Handler |
| `app/src/main/java/com/steve1316/automation_library/utils/MessageLog.kt` | 7 | Move string concat out of synchronized block |

---

## Batch 1: ImageUtils.kt Mat/Bitmap Leaks (Critical C2-C7, L1)

**Files:**
- Modify: `app/src/main/java/com/steve1316/automation_library/utils/ImageUtils.kt`

This batch fixes the 6 Critical leaks in the hottest path (image matching + OCR). All fixes use `try/finally` + `!==` alias checks.

### Task 1.1: Wrap `match()` Mat operations in try/finally (C6)

**Files:**
- Modify: `app/src/main/java/com/steve1316/automation_library/utils/ImageUtils.kt:247-356`

The current `match()` creates 4 Mats (`sourceMat`, `templateMat`, `clampedTemplateMat`, `resultMat`) at L262-286 and releases them manually at two points (L344-347 success early-return, L352-355 loop bottom). If any OpenCV call throws, all 4 leak. `matchAll()` already uses try/finally (L423) — we follow the same pattern.

**Key aliasing risk:** `clampedTemplateMat` may equal `templateMat` (L276) when no clamping is needed. Releasing both would double-free. Must check `!==` before release.

- [ ] **Step 1: Read the current `match()` function body (L247-356)**

Read `app/src/main/java/com/steve1316/automation_library/utils/ImageUtils.kt` lines 247-356 to confirm current structure.

- [ ] **Step 2: Replace the `while (scales.isNotEmpty())` loop body in `match()` with try/finally version**

Replace the block from `while (scales.isNotEmpty()) {` (L247) through the end of the loop (just before `return Pair(false, null)`) with:

```kotlin
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
            val resultMat = Mat()
            try {
                Utils.bitmapToMat(srcBitmap, sourceMat)
                Utils.bitmapToMat(tmp, templateMat)

                // Clamp template dimensions to source dimensions if template is too large.
                clampedTemplateMat =
                    if (templateMat.cols() > sourceMat.cols() || templateMat.rows() > sourceMat.rows()) {
                        Log.d(tag, "Image sizes for match assertion failed - sourceMat: ${sourceMat.size()}, templateMat: ${templateMat.size()}")
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
                val resultMat2 = Mat(resultRows, resultColumns, CvType.CV_32FC1)
                resultMat.assignTo(resultMat2)
                resultMat2.release()
                resultMat.create(resultRows, resultColumns, CvType.CV_32FC1)

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
                        if (matchFilePath != "") {
                            Imgproc.rectangle(sourceMat, matchLocation, Point(matchLocation.x + templateMat.cols(), matchLocation.y + templateMat.rows()), Scalar(0.0, 128.0, 0.0), 10)
                            Imgcodecs.imwrite("$matchFilePath/match.png", sourceMat)
                        }
                    }

                    matchLocation.x += (templateMat.cols() / 2)
                    matchLocation.y += (templateMat.rows() / 2)

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
                if (clampedTemplateMat !== templateMat) clampedTemplateMat.release()
                resultMat.release()
                // Release the scaled tmp Bitmap if it was newly created (not aliased to templateBitmap).
                if (tmp !== templateBitmap) tmp.recycle()
            }
        }
```

**Note:** The `resultMat` handling above is awkward because `Mat()` creates an empty Mat and we can't reassign a `val`. Simpler approach: declare `resultMat` inside the try block. Use this cleaner version instead:

```kotlin
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

            val sourceMat = Mat()
            val templateMat = Mat()
            var clampedTemplateMat: Mat = templateMat
            var resultMat: Mat? = null
            try {
                Utils.bitmapToMat(srcBitmap, sourceMat)
                Utils.bitmapToMat(tmp, templateMat)

                clampedTemplateMat =
                    if (templateMat.cols() > sourceMat.cols() || templateMat.rows() > sourceMat.rows()) {
                        Log.d(tag, "Image sizes for match assertion failed - sourceMat: ${sourceMat.size()}, templateMat: ${templateMat.size()}")
                        val clampedWidth = minOf(templateMat.cols(), sourceMat.cols())
                        val clampedHeight = minOf(templateMat.rows(), sourceMat.rows())
                        Mat(templateMat, Rect(0, 0, clampedWidth, clampedHeight))
                    } else {
                        templateMat
                    }

                Imgproc.cvtColor(sourceMat, sourceMat, Imgproc.COLOR_BGR2GRAY)
                Imgproc.cvtColor(clampedTemplateMat, clampedTemplateMat, Imgproc.COLOR_BGR2GRAY)

                val resultColumns: Int = sourceMat.cols() - clampedTemplateMat.cols() + 1
                val resultRows: Int = sourceMat.rows() - clampedTemplateMat.rows() + 1
                resultMat = Mat(resultRows, resultColumns, CvType.CV_32FC1)

                Imgproc.matchTemplate(sourceMat, clampedTemplateMat, resultMat, matchMethod)
                val mmr: Core.MinMaxLocResult = Core.minMaxLoc(resultMat)

                var matchLocation = Point()
                var matchCheck = false

                val minVal: Double = decimalFormat.format(mmr.minVal).toDouble()
                val maxVal: Double = decimalFormat.format(mmr.maxVal).toDouble()

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
                    if (debugMode && matchFilePath != "") {
                        Imgproc.rectangle(sourceMat, matchLocation, Point(matchLocation.x + templateMat.cols(), matchLocation.y + templateMat.rows()), Scalar(0.0, 128.0, 0.0), 10)
                        Imgcodecs.imwrite("$matchFilePath/match.png", sourceMat)
                    }

                    matchLocation.x += (templateMat.cols() / 2)
                    matchLocation.y += (templateMat.rows() / 2)

                    if (!region.contentEquals(intArrayOf(0, 0, 0, 0))) {
                        matchLocation.x = sourceBitmap.width - (sourceBitmap.width - (region[0] + matchLocation.x))
                        matchLocation.y = sourceBitmap.height - (sourceBitmap.height - (region[1] + matchLocation.y))
                    }

                    return Pair(true, matchLocation)
                }
            } finally {
                sourceMat.release()
                templateMat.release()
                if (clampedTemplateMat !== templateMat) clampedTemplateMat.release()
                resultMat?.release()
                if (tmp !== templateBitmap) tmp.recycle()
            }
        }
```

Use the cleaner version (second code block) for the actual edit.

- [ ] **Step 3: Compile to verify no syntax errors**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Add srcBitmap recycle for region crop in `match()` (C5/C7)**

After the `while` loop ends (before `return Pair(false, null)`), add cleanup for the region-cropped `srcBitmap`. Find the line `return Pair(false, null)` at the end of `match()` and insert before it:

```kotlin
        // Release the region-cropped srcBitmap if it was newly created (not aliased to sourceBitmap).
        if (srcBitmap !== sourceBitmap) srcBitmap.recycle()

        return Pair(false, null)
```

- [ ] **Step 5: Compile to verify**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL

### Task 1.2: Fix `matchAll()` tmp Bitmap and srcBitmap leaks (C3, C5)

**Files:**
- Modify: `app/src/main/java/com/steve1316/automation_library/utils/ImageUtils.kt:432-437` (tmp Bitmap) and end of matchAll (srcBitmap)

`matchAll()` already has try/finally for Mats (L423), but `tmp` Bitmap (L432-437) is never recycled, and `srcBitmap` (region crop, L384) is never recycled.

- [ ] **Step 1: Add tmp recycle inside the matchAll while loop**

In `matchAll()`, find the block (around L432-441):
```kotlin
            val tmp: Bitmap =
                if (newScale != 1.0) {
                    templateBitmap.scale((templateBitmap.width * newScale).toInt(), (templateBitmap.height * newScale).toInt())
                } else {
                    templateBitmap
                }

            // Create the Mats of both source and template images.
            Utils.bitmapToMat(srcBitmap, sourceMat)
            Utils.bitmapToMat(tmp, templateMat)
```

Replace with a version that recycles tmp after bitmapToMat:
```kotlin
            val tmp: Bitmap =
                if (newScale != 1.0) {
                    templateBitmap.scale((templateBitmap.width * newScale).toInt(), (templateBitmap.height * newScale).toInt())
                } else {
                    templateBitmap
                }

            try {
                // Create the Mats of both source and template images.
                Utils.bitmapToMat(srcBitmap, sourceMat)
                Utils.bitmapToMat(tmp, templateMat)
                // ... rest of the while loop body stays unchanged ...
            } finally {
                if (tmp !== templateBitmap) tmp.recycle()
            }
```

**However**, the matchAll while loop body is large (L425-540+) and already wrapped in the outer try/finally at L423. Inserting an inner try/finally for each iteration is messy. **Cleaner approach:** recycle `tmp` at the end of each loop iteration, right before the closing `}` of the while loop. Find the end of the matchAll while loop and add recycle there.

Read `app/src/main/java/com/steve1316/automation_library/utils/ImageUtils.kt` lines 530-560 to find the end of the matchAll while loop.

- [ ] **Step 2: Add srcBitmap recycle in matchAll's finally block**

The matchAll finally block (around L610-622) already releases Mats. Add srcBitmap recycle there. Find the finally block and add before the closing `}`:

```kotlin
            } finally {
                sourceMat.release()
                templateMat.release()
                clampedTemplateMat?.release()
                resultMat.release()
                // Release the region-cropped srcBitmap if it was newly created.
                if (srcBitmap !== sourceBitmap) srcBitmap.recycle()
            }
```

- [ ] **Step 3: Compile to verify**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL

### Task 1.3: Recycle templateBitmap in `findImage()` and `findAll()` (C4)

**Files:**
- Modify: `app/src/main/java/com/steve1316/automation_library/utils/ImageUtils.kt:897-964` (findImage) and `974-1000` (findAll)

`getBitmaps()` (L705-708) creates `templateBitmap` via `BitmapFactory.decodeStream`. `findImage()` and `findAll()` use it but never recycle it. `sourceBitmap` is a cache reference (must NOT recycle).

- [ ] **Step 1: Add templateBitmap recycle in `findImage()`**

In `findImage()`, wrap the while loop in try/finally to recycle templateBitmap. Find the `val (sourceBitmap, templateBitmap) = getBitmaps(templateName)` line (L917) and the two return statements (L954, L963). Replace the function body from L917 to L963 with:

```kotlin
        val (sourceBitmap, templateBitmap) = getBitmaps(templateName)
        try {
            while (numberOfTries > 0) {
                if (templateBitmap != null) {
                    val (resultFlag, matchLocation) = match(sourceBitmap, templateBitmap, templateName, region, useSingleScale = true, customConfidence = confidence)
                    if (!resultFlag) {
                        if (testMode) {
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
                    MessageLog.w(tag, "Template bitmap is null for ${templateName.uppercase()}. Cannot find image.")
                    numberOfTries -= 1
                }
            }

            return Pair(null, sourceBitmap)
        } finally {
            // Release the template Bitmap decoded from assets. Do NOT recycle sourceBitmap — it is a cache reference from MediaProjectionService.
            templateBitmap?.recycle()
        }
```

- [ ] **Step 2: Add templateBitmap recycle in `findAll()`**

In `findAll()`, wrap the body after `getBitmaps()` in try/finally. Find the `val (sourceBitmap, templateBitmap) = getBitmaps(templateName)` line (L979) and replace through the end of the function with:

```kotlin
        val (sourceBitmap, templateBitmap) = getBitmaps(templateName)
        try {
            if (templateBitmap != null) {
                val matchLocations = matchAll(sourceBitmap, templateBitmap, region = region, customConfidence = confidence)

                matchLocations.sortBy { it.x }
                matchLocations.sortBy { it.y }

                if (debugMode) {
                    MessageLog.d(tag, "Found match locations for $templateName: $matchLocations.")
                } else {
                    Log.d(tag, "Found match locations for $templateName: $matchLocations.")
                }

                return matchLocations
            } else {
                MessageLog.w(tag, "Template bitmap is null for ${templateName.uppercase()}. Cannot find all images.")
                return ArrayList()
            }
        } finally {
            templateBitmap?.recycle()
        }
```

- [ ] **Step 3: Compile to verify**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL

### Task 1.4: Fix `findText()` croppedBitmap leak (C2) and debug Mat leak (L1)

**Files:**
- Modify: `app/src/main/java/com/steve1316/automation_library/utils/ImageUtils.kt:1142-1284`

`findText()` creates `croppedBitmap` (L1175) which is never recycled. In the fast path with scale==1.0, `finalBitmap = croppedBitmap` (alias), so only one should be recycled. The debug Mat at L1179-1183 has no try/finally.

- [ ] **Step 1: Wrap findText body in try/finally to recycle croppedBitmap**

Find the `val croppedBitmap = Bitmap.createBitmap(...)` line (L1175) and the `return result` line (L1283). Wrap everything between them in try/finally. The finally block should recycle `croppedBitmap` only if it's not aliased to `finalBitmap`.

Replace the section from L1175 through L1283 with:

```kotlin
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
            val needRecycleFinal: Boolean
            val finalBitmap: Bitmap = if (!grayscale && !thresh) {
                if (clampedScale == 1.0) {
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
                val cvImage = Mat()
                val grayImage = Mat()
                var processedMat: Mat? = null
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

                    processedMat =
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
                    // processedMat is either cvImage, grayImage, or bwImage — all released above.
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

            // Drop low-confidence reads when minConfidence is configured (> 0).
            val confidenceFiltered: String = if (minConf > 0f && conf < minConf && rawText.isNotEmpty()) {
                MessageLog.d(tag, "[TEXT_DETECTION] Dropping low-confidence read '$rawText' (conf=$conf < minConf=$minConf).")
                ""
            } else {
                rawText
            }

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
            // Recycle the cropped Bitmap. If finalBitmap was aliased to it (fast path, scale==1.0),
            // needRecycleFinal was false and finalBitmap was not recycled above, so recycle here.
            // If finalBitmap was a different bitmap, croppedBitmap is still alive and recycled here.
            croppedBitmap.recycle()
        }
```

**Key change:** `croppedBitmap.recycle()` in the outer finally always runs. In the fast path with scale==1.0, `finalBitmap === croppedBitmap`, `needRecycleFinal=false` so `finalBitmap.recycle()` is skipped, and `croppedBitmap.recycle()` in the outer finally handles it. In all other paths, `finalBitmap` is a different bitmap that gets recycled by `if (needRecycleFinal) finalBitmap.recycle()`, and `croppedBitmap.recycle()` in the outer finally handles the cropped one. No double-recycle.

- [ ] **Step 2: Compile to verify**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL

### Task 1.5: Full assemble + commit Batch 1

- [ ] **Step 1: Full assemble debug**

Run: `./gradlew :app:assembleDebug 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Commit Batch 1 + pre-existing infrastructure reuse changes**

```bash
git add app/src/main/java/com/steve1316/automation_library/utils/ImageUtils.kt \
        app/src/main/java/com/steve1316/automation_library/utils/ocr/OcrEngine.kt \
        app/src/main/java/com/steve1316/automation_library/utils/ocr/OnnxPpocrEngine.kt \
        app/src/main/java/com/steve1316/automation_library/utils/BotService.kt \
        app/src/main/java/com/steve1316/automation_library/data/SharedData.kt \
        .learnings/LEARNINGS.md
git commit -m "fix(memory): eliminate Mat/Bitmap leaks in ImageUtils match/matchAll/findImage/findAll/findText

- Wrap match() Mat operations in try/finally (C6); alias-safe clampedTemplateMat release
- Recycle scaled tmp Bitmap in match()/matchAll() loops (C3)
- Recycle region-cropped srcBitmap in match()/matchAll() (C5/C7)
- Recycle templateBitmap in findImage()/findAll() (C4); sourceBitmap is cache ref, not recycled
- Recycle croppedBitmap in findText() with alias-safe outer try/finally (C2)
- Wrap debug Mat in findText() in try/finally (L1)
- Include pre-existing infrastructure reuse changes: OcrEngine confidence API, ImageUtils OCR MessageLog/isRunning, BotService OCR lifecycle, SharedData OCR config"
```

---

## Batch 2: MediaProjectionService.kt (C1, C8, H1, M4, M7)

**Files:**
- Modify: `app/src/main/java/com/steve1316/automation_library/utils/MediaProjectionService.kt`

### Task 2.1: Recycle old lastBitmap in takeScreenshotNow() (C1)

- [ ] **Step 1: Add lastBitmap recycle before assigning new bitmap**

Find L333-337 in `takeScreenshotNow()`:
```kotlin
                    val newBitmap = createBitmap(SharedData.displayWidth + rowPadding / pixelStride, SharedData.displayHeight)
                    newBitmap.copyPixelsFromBuffer(buffer)

                    // Update the cache.
                    lastBitmap = newBitmap
```

Replace with:
```kotlin
                    val newBitmap = createBitmap(SharedData.displayWidth + rowPadding / pixelStride, SharedData.displayHeight)
                    newBitmap.copyPixelsFromBuffer(buffer)

                    // Update the cache, recycling the previous bitmap to avoid native memory leaks.
                    // Note: callers of takeScreenshotNow() receive the old lastBitmap reference via
                    // the return value; they must finish using it before the next takeScreenshotNow() call.
                    // The single bot thread (BotService.thread) is the only screenshot consumer, so
                    // recycling here is safe — no concurrent readers hold the old reference.
                    val oldBitmap = lastBitmap
                    lastBitmap = newBitmap
                    oldBitmap?.recycle()
```

- [ ] **Step 2: Compile to verify**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

### Task 2.2: Remove leaking Looper thread (C8)

- [ ] **Step 1: Read the current threadHandler declaration and usage**

Search for `threadHandler` in MediaProjectionService.kt to see how it's used.

- [ ] **Step 2: Remove the useless Thread/Looper and use main looper Handler directly**

Find L474-482 in `onCreate()`:
```kotlin
        // Now, start a new Thread to handle processing new screenshots.
        object : Thread() {
            override fun run() {
                Log.d(tag, "Thread running for MediaProjection service.")
                threadHandler = Handler(Looper.getMainLooper())
                Looper.prepare()
                Looper.loop()
            }
        }.start()
```

Replace with:
```kotlin
        // Use the main looper for the screenshot handler. Previously a dedicated Thread with
        // Looper.prepare()/loop() was created but never quit(), leaking a thread per Service restart.
        // The main looper is sufficient since threadHandler is only used for periodic screenshot polls.
        threadHandler = Handler(Looper.getMainLooper())
```

- [ ] **Step 3: Compile to verify**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

### Task 2.3: Fix FileOutputStream leak (H1) and rowPixels GC pressure (M4)

- [ ] **Step 1: Fix FileOutputStream in takeScreenshotNow() with .use{}**

Find L355-367 in `takeScreenshotNow()`:
```kotlin
                val fos =
                    if (isException) {
                        FileOutputStream("$tempDirectory/exception.png")
                    } else {
                        FileOutputStream("$tempDirectory/source.png")
                    }

                try {
                    bitmapToReturn.compress(Bitmap.CompressFormat.PNG, 100, fos)
                    fos.close()
                } catch (e: IOException) {
                    Log.e(tag, "Failed to save screenshot: ${e.message}")
                }
```

Replace with:
```kotlin
                val fos =
                    if (isException) {
                        FileOutputStream("$tempDirectory/exception.png")
                    } else {
                        FileOutputStream("$tempDirectory/source.png")
                    }
                try {
                    fos.use {
                        bitmapToReturn.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                } catch (e: IOException) {
                    Log.e(tag, "Failed to save screenshot: ${e.message}")
                }
```

- [ ] **Step 2: Find and fix the same pattern in captureArea()**

Read `app/src/main/java/com/steve1316/automation_library/utils/MediaProjectionService.kt` around L283-296 to find the captureArea FileOutputStream and apply the same `.use{}` fix.

- [ ] **Step 3: Fix rowPixels GC pressure in captureArea() (M4)**

Find L249 `val rowPixels = IntArray(cropW)` inside the `for (y in 0 until cropH)` loop. Move it outside the loop:
```kotlin
            val rowPixels = IntArray(cropW)
            for (y in 0 until cropH) {
                // ... use rowPixels ...
            }
```

- [ ] **Step 4: Compile to verify**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

### Task 2.4: Full assemble + commit Batch 2

- [ ] **Step 1: Full assemble**

Run: `./gradlew :app:assembleDebug 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Commit Batch 2**

```bash
git add app/src/main/java/com/steve1316/automation_library/utils/MediaProjectionService.kt
git commit -m "fix(memory): recycle old lastBitmap, remove leaking Looper thread, fix FileOutputStream leaks

- Recycle previous lastBitmap before overwriting in takeScreenshotNow() (C1)
- Remove useless Thread+Looper.prepare/loop that never quit (C8); use main looper Handler
- Use .use{} for FileOutputStream in takeScreenshotNow/captureArea (H1)
- Move rowPixels IntArray outside captureArea loop to reduce GC pressure (M4)"
```

---

## Batch 3: MyAccessibilityService.kt (C8, H3)

**Files:**
- Modify: `app/src/main/java/com/steve1316/automation_library/utils/MyAccessibilityService.kt`

### Task 3.1: Use inJustDecodeBounds in randomizeTapLocation (C8)

- [ ] **Step 1: Replace BitmapFactory.decodeStream with inJustDecodeBounds**

Find L209-227 in `randomizeTapLocation()`:
```kotlin
        val dimensions: Pair<Int, Int> =
            try {
                val newImageSubFolder =
                    if (SharedData.templateSubfolderPathName.last() != '/') {
                        "${SharedData.templateSubfolderPathName}/"
                    } else {
                        SharedData.templateSubfolderPathName
                    }

                myContext.assets?.open("$newImageSubFolder$imageName.${SharedData.templateImageExt}").use { inputStream ->
                    // Get the Bitmap from the template image file and then start matching.
                    templateBitmap = BitmapFactory.decodeStream(inputStream)
                }
                Pair(templateBitmap.width, templateBitmap.height)
            } catch (e: FileNotFoundException) {
                Log.e(tag, "Cannot find the image asset file: $e")
                Log.e(tag, "Using a region of 25x25 as a fallback in order to proceed with tap location randomization.")
                Pair(25, 25)
            }
```

Replace with (no Bitmap allocation, just read dimensions):
```kotlin
        val dimensions: Pair<Int, Int> =
            try {
                val newImageSubFolder =
                    if (SharedData.templateSubfolderPathName.last() != '/') {
                        "${SharedData.templateSubfolderPathName}/"
                    } else {
                        SharedData.templateSubfolderPathName
                    }

                myContext.assets?.open("$newImageSubFolder$imageName.${SharedData.templateImageExt}").use { inputStream ->
                    // Read only the image dimensions without decoding pixels into memory.
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(inputStream, null, opts)
                    Pair(opts.outWidth, opts.outHeight)
                }
            } catch (e: FileNotFoundException) {
                Log.e(tag, "Cannot find the image asset file: $e")
                Log.e(tag, "Using a region of 25x25 as a fallback in order to proceed with tap location randomization.")
                Pair(25, 25)
            }
```

Also remove the now-unused `templateBitmap` declaration at L203 (`val templateBitmap: Bitmap`).

- [ ] **Step 2: Compile to verify**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

### Task 3.2: Null out instance in onDestroy (H3)

- [ ] **Step 1: Change instance to nullable and null it in onDestroy**

Find L40 in companion object:
```kotlin
        @SuppressLint("StaticFieldLeak")
        private lateinit var instance: MyAccessibilityService
```

Replace with:
```kotlin
        @SuppressLint("StaticFieldLeak")
        private var instance: MyAccessibilityService? = null
```

Find `getInstance()` (L57-65) and update to handle nullable:
```kotlin
        fun getInstance(): MyAccessibilityService {
            val inst = instance
            if (inst == null) {
                throw IllegalStateException("Accessibility Service not initialized. Disable and re-enable the Accessibility Service.")
            }
            if (!BotService.isRunning) {
                throw IllegalStateException("Accessibility Service is not running. Enable the Accessibility Service.")
            }
            return inst
        }
```

Find `onServiceConnected()` (L120-121) — `instance = this` stays the same (assigning non-null).

Find `onDestroy()` and add `instance = null`. Read the current onDestroy first to find the exact location.

- [ ] **Step 2: Compile to verify**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

### Task 3.3: Full assemble + commit Batch 3

- [ ] **Step 1: Full assemble**

Run: `./gradlew :app:assembleDebug 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Commit Batch 3**

```bash
git add app/src/main/java/com/steve1316/automation_library/utils/MyAccessibilityService.kt
git commit -m "fix(memory): avoid Bitmap allocation in randomizeTapLocation, null out instance on destroy

- Use inJustDecodeBounds=true to read template dimensions without decoding pixels (C8)
- Change instance to nullable var and null it in onDestroy to release Service Context (H3)"
```

---

## Batch 4: OnnxPpocrEngine.kt GC Pressure (H6, M1, M2, M3)

**Files:**
- Modify: `app/src/main/java/com/steve1316/automation_library/utils/ocr/OnnxPpocrEngine.kt`

### Task 4.1: Wrap detectTextLines Bitmaps in try/finally (H6)

- [ ] **Step 1: Read detectTextLines Bitmap section**

Read `app/src/main/java/com/steve1316/automation_library/utils/ocr/OnnxPpocrEngine.kt` around L412-423 to see the resized/input Bitmap handling.

- [ ] **Step 2: Wrap resized/input Bitmaps in try/finally**

Replace the Bitmap section with a try/finally version that recycles both bitmaps in the finally block.

- [ ] **Step 3: Compile to verify**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

### Task 4.2: Reuse FloatArray/IntArray in preprocessRec (M1)

- [ ] **Step 1: Add member buffers for preprocessRec**

Add member variables to the class for reusable buffers:
```kotlin
    // Reusable buffers for preprocessRec to reduce GC pressure on hot path.
    private var recFloatBuf: FloatArray? = null
    private var recPixelBuf: IntArray? = null
```

- [ ] **Step 2: Use member buffers in preprocessRec**

In `preprocessRec()`, replace `val floatArray = FloatArray(3 * planeSize)` and `val pixels = IntArray(planeSize)` with member-buffer reuse, allocating only when size changes.

- [ ] **Step 3: Compile to verify**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

### Task 4.3: Reuse FloatBuffer in recognizeBatchWithConfidence (M2) and detectTextLines (M3)

- [ ] **Step 1: Move slice FloatBuffer outside loop in recognizeBatchWithConfidence**

Find L235 `val slice = FloatBuffer.allocate(step)` inside the `for (i in 0 until n)` loop. Move it outside the loop and reuse.

- [ ] **Step 2: Make probBuf a member variable in detectTextLines**

Add `private var detProbBuf: FloatBuffer? = null` member and reuse it in detectTextLines.

- [ ] **Step 3: Compile to verify**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

### Task 4.4: Full assemble + commit Batch 4

- [ ] **Step 1: Full assemble**

Run: `./gradlew :app:assembleDebug 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Commit Batch 4**

```bash
git add app/src/main/java/com/steve1316/automation_library/utils/ocr/OnnxPpocrEngine.kt
git commit -m "perf(memory): reuse buffers in OCR hot path, fix Bitmap leak in detectTextLines

- Wrap resized/input Bitmaps in detectTextLines in try/finally (H6)
- Reuse FloatArray/IntArray in preprocessRec via member buffers (M1)
- Move FloatBuffer outside loop in recognizeBatchWithConfidence (M2)
- Reuse probBuf in detectTextLines via member buffer (M3)"
```

---

## Batch 5: BotService + DiscordUtils Thread Management (H4, H5, M9, L3)

**Files:**
- Modify: `app/src/main/java/com/steve1316/automation_library/utils/BotService.kt`
- Modify: `app/src/main/java/com/steve1316/automation_library/utils/DiscordUtils.kt`

### Task 5.1: Track and join OCR/Discord threads in BotService (H4)

- [ ] **Step 1: Add thread references to BotService companion**

- [ ] **Step 2: Save thread refs when starting OCR warmup and Discord threads**

- [ ] **Step 3: Interrupt + join in performCleanUp**

- [ ] **Step 4: Compile to verify**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

### Task 5.2: Make performCleanUp idempotent (M9)

- [ ] **Step 1: Add AtomicBoolean for cleanup guard**

- [ ] **Step 2: Wrap performCleanUp body in check + set**

- [ ] **Step 3: Compile to verify**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

### Task 5.3: Shutdown Kord client on all early-exit paths (H5)

- [ ] **Step 1: Read DiscordUtils.main() early-exit paths**

- [ ] **Step 2: Wrap client usage in try/finally with shutdown**

- [ ] **Step 3: Compile to verify**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

### Task 5.4: Full assemble + commit Batch 5

- [ ] **Step 1: Full assemble**

Run: `./gradlew :app:assembleDebug 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Commit Batch 5**

```bash
git add app/src/main/java/com/steve1316/automation_library/utils/BotService.kt \
        app/src/main/java/com/steve1316/automation_library/utils/DiscordUtils.kt
git commit -m "fix(memory): track and join OCR/Discord threads, idempotent cleanup, Kord shutdown

- Save OCR warmup and Discord thread refs, interrupt+join in performCleanUp (H4)
- Guard performCleanUp with AtomicBoolean for idempotency (M9)
- Shutdown Kord client on all early-exit paths in DiscordUtils.main() (H5)"
```

---

## Batch 6: IO/Cursor/Stream Leaks (H2, H7, M6, M8)

**Files:**
- Modify: `app/src/main/java/com/steve1316/automation_library/utils/ImageUtils.kt` (getBitmapFromURL)
- Modify: `app/src/main/java/com/steve1316/automation_library/utils/SQLiteSettingsManager.kt`
- Modify: `app/src/main/java/com/steve1316/automation_library/utils/GlobalExceptionHandler.kt`

### Task 6.1: Fix getBitmapFromURL stream/connection leaks (H2)

- [ ] **Step 1: Rewrite getBitmapFromURL with proper try/finally + .use{}**

- [ ] **Step 2: Compile to verify**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

### Task 6.2: Fix Cursor leak in SQLiteSettingsManager (H7)

- [ ] **Step 1: Change cursor to .use{}**

- [ ] **Step 2: Compile to verify**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

### Task 6.3: Prevent duplicate GlobalExceptionHandler registration (M6)

- [ ] **Step 1: Add static registered flag**

- [ ] **Step 2: Compile to verify**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

### Task 6.4: Full assemble + commit Batch 6

- [ ] **Step 1: Full assemble**

Run: `./gradlew :app:assembleDebug 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Commit Batch 6**

```bash
git add app/src/main/java/com/steve1316/automation_library/utils/ImageUtils.kt \
        app/src/main/java/com/steve1316/automation_library/utils/SQLiteSettingsManager.kt \
        app/src/main/java/com/steve1316/automation_library/utils/GlobalExceptionHandler.kt
git commit -m "fix(memory): fix stream/connection/cursor leaks, prevent duplicate exception handler

- Rewrite getBitmapFromURL with try/finally + .use{} for InputStream and HttpURLConnection (H2)
- Use .use{} for Cursor in SQLiteSettingsManager.loadSetting (H7)
- Add static flag to prevent duplicate GlobalExceptionHandler registration (M6)"
```

---

## Batch 7: Low-Priority Style/GC Fixes (L2, L4, L5, M7, L6)

**Files:**
- Modify: `app/src/main/java/com/steve1316/automation_library/utils/NotificationUtils.kt`
- Modify: `app/src/main/java/com/steve1316/automation_library/utils/AndroidComponents.kt`
- Modify: `app/src/main/java/com/steve1316/automation_library/utils/MessageLog.kt`
- Modify: `app/src/main/java/com/steve1316/automation_library/utils/MediaProjectionService.kt` (M7)
- Modify: `app/src/main/java/com/steve1316/automation_library/utils/ImageUtils.kt` (L6 doc)

### Task 7.1: NotificationUtils nullable var (L2)

### Task 7.2: AndroidComponents static Handler (L4)

### Task 7.3: MessageLog move string concat out of sync (L5)

### Task 7.4: OrientationChangeCallback static inner class (M7)

### Task 7.5: Document findImage return value ownership (L6)

### Task 7.6: Full assemble + commit Batch 7

- [ ] **Step 1: Full assemble**

Run: `./gradlew :app:assembleDebug 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Commit Batch 7**

```bash
git add app/src/main/java/com/steve1316/automation_library/utils/NotificationUtils.kt \
        app/src/main/java/com/steve1316/automation_library/utils/AndroidComponents.kt \
        app/src/main/java/com/steve1316/automation_library/utils/MessageLog.kt \
        app/src/main/java/com/steve1316/automation_library/utils/MediaProjectionService.kt \
        app/src/main/java/com/steve1316/automation_library/utils/ImageUtils.kt
git commit -m "perf: minor GC and style fixes (nullable vars, static Handler, sync block, doc)

- NotificationUtils: lateinit var -> nullable var (L2)
- AndroidComponents: reuse static Handler (L4)
- MessageLog: move string concat out of synchronized block (L5)
- OrientationChangeCallback: static inner class + WeakReference (M7)
- ImageUtils: document findImage sourceBitmap ownership (L6)"
```

---

## Summary

| Batch | Files | Issues Fixed | Risk |
|-------|-------|--------------|------|
| 1 | ImageUtils.kt | C2, C3, C4, C5, C6, C7, L1 | High (aliasing) |
| 2 | MediaProjectionService.kt | C1, C8, H1, M4 | Medium (lastBitmap callers) |
| 3 | MyAccessibilityService.kt | C8, H3 | Low |
| 4 | OnnxPpocrEngine.kt | H6, M1, M2, M3 | Low |
| 5 | BotService.kt, DiscordUtils.kt | H4, H5, M9, L3 | Medium (thread join) |
| 6 | ImageUtils.kt, SQLiteSettingsManager.kt, GlobalExceptionHandler.kt | H2, H7, M6, M8 | Low |
| 7 | NotificationUtils.kt, AndroidComponents.kt, MessageLog.kt, MediaProjectionService.kt, ImageUtils.kt | L2, L4, L5, M7, L6 | Low |

**Total: 30 issues fixed across 7 batches, 7 commits.**
