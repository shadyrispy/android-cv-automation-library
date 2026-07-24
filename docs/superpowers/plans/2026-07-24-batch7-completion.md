# Batch 7 Completion Plan (L4, L5, M7, L6)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the remaining Batch 7 low-priority memory/GC fixes (L4, L5, M7, L6) that were left as stubs in the master plan, then commit the whole batch.

**Architecture:** Four independent small fixes, each isolated to one file, each verified with `./gradlew :app:compileDebugKotlin` before moving on. The batch closes with a full `assembleDebug` and a single commit covering Batch 7 (NotificationUtils L2 already committed locally but uncommitted; this commit captures the full batch).

**Tech Stack:** Kotlin, Android (Service/Handler/OrientationEventListener), OpenCV (Mat), java.util.concurrent synchronization.

**Project context:**
- Android **library** (AAR). No unit tests — verification is `./gradlew assembleDebug` + code review.
- User rule: "git commit 之前一定要在本地编译测试无问题" — every commit must pass `./gradlew assembleDebug`.
- User preference: minimal code changes, reuse existing infrastructure (MessageLog, SharedData, BotService.isRunning).
- Comments in English to match existing codebase style.
- M7 originally proposed "static inner class + WeakReference"; on inspection the real leak is that `MediaProjectionService` has no `onDestroy`, so the `OrientationEventListener` stays registered with the system if the Service is force-stopped without going through `MediaProjectionStopCallback.onStop()`. The minimal, equivalent fix is to add `onDestroy()` that disables the callback. This is the approach used here.

**Pre-existing uncommitted changes:** Task 7.1 (NotificationUtils L2 nullable var) is already applied to disk but uncommitted. It will be included in the Batch 7 commit.

---

## File Structure

Files to modify (no new files created):

| File | Task | Responsibility |
|------|------|----------------|
| `app/src/main/java/com/steve1316/automation_library/utils/AndroidComponents.kt` | 1 | Reuse a single static main-looper Handler instead of allocating one per toast |
| `app/src/main/java/com/steve1316/automation_library/utils/MessageLog.kt` | 2 | Move string concatenation out of the `synchronized(messageLogLock)` block in `log()` |
| `app/src/main/java/com/steve1316/automation_library/utils/MediaProjectionService.kt` | 3 | Add `onDestroy()` that disables `orientationChangeCallback` to prevent Service leak |
| `app/src/main/java/com/steve1316/automation_library/utils/ImageUtils.kt` | 4 | Document `sourceBitmap` ownership in `findImage`/`findAll` KDoc |

---

## Task 1: AndroidComponents — reuse static Handler (L4)

**Files:**
- Modify: `app/src/main/java/com/steve1316/automation_library/utils/AndroidComponents.kt`

**Problem:** `showCustomToast` calls `Handler(Looper.getMainLooper())` on every invocation, allocating a new Handler each time. The main looper is process-global and never changes, so a single reusable Handler is sufficient and reduces GC pressure in tight UI loops.

- [ ] **Step 1: Read current file**

Read `app/src/main/java/com/steve1316/automation_library/utils/AndroidComponents.kt` to confirm the structure (it is a 29-line `object` with one `showCustomToast` function).

- [ ] **Step 2: Add a private static Handler member and reuse it**

Replace the entire file content with:

```kotlin
package com.steve1316.automation_library.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Shared Android components file that holds custom implementations of Android elements.
 */
object AndroidComponents {
    // Single reusable Handler bound to the main looper. The main looper is process-global
    // and lives for the lifetime of the app, so one Handler can serve every toast without
    // allocating a new instance per call (L4 fix).
    private val mainHandler: Handler = Handler(Looper.getMainLooper())

    /**
     * Displays a Toast with a custom duration.
     *
     * @param context The Context to use for the Toast.
     * @param text The text to display in the Toast.
     * @param durationMs The duration in milliseconds before the Toast is cancelled.
     */
    fun showCustomToast(context: Context, text: CharSequence, durationMs: Long) {
        // Create the Toast with LENGTH_SHORT as the base duration.
        val toast = Toast.makeText(context, text, Toast.LENGTH_SHORT)
        toast.show()

        // Reuse the shared main-looper Handler to cancel the Toast after the specified duration.
        mainHandler.postDelayed({
            toast.cancel()
        }, durationMs)
    }
}
```

