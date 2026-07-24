# Learnings

Corrections, insights, and knowledge gaps captured during development.

**Categories**: correction | insight | knowledge_gap | best_practice

---

## [LRN-20260724-001] knowledge_gap

**Logged**: 2026-07-24T16:30:00+08:00
**Priority**: critical
**Status**: pending
**Area**: backend

### Summary
自动化库的 AndroidManifest 几乎为空,不声明任何权限与组件,导致消费方必须自行声明所有 service/permission,且库内完全没有 PermissionChecker/PermissionHelper 类。

### Details
- `app/src/main/AndroidManifest.xml` 仅声明移除 MlKitInitProvider,无任何 `<uses-permission>` / `<service>` / `<activity>`
- 库代码实际需要 SYSTEM_ALERT_WINDOW、BIND_ACCESSIBILITY_SERVICE、FOREGROUND_SERVICE_MEDIA_PROJECTION、POST_NOTIFICATIONS、REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
- 全库无统一权限检测/请求类
- strings.xml 中预留的 `overlay_disabled`/`accessibility_disabled`/`go_to_settings` 文案无任何代码引用(死字符串)

### Suggested Action
在库 manifest 中声明所有权限和 service(用 manifest-merger 自动合并);新建 `PermissionChecker`/`PermissionGuide` 统一管理三类授权。

### Metadata
- Source: code_review
- Related Files: app/src/main/AndroidManifest.xml, app/src/main/res/values/strings.xml
- Tags: permissions, manifest, library-design

---

## [LRN-20260724-002] knowledge_gap

**Logged**: 2026-07-24T16:30:00+08:00
**Priority**: critical
**Status**: pending
**Area**: backend

### Summary
`MyAccessibilityService.checkStatus()` 用了 `ActivityManager.getRunningServices`,Android 8+ 已废弃且只返回本应用服务,无法检测无障碍服务(由系统管理),导致方法永远返回 false。

### Details
`MyAccessibilityService.kt#L102-L110` 使用 `ActivityManager.getRunningServices(Integer.MAX_VALUE)` 然后遍历查找 `MyAccessibilityService`。无障碍服务由系统 `AccessibilityManagerService` 管理,不在 `getRunningServices` 列表中。

### Suggested Action
改为查 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`(支持 `:` 和 `;` 双分隔符以兼容 EMUI)。

### Metadata
- Source: code_review
- Related Files: app/src/main/java/com/steve1316/automation_library/utils/MyAccessibilityService.kt
- Tags: accessibility, api-deprecation

---

## [LRN-20260724-003] insight

**Logged**: 2026-07-24T16:30:00+08:00
**Priority**: critical
**Status**: pending
**Area**: backend

### Summary
`ImageUtils.findImage()` 在 templateBitmap 为 null 时无限循环(100% CPU 永久挂死)。

### Details
`ImageUtils.kt#L926-L964` 的 `while (numberOfTries > 0)` 循环只在 `templateBitmap != null` 分支里减 `numberOfTries`,没有 else 分支。当 assets 文件缺失/路径错误时触发死循环。

### Suggested Action
添加 `else { numberOfTries -= 1 }` 或 `else { break }`。

### Metadata
- Source: code_review
- Related Files: app/src/main/java/com/steve1316/automation_library/utils/ImageUtils.kt
- Tags: bug, infinite-loop, core-api

---

## [LRN-20260724-004] insight

**Logged**: 2026-07-24T16:30:00+08:00
**Priority**: critical
**Status**: pending
**Area**: backend

### Summary
`ImageUtils.match()` 在成功匹配时早退,4 个 native Mat 未 release,长时间运行 native 内存持续增长。

### Details
`ImageUtils.kt#L280-L368` 中 `return Pair(true, matchLocation)` 在 `release()` 之前发生。`matchAll()` 同样模式。OpenCV Mat 是 native 内存,GC 不立即回收。

### Suggested Action
用 `try/finally` 或 `Mat.use { }` 扩展确保 release。

