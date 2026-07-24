# Feature Requests

Capabilities requested by the user.

---

## [FEAT-20260724-001] permission_guide_window

**Logged**: 2026-07-24T16:30:00+08:00
**Priority**: high
**Status**: pending
**Area**: frontend

### Requested Capability
按照"悬浮窗 → 无障碍 → 投影"的顺序集成授权引导窗口,方便用户一站式完成三类特殊权限授权。

### User Context
当前库的 AndroidManifest 几乎空,无任何权限引导组件。消费方必须自行:
1. 声明所有 service/permission
2. 实现 canDrawOverlays 检查 + ACTION_MANAGE_OVERLAY_PERMISSION 跳转
3. 实现 ENABLED_ACCESSIBILITY_SERVICES 检查 + ACTION_ACCESSIBILITY_SETTINGS 跳转
4. 实现 createScreenCaptureIntent + startActivityForResult + 把 Intent 传给 ForegroundService

集成门槛极高,且 `MyAccessibilityService.checkStatus()` 不可靠,`FloatingOverlayButton` 不检查 canDrawOverlays 直接 addView,体验割裂。

### Complexity Estimate
complex

### Suggested Implementation
- 新建 `com.steve1316.automation_library.permission` 包,含 PermissionGuide/PermissionGuideActivity/PermissionChecker/PermissionStep/PermissionState/PermissionStatus/PermissionGuideCallback
- UI 采用单页多卡片模式(参考 AutoJs6/AutoX),顶部进度条 + 三张卡片(悬浮窗/无障碍/投影)+ 底部"开始使用"按钮
- 用纯 Android View + 系统 Theme(`@android:style/Theme.Material.Light.NoActionBar`),避免引入 appcompat/material/compose 增大 AAR 体积
- 库 manifest 中声明 PermissionGuideActivity,通过 manifest-merger 自动合并到宿主
- 对外暴露 `PermissionGuide.start(activity, requestCode)` 一行 API
- 三步无实际依赖,允许任意顺序,但默认聚焦第一项
- 同步修复:AndroidManifest 补全权限声明、accessibility_service_config.xml 移除占位、MyAccessibilityService.checkStatus 改用 Settings.Secure、MediaProjectionService.startForeground 加 type 参数

### Metadata
- Frequency: first_time
- Related Features: FloatingOverlayButton, MyAccessibilityService, MediaProjectionService
- Related Learnings: LRN-20260724-001, LRN-20260724-002, LRN-20260724-005, LRN-20260724-007

---

## [FEAT-20260724-002] ocr_engine_migration_to_ppocrv6

**Logged**: 2026-07-24T17:30:00+08:00
**Priority**: high
**Status**: pending
**Area**: backend

### Requested Capability
将 OCR 引擎从 Tesseract + Google ML Kit 迁移到 onnxruntime 或 MNN 运行 PP-OCRv6 模型。

### User Context
当前 `ImageUtils.kt` 的 `findText()` 采用 "ML Kit 优先 → Tesseract 兜底" 双引擎方案:
- ML Kit:拉丁语系,需 Google Play 服务,体积大,离线模型约 10MB+
- Tesseract:需手动下 .traineddata,精度一般,线程不安全(已加 tesseractLock 串行化)

痛点:精度不足、依赖重、多语言支持差、Tesseract API 非线程安全。

PP-OCRv6(2026-06 发布)优势:
- Tiny(1.5M)/Small(20.1MB)/Medium(73M)三档,Small 适合手机 App
- 单模型支持中/英/日 + 46 拉丁语系共 50 种语言
- 官方原生提供 ONNX 格式模型,PaddleOCR 3.7+ 内置 onnxruntime 后端
- 检测 Hmean 86.2%,识别准确率 83.2%,幻觉率仅 6.8%

### Complexity Estimate
complex