- [ ] **Step 3: Compile to verify**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -15`
Expected: BUILD SUCCESSFUL

---

## Task 2: MessageLog — move string concat out of synchronized block (L5)

**Files:**
- Modify: `app/src/main/java/com/steve1316/automation_library/utils/MessageLog.kt:263-293` (the `log()` function)

**Problem:** In `log()`, the `msg` string is built with `"\n$prefix " + message.removePrefix("\n")` **inside** the `synchronized(messageLogLock)` block (L287-292). String concatenation is cheap but the lock is held during the work; under heavy concurrent logging this serializes threads unnecessarily. The `msg` value does not depend on any shared mutable state — it can be built before entering the synchronized block. Only the `messageLog.add(msg)` and `EventBus.post(...)` need the lock.

- [ ] **Step 1: Read current `log()` function**

Read `app/src/main/java/com/steve1316/automation_library/utils/MessageLog.kt` lines 263-293 to confirm current structure.

- [ ] **Step 2: Hoist the `msg` construction above the synchronized block**

Find the `log()` function (around L263-293). Replace the body from `var prefix = ""` (L272) through the end of the function with:

```kotlin
            var prefix = ""
            if (!skipPrintTime) {
                prefix += "${getElapsedTimeString()} "
            }

            prefix += "[${level.name}]"

            // Build the final message string BEFORE acquiring the lock. String concat does not
            // touch shared mutable state, so holding the lock during this work is wasteful and
            // serializes concurrent loggers (L5 fix).
            val msg =
                if (message.startsWith("\n")) {
                    "\n$prefix " + message.removePrefix("\n")
                } else {
                    "$prefix $message"
                }

            // Synchronize access to messageLog and EventBus posting to prevent race conditions.
            synchronized(messageLogLock) {
                messageLog.add(msg)

                // Send the message to the frontend.
                EventBus.getDefault().post(JSEvent("MessageLog", msg, disableOutput))
            }
```

- [ ] **Step 3: Compile to verify**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -15`
Expected: BUILD SUCCESSFUL

---

## Task 3: MediaProjectionService — add onDestroy to disable OrientationChangeCallback (M7)

**Files:**
- Modify: `app/src/main/java/com/steve1316/automation_library/utils/MediaProjectionService.kt` (add `onDestroy()` after `onStartCommand`, around L560)