### Metadata
- Source: code_review
- Related Files: app/src/main/java/com/steve1316/automation_library/utils/ImageUtils.kt
- Tags: bug, memory-leak, opencv

---

## [LRN-20260724-005] knowledge_gap

**Logged**: 2026-07-24T16:30:00+08:00
**Priority**: high
**Status**: pending
**Area**: backend

### Summary
`MediaProjectionService.startForeground` 缺少 `foregroundServiceType` 参数,Android 14+ 会抛 SecurityException 崩溃。

### Details
`MediaProjectionService.kt#L527` 调用 `startForeground(notificationID, notification)`,Android 14(targetSdk 34+)对 mediaProjection 类型前台服务强制要求传 type 参数。Manifest 也未声明 `<service android:foregroundServiceType="mediaProjection">` 和 `FOREGROUND_SERVICE_MEDIA_PROJECTION` 权限。

### Suggested Action
Android 10+ 改用 `startForeground(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)` 重载;Manifest 同步声明。

### Metadata
- Source: code_review
- Related Files: app/src/main/java/com/steve1316/automation_library/utils/MediaProjectionService.kt
- Tags: android-14, foreground-service, compatibility

---

## [LRN-20260724-006] knowledge_gap

**Logged**: 2026-07-24T16:30:00+08:00
**Priority**: high
**Status**: pending
**Area**: backend

### Summary
Android 14+ MediaProjection 不允许复用上次 resultCode/data,每次启动新会话必须重新走 createScreenCaptureIntent 流程。

### Details
Android 14 行为变更:MediaProjection consent 不可复用,且同一进程两次拒绝后会被限流。当前 `MediaProjectionService.getScreenCaptureIntent` 已用 `MediaProjectionConfig.createConfigForDefaultDisplay()`(API 34+),但缓存策略需调整。

### Suggested Action
引导页拿到 RESULT_OK 后立即通过 Intent extra 回传宿主,宿主立即 startService;不要持久化缓存 data。

### Metadata
- Source: research
- Related Files: app/src/main/java/com/steve1316/automation_library/utils/MediaProjectionService.kt
- Tags: android-14, media-projection
- See Also: https://developer.android.com/about/versions/14/behavior-changes-14#media-projection-consent

---

## [LRN-20260724-007] knowledge_gap

**Logged**: 2026-07-24T16:30:00+08:00
**Priority**: medium
**Status**: pending
**Area**: backend

### Summary
`accessibility_service_config.xml` 中 `settingsActivity="com.example.android.accessibility.ServiceSettingsActivity"` 是 Google 示例占位符,指向不存在的 Activity,点击无障碍服务条目会 ClassNotFoundException。

### Details
`accessibility_service_config.xml#L13` 直接复制了官方示例的占位类名。用户在系统设置里点服务旁边的齿轮会跳到不存在的 Activity。

### Suggested Action
删除该属性,或改为消费方可配置的占位。

### Metadata
- Source: code_review
- Related Files: app/src/main/res/xml/accessibility_service_config.xml
- Tags: accessibility, config-bug

---

## [LRN-20260724-008] insight

**Logged**: 2026-07-24T16:30:00+08:00
**Priority**: high
**Status**: pending
**Area**: backend

### Summary
`MyAccessibilityService.randomizeTapLocation` 的 break 条件用 `||` 应为 `&&`,导致第一次循环必然 break,随机化形同虚设。

### Details
`MyAccessibilityService.kt#L243` 的 `if (newX > x0 || newX < x1 || newY > y0 || newY < y1) break`。newX 几乎总是 > x0,所以第一次就 break。

### Suggested Action
`||` 改为 `&&`。

### Metadata
- Source: code_review
- Related Files: app/src/main/java/com/steve1316/automation_library/utils/MyAccessibilityService.kt
- Tags: bug, gesture

---

## [LRN-20260724-009] knowledge_gap

**Logged**: 2026-07-24T17:50:00+08:00
**Priority**: high
**Status**: pending
**Area**: backend