### Suggested Implementation
**引擎选型对比**:
- onnxruntime-android:PP-OCRv6 官方原生支持 ONNX,模型直接用,无需转换;so 库约 3-5MB;API 成熟;维护成本低
- MNN:so 库极小(800KB),ARM 优化更深,但需 MNNConvert 转 .mnn + 自己实现前后处理;社区 PP-OCR 集成案例少

**推荐方案(onnxruntime-android)**:
1. 引擎层:新建 `OcrEngine` 接口 + `PpocrOnnxEngine` 实现,封装 det+rec+方向分类
2. 模型文件:assets 下放 PP-OCRv6_small_det.onnx / rec.onnx / textline_ori.onnx
3. API 层:保持 `findText()` 签名不变,内部替换为 PpocrOnnxEngine;`detectDigitsOnly` 改为后处理正则过滤
4. 依赖:移除 tesseract4android + mlkitTextRecognition,新增 onnxruntime-android
5. 体积预估:移除旧依赖省 15MB+,新增 onnxruntime(3-5MB)+ PP-OCRv6 small(20MB)≈ 净增 8-10MB,但精度和多语言能力大幅提升
6. 兼容:保留 grayscale/thresh/scale 预处理参数,PP-OCRv6 自带预处理可忽略部分,但保留接口

### Metadata
- Frequency: first_time
- Related Features: ImageUtils.findText, ImageUtils.initTesseract
- Source: conversation
- Tags: ocr, onnxruntime, mnn, ppocrv6, migration
- See Also: LRN-20260724-009, LRN-20260724-010

### Resolution
- **Resolved**: 2026-07-24T17:35:00+08:00
- **Notes**: 采用 onnxruntime-android + PP-OCRv6 Tiny rec 方案完成迁移。新增 `utils/ocr/OcrEngine.kt` + `OnnxPpocrEngine.kt`;`ImageUtils.findText()` 改用新引擎,`detectDigitsOnly` 改为正则后处理;移除 tesseract4android + mlkitTextRecognition 依赖与 AndroidManifest MlKitInitProvider 节点;assets/ppocr/ 放入 PP-OCRv6_tiny_rec.onnx (4.3MB) + ppocr_keys_v1.txt (26KB);consumer-rules.pro 添加 onnxruntime keep 规则;编译 BUILD SUCCESSFUL,AAR 3.8MB → 7.7MB(增量主要来自 4.3MB 模型,onnxruntime so 通过 maven 传递给消费方)。

---

## [FEAT-20260724-003] ocr_batch_and_detection

**Logged**: 2026-07-24T18:00:00+08:00
**Priority**: high
**Status**: pending
**Area**: backend

### Requested Capability
在现有单区域 `findText()` 基础上新增两项能力:
1. **多区域 batch OCR**:一次性传入多个 cropRegion,共享一次 session.run,减少 native 调用与预处理开销。
2. **文本检测(det)**:集成 PP-OCRv6 Tiny det 模型,在整图上检测文字位置,提供"通过文字内容反查坐标"的新 API。

### User Context
- 当前 `ImageUtils.findText(cropRegion)` 一次只处理一个区域;游戏自动化场景常需同时读取多个 UI 数值(血量/蓝量/金币/经验等),多次串行调用导致整体延迟 = N × 单次延迟。
- 当前只能识别裁剪好的图块内的文字,无法"在整张截图里找包含某文字的位置",这限制了自动化脚本通过文字定位 UI 元素的能力(类似 ImageUtils.findImage 但针对文字)。

### Complexity Estimate
complex

### Current State
- `ImageUtils.findText(cropRegion, ...)`:单区域,签名固定,内部调用 `ocrEngine.recognize(bitmap)`。
- `OcrEngine` 接口只有 `recognize(bitmap): String` 和 `close()`,无 batch / det 方法。
- `OnnxPpocrEngine`:仅加载 rec 模型(`PP-OCRv6_tiny_rec.onnx`),session 单例。
- `assets/ppocr/`:仅 rec 模型 + 字典,无 det 模型。