**Problem:** `OrientationChangeCallback` is a non-static inner class holding an implicit strong reference to the Service. It is registered with the system via `enable()` (L672) and only disabled inside `MediaProjectionStopCallback.onStop()` (L615). If the Service is destroyed without `mediaProjection.stop()` being called (force-stop, system kill, or any path that doesn't trigger the MediaProjection stop callback), the `OrientationEventListener` remains registered and keeps the Service alive — a classic Service leak.

The minimal fix is to override `onDestroy()` to disable the callback. This guarantees the listener is released regardless of how the Service is torn down. Converting to a static inner class + WeakReference (as originally proposed) is a larger refactor that the minimal-fix approach supersedes; `onDestroy` is sufficient because once the listener is disabled, it no longer holds a live reference to the system event source.

- [ ] **Step 1: Read the end of onStartCommand and the start of OrientationChangeCallback**

Read `app/src/main/java/com/steve1316/automation_library/utils/MediaProjectionService.kt` lines 555-590 to find the exact insertion point (after `onStartCommand` returns `START_NOT_STICKY` at L560, before the `OrientationChangeCallback` KDoc at L563).

- [ ] **Step 2: Insert onDestroy() override**

Insert the following between the closing `}` of `onStartCommand` (L561) and the `/**` KDoc for `OrientationChangeCallback` (L563):

```kotlin
    override fun onDestroy() {
        super.onDestroy()

        // Disable the orientation listener to prevent the system from retaining a reference to
        // the Service via the non-static inner OrientationChangeCallback (M7 fix). Normally the
        // listener is disabled inside MediaProjectionStopCallback.onStop(), but if the Service is
        // torn down without going through that path (force-stop, system kill, etc.), the listener
        // would otherwise keep the Service alive.
        orientationChangeCallback?.disable()
        orientationChangeCallback = null
    }

```

- [ ] **Step 3: Compile to verify**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -15`
Expected: BUILD SUCCESSFUL

---

## Task 4: ImageUtils — document findImage/findAll sourceBitmap ownership (L6)

**Files:**
- Modify: `app/src/main/java/com/steve1316/automation_library/utils/ImageUtils.kt:898-907` (findImage KDoc) and `984-990` (findAll KDoc)

**Problem:** `findImage()` returns `Pair<Point?, Bitmap>` where the `Bitmap` is `sourceBitmap` — a **cache reference** from `MediaProjectionService.lastBitmap`. Callers who treat it as owned and call `recycle()` will corrupt the cache; callers who hold it long-term may see it recycled underneath them by the next `takeScreenshotNow()`. The KDoc currently says only "Can be null" and does not warn about ownership. Same for `findAll()` returning `sourceBitmap` implicitly via `matchAll` — actually `findAll` returns only `ArrayList<Point>`, so only `findImage` needs the ownership note.

- [ ] **Step 1: Read current KDoc blocks**

Read `app/src/main/java/com/steve1316/automation_library/utils/ImageUtils.kt` lines 898-916 (findImage KDoc + signature) and 984-995 (findAll KDoc + signature) to confirm current text.

- [ ] **Step 2: Update findImage KDoc to document sourceBitmap ownership**

Find the findImage KDoc (L898-907) and replace the `@return` line:

```kotlin
     * @return Pair object consisting of the Point object containing the location of the match and the source screenshot. Can be null.
```

with:

```kotlin
     * @return Pair object consisting of the Point object containing the location of the match (null if not found) and the source screenshot.
     *
     * **Ownership note:** the returned Bitmap is a live reference to the MediaProjectionService screenshot cache (`lastBitmap`), NOT a copy. Callers must NOT call `recycle()` on it, and must finish using it before the next `takeScreenshotNow()` call, which recycles the previous cached bitmap.
```

- [ ] **Step 3: Update findAll KDoc to clarify it does not return a bitmap**

Find the findAll KDoc (L984-990) and replace the `@return` line:

```kotlin
     * @return An ArrayList of Point objects containing all the occurrences of the specified image or null if not found.
```

with:

```kotlin
     * @return An ArrayList of Point objects containing all the occurrences of the specified image. Empty if none found.
```

(The original "or null if not found" is inaccurate — `findAll` returns `ArrayList()` not `null`. This is a minor doc-correctness fix bundled with the ownership audit.)

- [ ] **Step 4: Compile to verify (KDoc-only change, but confirm no stray typos broke anything)**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -15`
Expected: BUILD SUCCESSFUL

---

## Task 5: Full assemble + commit Batch 7

- [ ] **Step 1: Full assemble debug**

Run: `./gradlew :app:assembleDebug 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL, `app-debug.aar` produced under `app/build/outputs/aar/`.

- [ ] **Step 2: Verify AAR size is still under 4MB**

Run: `ls -lh app/build/outputs/aar/*.aar`
Expected: file size < 4MB (Batch 7 changes are source-only; no new assets/dependencies. Expected size unchanged from current ~3.8MB.)

- [ ] **Step 3: Stage Batch 7 files**

```bash
git add app/src/main/java/com/steve1316/automation_library/utils/NotificationUtils.kt \
        app/src/main/java/com/steve1316/automation_library/utils/AndroidComponents.kt \
        app/src/main/java/com/steve1316/automation_library/utils/MessageLog.kt \
        app/src/main/java/com/steve1316/automation_library/utils/MediaProjectionService.kt \
        app/src/main/java/com/steve1316/automation_library/utils/ImageUtils.kt
```

- [ ] **Step 4: Commit Batch 7**

```bash
git commit -m "perf(memory): minor GC and lifecycle fixes (Batch 7)

- NotificationUtils: lateinit var -> nullable var with null safety (L2)
- AndroidComponents: reuse single static main-looper Handler (L4)
- MessageLog: hoist string concat out of synchronized block in log() (L5)
- MediaProjectionService: add onDestroy() to disable OrientationChangeCallback, preventing Service leak on non-stop teardown paths (M7)
- ImageUtils: document findImage sourceBitmap cache ownership + fix findAll @return doc (L6)"
```

- [ ] **Step 5: Confirm clean working tree**

Run: `git status`
Expected: nothing to commit, working tree clean (or only unrelated files).

---

## Summary

| Task | File | Issue | Change |
|------|------|-------|--------|
| 1 | AndroidComponents.kt | L4 | One static Handler, reused per toast |
| 2 | MessageLog.kt | L5 | Build `msg` before entering `synchronized` block |
| 3 | MediaProjectionService.kt | M7 | Add `onDestroy()` to disable orientation callback |
| 4 | ImageUtils.kt | L6 | Document `sourceBitmap` cache ownership + fix findAll doc |
| 5 | — | — | Full assemble + commit |

**Total: 4 issues fixed, 5 files touched, 1 commit.** With this batch the master memory-leak plan is fully executed.