### Summary
MNN Android AAR 无可靠的 Maven Central/JitPack 官方发布,集成需自行 NDK 交叉编译,工作量大;onnxruntime-android 有 Maven Central 官方发布,Gradle 一行引入。

### Details
调研 MNN + PP-OCRv6 OCR 迁移方案时发现:
- Maven Central 上仅有极旧的 `com.alibaba.android.workbench:mnnruntime:0.0.4`(2020年)
- 各教程引用的 `com.alibaba.android:MNN:2.9.0@aar` 均无法在 Maven Central 验证存在
- MNN 官方 README 和教程均指向 `git clone` + NDK CMake 交叉编译 + `package_android.sh` 自建 AAR
- 对比:onnxruntime-android 在 Maven Central 有稳定官方发布 `com.microsoft.onnxruntime:onnxruntime-android`,且 PP-OCRv6 官方原生提供 ONNX 格式模型可直接使用,无需模型转换
- MNN 的优势(so 库 800KB vs onnxruntime 3-5MB)在需要自建 AAR 的工程成本面前不划算

### Suggested Action
OCR 迁移回退到 onnxruntime-android;若未来 MNN 发布官方 Maven AAR 可重新评估。

### Metadata
- Source: research
- Related Files: gradle/libs.versions.toml, app/build.gradle.kts
- Tags: mnn, onnxruntime, ocr, dependency-management, android-aar
- See Also: FEAT-20260724-002

---

## [LRN-20260724-011] best_practice

**Logged**: 2026-07-24T18:30:00+08:00
**Priority**: critical
**Status**: promoted
**Area**: backend

### Summary
新增模块必须复用已有基础设施(MessageLog/SettingsHelper/NotificationUtils/SharedData),不能使用 android.util.Log 或硬编码参数。

### Details
OCR 引擎(OnnxPpocrEngine + ImageUtils.findText*)最初实现时未接入项目基础设施:
- 用 `android.util.Log` 而非 `MessageLog`,导致错误不写日志文件、不发前端
- 所有 det/rec 参数硬编码,无 SettingsHelper 配置项
- `OcrEngine.close()` 从未被调用,ONNX native session 内存泄漏
- `debugMode` 开关对 OCR 无效(`if (debugMode) Log.d` 无意义)
- `BotService.isRunning` 中断检查缺失

对比 `findImage` 系列的规范:用 `MessageLog.d/i/w` 写 Logcat + 写文件 + 发 EventBus;confidence 阈值可配置;有 `BotService.isRunning` 中断检查。

### Suggested Action
1. 所有新增代码的日志统一用 `MessageLog.v/d/i/w/e(tag, msg)`
2. 可配置参数通过 `SharedData` getter + `SettingsHelper.getXxxSetting` 读取
3. native 资源在 `BotService.performCleanUp` 中释放
4. 长耗时操作加 `BotService.isRunning` 中断检查
5. 致命错误考虑 `NotificationUtils.updateNotification` 通知用户

### Metadata
- Source: simplify-and-harden
- Related Files: OnnxPpocrEngine.kt, OcrEngine.kt, ImageUtils.kt, BotService.kt, SharedData.kt
- Tags: infrastructure, logging, settings, resource-management
- Pattern-Key: harden.reuse_infrastructure
- Promoted: project_memory.md

---

## [LRN-20260724-010] best_practice

**Logged**: 2026-07-24T17:35:00+08:00
**Priority**: high
**Status**: resolved
**Area**: backend

### Summary
PP-OCRv6 ONNX 模型与字典可从 ModelScope 下载(huggingface.co 国内不可达);onnxruntime-android API 有几个易错点需注意。

### Details
1. **模型下载源**:huggingface.co 在国内 curl 超时;ModelScope 提供 `PaddlePaddle/PP-OCRv6_tiny_rec_onnx` 仓库,API 下载链接格式:
   `https://www.modelscope.cn/api/v1/models/{namespace}/{model}/repo?Revision=master&FilePath={file}`
   字典 `ppocr_keys_v1.txt` 在主 PaddleOCR 仓库不存在,但 `NexaAIDev/paddleocr-npu` 仓库有(25.63KB,6622 行)。