### Suggested Implementation

#### 阶段 1:扩展 OcrEngine 接口(底层)

**OcrEngine.kt** 新增方法:

```kotlin
interface OcrEngine {
    // 现有
    fun recognize(bitmap: Bitmap): String
    fun close()

    // 新增:batch 识别(多图共享一次 session.run)
    fun recognizeBatch(bitmaps: List<Bitmap>): List<String>

    // 新增:文本检测,返回每行文本的旋转矩形框 + 该行的识别结果(端到端)
    // 若 textOnly=true,只返回识别文本列表(不返回框);否则返回 OcrResult 列表
    fun detectAndRecognize(bitmap: Bitmap, textOnly: Boolean = false): List<OcrResult>
}

data class OcrResult(
    val text: String,
    val confidence: Float,
    // 文本行的四点边框(原图坐标),顺时针:左上→右上→右下→左下
    val box: List<PointF>,
    // 中心点坐标(便于点击)
    val center: PointF,
)
```

#### 阶段 2:OnnxPpocrEngine 实现 batch

**OnnxPpocrEngine.kt** 改造:

1. **recognizeBatch(bitmaps)**:
   - 预处理所有 bitmaps → 统一 resize 到 H=48,宽度按各自比例,但需 pad 到 batch 内最大宽度(ONNX batch 要求同形状)
   - 拼成 `[N, 3, 48, maxW]` 单 tensor,一次 `session.run`
   - 输出 `[N, T, C]`,逐样本 CTC 解码
   - 优势:N=5 时整体延迟约为单次 1.5x(而非 5x)

2. **关键技术点**:
   - ONNX Runtime 原生支持 batch dimension,无需改模型
   - 不同宽度需右侧 pad 0(blank 像素),rec 模型对 padding 鲁棒
   - 输入名 `x`,输出名 `softmax_0.tmp_0` 或类似(需用 `Netron` 或 session.outputNames 确认)

#### 阶段 3:下载并集成 det 模型

**模型**:`PaddlePaddle/PP-OCRv6_tiny_det_onnx`(ModelScope,1.78MB)

```
assets/ppocr/
├── PP-OCRv6_tiny_rec.onnx   # 已有,4.3MB
├── PP-OCRv6_tiny_det.onnx   # 新增,1.78MB
└── ppocr_keys_v1.txt        # 已有
```

**det 前处理**(PP-OCRv6 标准):
- Resize:长边比例缩放到 `limit_max_len=960`(移动端可降到 640 省内存),保持比例
- Normalize:mean=[0.485, 0.456, 0.406] std=[0.229, 0.224, 0.225](ImageNet 标准)
- Pad 到 32 的倍数

**det 后处理**(DB 算法):
- 输出 `[1, 1, H, W]` 概率图
- threshold=0.3 → 二值化
- 找连通域外接矩形 → 最小外接矩形(可选,简化版用正矩形)
- 过滤面积 < 10 或短边 < 4 的框
- 按行排序(top→bottom, left→right)

**det session 配置**:
- 与 rec session 共享 `OrtEnvironment`
- 独立 `OrtSession`,独立 `SessionOptions`(可设不同线程数)

#### 阶段 4:detectAndRecognize 端到端实现

**流程**:
```
detectAndRecognize(fullBitmap):
  1. det 前处理 → det session.run → 概率图
  2. DB 后处理 → List<textLineBox>(原图坐标)
  3. 对每个 box:从 fullBitmap 裁剪文本行 → 透视校正(可选,简化版用正矩形裁剪)
  4. 把所有裁剪图送 recognizeBatch(一次 session.run)
  5. 组装 List<OcrResult>(text + box + center)
```

#### 阶段 5:ImageUtils 对外 API

**ImageUtils.kt** 新增:

```kotlin
/**
 * 一次性识别多个裁剪区域的文字(batch 共享一次推理)。
 * 适用于同时读取多个 UI 数值(血量/蓝量/金币等)。
 *
 * @param cropRegions 多个裁剪区域,每个为 [x, y, w, h]
 * @param sourceBitmap 共享的源截图;null 时自动 getSourceBitmap()
 * @return 每个区域对应的识别文本,顺序与输入一致
 */
open fun findTextBatch(
    cropRegions: List<IntArray>,
    sourceBitmap: Bitmap? = null,
): List<String>

/**
 * 在整图中检测所有文字,返回每个文本行的内容与位置。
 * 适用于"通过文字内容反查 UI 元素坐标"。
 *
 * @param sourceBitmap 源截图;null 时自动 getSourceBitmap()
 * @param textFilter 可选,只返回包含该子串的结果(大小写不敏感)
 * @return 匹配的 OcrResult 列表(按 top→bottom, left→right 排序)
 */
open fun findTextLocations(
    sourceBitmap: Bitmap? = null,
    textFilter: String? = null,
): List<OcrResult>

/**
 * 在整图中查找第一个包含指定文字的位置,返回中心点(便于点击)。
 * findText 的"按内容定位"版本,类似 findImage 但针对文字。
 *
 * @return 中心点 PointF;找不到返回 null
 */
open fun findTextLocation(
    text: String,
    sourceBitmap: Bitmap? = null,
): PointF?
```

#### 阶段 6:预热 + ProGuard + 编译

- `BotService.onCreate` 预热逻辑不变(OcrEngine.get 已会触发 det+rec 加载)
- `consumer-rules.pro` 无需新增(已 keep ai.onnxruntime.**)
- assets 增加 1.78MB,AAR 预计 7.7MB → 9.5MB(仍超 4MB 约束,但符合用户明确要求;体积优化走动态下载方案,见 FEAT-20260724-002 的 E 选项)
- 编译验证 + 单元测试(可用assets里放一张测试图)

### Implementation Order

1. **下载 det 模型**(1.78MB)到 assets/ppocr/
2. **扩展 OcrEngine 接口**:新增 `recognizeBatch` / `detectAndRecognize` / `OcrResult`
3. **实现 recognizeBatch**(单 session.run 多样本,共享 rec 模型)
4. **实现 det 前处理 + 后处理**(DB 算法,正矩形版,不做透视校正)
5. **实现 detectAndRecognize 端到端**(det → crop → batch rec)
6. **ImageUtils 新增 findTextBatch / findTextLocations / findTextLocation**
7. **编译验证 + 对比性能**(单次 vs batch,单区域 vs det+rec 端到端)

### Risk & Notes

- **batch 形状不一致**:ONNX 要求 batch 内同形状,需 pad 到最大宽度;rec 模型对右侧 padding 鲁棒(CTC 会输出 blank)
- **det 内存峰值**:960×960 输入 × float32 ≈ 14MB,加上输出概率图同尺寸 ≈ 28MB,移动端可接受;若 OOM 降到 640
- **det 精度**:tiny det 比 small det 精度低,游戏 UI 文字通常规整,tiny 足够;若不够再换 small(7.7MB)
- **透视校正**:弯曲文本(如圆柱包装)需透视校正;游戏 UI 通常是正矩形,简化版不实现,后续按需加
- **线程安全**:det session 与 rec session 独立,可并发;但 batch 内必须串行预处理(共享 Bitmap 像素读取)
- **向后兼容**:`findText(cropRegion)` 保持不变,内部可改为调用 `recognizeBatch(listOf(crop)).first()`,但为了不破坏现有性能特征,保留独立路径

### Metadata
- Frequency: first_time
- Related Features: ImageUtils.findText, OcrEngine, OnnxPpocrEngine
- Source: conversation
- Tags: ocr, batch, detection, ppocrv6, performance, api-design
- See Also: FEAT-20260724-002, LRN-20260724-010

---