2. **onnxruntime-android API 易错点**:
   - `OrtSession.Result.get(0)` / `outputs[0]` 返回 `OnnxValue`,需 `as OnnxTensor` 才能访问 `floatBuffer`
   - `OnnxValue.info` 返回 `ValueInfo` 父类,需 `as TensorInfo` 才能访问 `shape`
   - Kotlin `Throwable.stackTraceToString()` 是函数,必须带括号(`${e.stackTraceToString()}`)

3. **Android library AAR 与 api 依赖的 native so**:
   - library 模块的 AAR **不会**打包 `api(externalAar)` 依赖的 native so
   - 消费方 app 通过 maven 依赖(pom.xml)自动拉取 onnxruntime-android 的 so
   - library 的 jniLibs(本地 so,如 OpenCV)**会**打包进 AAR

### Suggested Action
后续 ONNX Runtime 相关集成参考本条;ModelScope 优先作为国内模型下载源。

### Resolution
- **Resolved**: 2026-07-24T17:35:00+08:00
- **Notes**: PP-OCRv6 Tiny rec 迁移完成,ImageUtils.findText() 切换到 OcrEngine,AAR 7.7MB(含 4.3MB 模型)。

### Metadata
- Source: conversation
- Related Files: app/src/main/java/com/steve1316/automation_library/utils/ocr/OnnxPpocrEngine.kt, app/src/main/java/com/steve1316/automation_library/utils/ImageUtils.kt
- Tags: onnxruntime, ppocrv6, modelscope, android-aar, api-gotcha
- See Also: FEAT-20260724-002, LRN-20260724-009

---

## [LRN-20260724-012] knowledge_gap

**Logged**: 2026-07-24T19:30:00+08:00
**Priority**: low
**Status**: resolved
**Area**: infra

### Summary
`npx skills find` 在当前环境不可用（npx 未安装），find-skills skill 无法实际搜索技能生态。

### Details
find-skills skill 依赖 `npx skills find [query]` CLI 工具，但当前 macOS 环境中没有安装 npx（Node.js/npm 未安装或不在 PATH 中）。尝试 `which npx` 返回 "npx not available"。

### Suggested Action
需要先安装 Node.js（含 npx）才能使用 find-skills；或在有 Node.js 的环境中使用。

### Resolution
- **Resolved**: 2026-07-24T19:30:00+08:00
- **Notes**: 改用 WebSearch 或直接用 Grep/Read 手动扫描代码完成内存泄漏分析。

### Metadata
- Source: conversation
- Tags: npx, nodejs, tooling, find-skills

---

## [LRN-20260724-013] best_practice

**Logged**: 2026-07-24T19:45:00+08:00
**Priority**: critical
**Status**: pending
**Area**: backend

### Summary
Android 自动化库的 native 资源（Mat/Bitmap/ONNX Tensor/Cursor/Stream）必须在 try/finally 中释放，异常路径是内存泄漏的主要来源；特别要注意"别名"引用——recycle 前必须确认没有其他变量指向同一对象。

### Details
全面扫描后发现 8 个 Critical 内存泄漏，根源都是以下 3 个反模式：

1. **无 try/finally 的 native 资源**：`ImageUtils.match()` 创建 4 个 Mat 但只在正常路径 release，异常路径全泄漏。对比 `matchAll()` 已有正确的 try/finally。**规则：任何创建 Mat/Bitmap/ONNX Tensor 的代码块都必须用 try/finally 包裹。**

2. **Bitmap 别名导致 double-recycle 风险**：
   - `findText()` 快速路径 `finalBitmap = croppedBitmap`（别名），若两者都 recycle 会 crash
   - `match()` 中 `clampedTemplateMat = templateMat`（别名），若两者都 release 会 crash
   - `preprocessRec()` 中 `resized = bitmap`（当不需要缩放时），正确处理了别名（`if (resized !== bitmap) resized.recycle()`）
   - **规则：recycle/release 前必须用 `!==` 检查别名，或用 needRecycle 标志位。**

3. **缓存引用 vs 新建引用的混淆**：
   - `findImage()` 返回的 `sourceBitmap` 实际是 `MediaProjectionService.lastBitmap` 缓存引用，**不能 recycle**
   - `templateBitmap` 从 assets 新建，**必须 recycle**
   - `takeScreenshotNow()` 旧 `lastBitmap` 被覆盖时不 recycle，但 recycle 有风险（调用者可能持有旧引用）
   - **规则：明确每个资源对象的所有权——谁创建谁释放，缓存引用不释放，新建引用必须释放。**

### Suggested Action
1. 所有 Mat/Bitmap/ONNX Tensor 操作用 try/finally 包裹
2. recycle/release 前用 `!==` 检查别名
3. 文档标注返回值是"缓存引用"还是"新建副本"
4. 热路径避免循环内分配大对象（FloatArray/FloatBuffer），改用成员变量复用

### Metadata
- Source: conversation
- Related Files: app/src/main/java/com/steve1316/automation_library/utils/ImageUtils.kt, app/src/main/java/com/steve1316/automation_library/utils/ocr/OnnxPpocrEngine.kt, app/src/main/java/com/steve1316/automation_library/utils/MediaProjectionService.kt, app/src/main/java/com/steve1316/automation_library/utils/MyAccessibilityService.kt
- Tags: memory-leak, native-resource, mat, bitmap, onnx, try-finally, aliasing, ownership
- See Also: LRN-20260724-011

---

## [LRN-20260724-014] knowledge_gap

**Logged**: 2026-07-24T20:50:00+08:00
**Priority**: high
**Status**: pending
**Area**: infra

### Summary
项目当前 `libopencv_java4.so`（8.1MB, arm64-v8a）参考了 nihui/opencv-mobile 的精简思路,但**不能直接套用 nihui 官方的 `opencv4_cmake_options.txt`**,因为本项目使用 OpenCV Java API,必须保留 Java 绑定 + imgcodecs + 内置 jpeg/png/zlib。

### Details
对比 nihui 官方 `opencv4_cmake_options.txt` 与本项目 `/tmp/ocv-min/build-arm64/CMakeVars.txt` 的关键差异:

| 配置项 | nihui 官方 | 本项目 | 原因 |
|---|---|---|---|
| `BUILD_opencv_java` | OFF | **ON** | 项目用 `org.opencv.android.Utils` 等 Java API,必须构建 Java 绑定 |
| `BUILD_JAVA` | OFF | (未显式设,默认 ON) | 同上 |
| `BUILD_FAT_JAVA_LIB` | OFF | **ON** | 把所有静态库链接进单个 `libopencv_java4.so`,方便 jniLibs 引用 |
| `BUILD_opencv_imgcodecs` | OFF | **ON** | 项目用 `Imgcodecs.imread/imwrite` 做 PNG/JPG 读写 |
| `BUILD_JPEG` | OFF | **ON** | 内置 libjpeg-turbo(无系统 JPEG 可用) |
| `BUILD_PNG` | OFF | **ON** | 内置 libpng |
| `BUILD_ZLIB` | OFF | **ON** | 内置 zlib |
| `WITH_CPUFEATURES` | OFF | ON | 启用 cpufeatures |
| `WITH_OPENMP` | ON | OFF | nihui 启用 OpenMP,本项目默认关闭 |
| `WITH_ANDROID_NATIVE_CAMERA` | (未设) | ON | 项目原依赖保留 |

nihui 主推"minimal native build"——只构建 native 静态库,不构建 Java 绑定,不构建 imgcodecs。这对纯 C++ 项目合适,但对 Android Java/Kotlin 项目不直接适用。

### Suggested Action
1. 项目配置正确,无需对齐 nihui 全部选项;8.1MB 主要是 Java 绑定 + imgcodecs + jpeg/png/zlib 编解码库贡献的,属于必要开销
2. 若未来不需要 `imwrite` 写图功能,可考虑关闭 `BUILD_opencv_imgcodecs` 进一步缩减体积
3. 若仅需 `imdecode` (从内存解码),仍需 imgcodecs 模块但可关闭 JPEG/PNG 编码路径(需 patch)
4. 构建脚本 `/tmp/ocv-min/build_minimal.sh` 是 0 字节空文件,且 `/tmp` 重启即清空——应将完整 cmake configure 命令持久化到 `scripts/build_opencv_minimal.sh`

### Metadata
- Source: research
- Related Files: /tmp/ocv-min/build-arm64/CMakeVars.txt, /tmp/ocv-min/build-arm64/CMakeCache.txt, app/build.gradle.kts, app/src/main/java/com/steve1316/automation_library/utils/ImageUtils.kt
- Tags: opencv, opencv-mobile, build-config, cmake, native-build, aar-size
- See Also: LRN-20260724-013

---

## [LRN-20260724-015] best_practice

**Logged**: 2026-07-24T22:00:00+08:00
**Priority**: medium
**Status**: resolved
**Area**: infra

### Summary
启用 CAROTENE (ARM NEON SIMD) + 扩展多 ABI 支持 (arm64-v8a / armeabi-v7a / x86_64)

### Details
**变更背景**:
1. 评估 OpenMP 在手机上效果有限(Android 大小核架构下 fork/join 开销可能超过并行收益),且 OCR 主负载由 ONNX Runtime 独立处理。
2. CAROTENE 是 OpenCV 内置 ARM NEON SIMD 优化层,对 `matchTemplate`/`cvtColor`/`threshold` 等 SIMD 友好路径有 20-40% 加速,是更确定的优化方向。

**ABI 特定配置**:
- `arm64-v8a`: `-DWITH_CAROTENE=ON` (NEON 原生支持)
- `armeabi-v7a`: `-DWITH_CAROTENE=ON -DENABLE_NEON=ON -DENABLE_VFPV3=ON` (v7a 需显式启用 NEON,minSdk 24+ 设备 100% 支持)
- `x86_64`: `-DWITH_CAROTENE=OFF` (x86 不支持 CAROTENE, OpenCV 自动忽略)

**构建产物** (libopencv_java4.so):
| ABI | 大小 | JNI 符号 | 备注 |
|---|---|---|---|
| arm64-v8a | 8.5MB (原 8.1MB, +400KB) | 834 | CAROTENE ON |
| armeabi-v7a | 5.7MB | 834 | CAROTENE ON, NEON |
| x86_64 | 12MB | 834 | CAROTENE OFF, 仅模拟器 |

**AAR 包大小**: 14MB (app-debug.aar,三 ABI 全部打包)

**关键发现**:
1. CAROTENE 启用后 arm64-v8a 增加 ~400KB,符合预期
2. armeabi-v7a 体积反而小于 arm64-v8a (5.7MB vs 8.5MB),原因是 32 位 ARM 指令更紧凑 + CAROTENE 在 v7a 上 fallback 路径较少
3. x86_64 体积最大 (12MB),因为无 CAROTENE 优化 + x86 指令冗长 + 仍保留 Java 绑定全套
4. macOS 上系统 `nm` 无法读取 32 位 ARM ELF,需用 NDK 自带的 `llvm-nm` (`$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-nm`)
5. CAROTENE 是**纯优化层**,API 行为一致,无功能影响;仅可能产生 1e-7 级浮点累加误差,对 matchTemplate confidence 阈值 (0.8-0.9) 完全可忽略

### Suggested Action
1. 若需进一步缩减 AAR 体积,可在 `build.gradle.kts` 中使用 ABI splits 为不同架构生成独立 AAR
2. 若 release 包不需要模拟器支持,可考虑从 release 构建中移除 x86_64
3. 性能验证建议:启用 CAROTENE 后,实测 `ImageUtils.findImage()` 在大图场景下的耗时对比

### Metadata
- Source: research
- Related Files: scripts/build_opencv_minimal.sh, app/build.gradle.kts, app/src/main/jniLibs/
- Tags: opencv, carotene, neon, simd, multi-abi, performance
- See Also: LRN-20260724-014

---

