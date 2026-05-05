# P2: 高级功能实施计划

## TL;DR

> **目标**: 实现5个高级功能 — LaMa ONNX修复器、DeepL翻译器、大图降采样、模型下载进度UI、saveTranslation持久化。全部可并行实施。
>
> **关键成果**:
> - LaMa ONNX模型修复器（替代白色填充）
> - DeepL翻译器（第二个云端翻译选项）
> - 大图自动降采样（防止OOM）
> - 模型下载进度对话UI
> - 翻译结果保存到Room数据库
>
> **预估总LOC**: ~1800-2200 行（5个模块）
> **并行度**: 全部5个任务互不依赖，可同时实施
> **难度**: 中等（LaMa部分较高）

---

## Context

### 当前状态
- ✅ P0/P1全部修复：管线触发、设置导航、模型URL、配置读DataStore、进度UI+取消、竖排渲染、GPT重试、验证回退、错误UI
- ✅ SimpleFillInpainter可用（白色填充），Config却默认LAMA_LARGE
- ✅ Translator接口和GPT兼容翻译器可用
- ✅ ModelDownloadManager有downloadStatus StateFlow但无UI
- ✅ 管线无降采样，大图直接处理
- ✅ TranslationHistoryEntity/DAO存在，但saveTranslation()是mock

### 并行可行性
全部5个P2模块**互不依赖**，可在5个独立轨道并行推进：
```
轨道A: P2-1 LaMa ONNX修复器
轨道B: P2-2 DeepL翻译器
轨道C: P2-3 大图降采样
轨道D: P2-4 模型下载UI
轨道E: P2-5 saveTranslation持久化
```

---

## Work Objectives

### Core Objective
补齐5个标记为P2的高级功能，使App的修复能力超越白色填充、翻译器有第二个云端选项、大图处理安全、模型下载用户可见、翻译结果可追溯。

### Definition of Done
- [x] AOT-GAN ONNX修复器（替代白色填充，因LaMa FFT不可导ONNX）
- [x] DeepL翻译器可通过设置界面选择并使用
- [x] >4096px大图自动降采样到detectionSize
- [x] 模型下载有进度条、取消按钮、错误重试UI
- [x] 翻译完成后自动保存到历史记录

### Must NOT Do
- ❌ 不做Stable Diffusion修复器（太重）
- ❌ 不做百度/有道翻译器（优先级低）
- ❌ 不改变现有管线架构
- ❌ 不做完整的文件管理UI
- ❌ 不做批量下载/批量翻译优化

---

## 🔴 重要发现: LaMa无法导出为ONNX

**探索结果**: Python项目的LaMa模型使用FFT(傅里叶变换)卷积块(`torch.fft.rfftn/irfftn`)，这些操作**不在ONNX opset中**，无法导出。LaMa (`lama_large`/`lama_mpe`) 75%的通道走FFT路径，完全无法ONNX化。

**替代方案**: 使用 **AOT-GAN** (`AOTGenerator` in `inpainting_aot.py`) — Python项目的**默认修复器**，只用纯卷积层→ **完全ONNX兼容**。质量好（与原版LaMa接近），模型更小（约50MB vs LaMa的200MB）。

| 功能 | AOT-GAN | OpenCV Telea | SimpleFill |
|------|---------|-------------|------------|
| **需要模型** | ✅ ONNX (50MB) | ❌ 无 | ❌ 无 |
| **修复质量** | ⭐⭐⭐⭐ 优秀 | ⭐⭐ 模糊 | ⭐ 白色填充 |
| **是否实现** | 本次实现 | 不实现 | 已实现 |

---

## P2-1: AOT-GAN ONNX Inpainter ⭐⭐⭐⭐⭐

### 目标
创建 `AotInpainter`，使用ONNX Runtime运行AOT-GAN模型，替代当前的SimpleFillInpainter（白色填充）。

### 模型来源
- **Python文件**: `manga_translator/inpainting/inpainting_aot.py` — `AOTGenerator(4, 3)`
- **下载URL**: `https://github.com/zyddnys/manga-image-translator/releases/download/beta-0.3/inpainting.ckpt`
- **SHA256**: `878d541c68648969bc1b042a6e997f3a58e49b6c07c5636ad55130736977149f`
- **架构**: GatedConv + Weight Standardization，纯卷积层，无FFT → **ONNX兼容**
- **模型大小**: ~50MB

### 导出ONNX（在Python侧一次性操作）
使用下面的脚本将 `inpainting.ckpt` 导出为 `aot_inpainting.onnx`：
```python
import torch
from manga_translator.inpainting.inpainting_aot import AOTGenerator

# 加载模型
checkpoint = torch.load("inpainting.ckpt", map_location="cpu")
model = AOTGenerator(4, 3)  # 4通道输入(3RGB + 1Mask), 3通道输出
model.load_state_dict(checkpoint)
model.eval()

# 导出为ONNX
dummy_input = torch.randn(1, 4, 512, 512)
torch.onnx.export(
    model, dummy_input, "aot_inpainting.onnx",
    input_names=["input"], output_names=["output"],
    opset_version=18, dynamic_axes={"input": {2:"H", 3:"W"}, "output": {2:"H", 3:"W"}},
)
```

### 架构设计

```kotlin
class AotInpainter(
    private val sessionManager: OnnxSessionManager,
    private val modelDownloadManager: ModelDownloadManager,
    private val context: Context,
) : Inpainter {
    // 实现 Inpainter 接口
    // ONNX推理 + 预处理/后处理
}
```

### 预处理
1. Bitmap + Mask → 4通道 Tensor (memory layout: NCHW)
2. Normalize: `img / 127.5 - 1.0` → 范围[-1, 1] (AOT-GAN不继承LamaFourier, 走非FFT分支)
3. Mask threshold: `mask > 127 → 1.0, else 0.0`
4. 输入组合: `torch.cat([mask, img], dim=1)` — 注意AOT的concat顺序是[mask, img]（Python源码: `x = torch.cat([mask, img], dim=1)`）

### 后处理
1. 输出clip到[-1, 1]
2. Denormalize: `(output + 1.0) * 127.5` → uint8范围[0, 255]
3. Blend: `result = inpainted * mask_original + original * (1 - mask_original)` — 只替换mask区域

### 关键实现

**Android端核心代码**:
```kotlin
override suspend fun inpaint(bitmap: Bitmap, mask: Bitmap, config: InpainterConfig): Bitmap {
    // 1. 检查模型是否已下载
    val modelFile = modelDownloadManager.ensureModel(ModelRegistry.AOT_INPAINTING_MODEL)
    
    // 2. 预处理: Bitmap → Tensor
    val inputTensor = preprocess(bitmap, mask)  // [1, 4, H, W]
    
    // 3. ONNX推理
    val session = sessionManager.createSession("aot", modelFile)
    val output = session.run(mapOf("input" to inputTensor))  // [1, 3, H, W]
    
    // 4. 后处理: Tensor → Bitmap
    return postprocess(output, bitmap, mask)
}
```

**预处理(preprocess)**: 
```kotlin
private fun preprocess(bitmap: Bitmap, mask: Bitmap): OnnxTensor {
    // 如果图片太大，缩放到 inpainting_size (默认2048)，Python也是这么做的
    val workingImg = if (maxOf(bitmap.width, bitmap.height) > config.inpaintingSize) {
        ImageUtils.downsampleToMaxSize(bitmap, config.inpaintingSize)
    } else bitmap
    val workingMask = // resize mask to match workingImg
    
    // 转换为float数组 [R,G,B, mask]
    val pixels = IntArray(workingImg.width * workingImg.height)
    workingImg.getPixels(pixels, 0, workingImg.width, 0, 0, workingImg.width, workingImg.height)
    val maskPixels = IntArray(workingMask.width * workingMask.height)
    workingMask.getPixels(maskPixels, 0, workingMask.width, 0, 0, workingMask.width, workingMask.height)
    
    // 构建4通道输入: [mask, R, G, B] — AOT用 cat([mask, img], dim=1)
    val tensorData = FloatArray(4 * workingImg.height * workingImg.width)
    for (i in pixels.indices) {
        val r = ((pixels[i] shr 16) and 0xFF) / 127.5f - 1.0f
        val g = ((pixels[i] shr 8) and 0xFF) / 127.5f - 1.0f
        val b = (pixels[i] and 0xFF) / 127.5f - 1.0f
        val m = if ((maskPixels[i] and 0xFF) > 127) 1.0f else 0.0f
        // NCHW layout: channel 0=mask, 1=R, 2=G, 3=B
        tensorData[0 * workingImg.height * workingImg.width + i / workingImg.width * workingImg.width + i % workingImg.width] = m  // mask 
        tensorData[1 * workingImg.height * workingImg.width + i / workingImg.width * workingImg.width + i % workingImg.width] = r  // R
        tensorData[2 * workingImg.height * workingImg.width + i / workingImg.width * workingImg.width + i % workingImg.width] = g  // G
        tensorData[3 * workingImg.height * workingImg.width + i / workingImg.width * workingImg.width + i % workingImg.width] = b  // B
    }
    return OnnxTensor.createTensor(OnnxRuntime.get(), LongArray(4).apply {
        set(0, 1); set(1, 4); set(2, workingImg.height.toLong()); set(3, workingImg.width.toLong())
    }, tensorData, OnnxJavaType.FLOAT)
}
```

**后处理(postprocess)**:
```kotlin
private fun postprocess(output: Any, original: Bitmap, mask: Bitmap): Bitmap {
    // 从ONNX输出提取 [1,3,H,W] → denormalize [(out+1)*127.5] → 合成RGB
    val result = original.copy(Bitmap.Config.ARGB_8888, true) ?: original
    // 遍历每个像素: if mask>127 → 使用修复值, else → 保留原值
    return result
}
```

### Tiling策略
- AOT-GAN处理整张图（如>2048px则缩放），**不进行分块**（Python项目也不分块）
- 这比分块简单得多，且质量更好（不分块无拼接缝）

### DI集成
```kotlin
factory<Inpainter> {
    val config: TranslationConfig = get()
    when (config.inpainter.inpainter) {
        InpainterType.AOT -> AotInpainter(get(), get(), androidContext())
        InpainterType.LAMA_LARGE, InpainterType.LAMA_MPE -> AotInpainter(get(), get(), androidContext()) // LaMa不可用, AOT替代
        InpainterType.SIMPLE_FILL -> SimpleFillInpainter()
        else -> SimpleFillInpainter()
    }
}
```

### 预估文件
| 文件 | 用途 | 预估行数 |
|------|------|---------|
| `translation/inpaint/AotInpainter.kt` | AOT-GAN ONNX修复器 | 350-450 |
| `translation/data/config/InpainterType.kt` (修改) | 将默认从LAMA_LARGE改为AOT | +1 |
| `ModelRegistry.kt` (修改) | 添加AOT_INPAINTING_MODEL | +5 |
| `TranslationModule.kt` (修改) | 条件注入 | 改5行 |

### QA
- [/] AOT修复影像与白色填充的质量对比 — 需真机测试验证
- [/] 验证大图（>4000px）正确缩放后处理 — 需真机测试验证
- [/] 验证mask边缘无锯齿/硬边 — 需真机测试验证
- [/] 验证内存无泄漏（连续5次修复） — 需真机测试验证

---

## P2-2: DeepL Translator ⭐⭐⭐

### 目标
创建 `DeeplTranslator`，通过DeepL REST API实现翻译，成为GPT兼容翻译器之后的第二个云端翻译选项。

### 背景
- Python项目 `translators/deepl.py`（52行）：使用DeepL官方API
- DeepL提供免费层（每月50万字符）
- API格式简单：POST到 `https://api-free.deepl.com/v2/translate` 或 `https://api.deepl.com/v2/translate`
- 支持表单和JSON两种格式

### API参考

**请求**:
```
POST https://api-free.deepl.com/v2/translate
Content-Type: application/json
Authorization: DeepL-Auth-Key {api_key}

{
  "text": ["line1", "line2", "line3"],
  "target_lang": "ZH",
  "source_lang": "JA"
}
```

**响应**:
```json
{
  "translations": [
    {"detected_source_language": "JA", "text": "翻译1"},
    {"detected_source_language": "JA", "text": "翻译2"},
    {"detected_source_language": "JA", "text": "翻译3"}
  ]
}
```

### 语言代码映射
DeepL使用 ISO 639-1 代码（小写），需要从内部代码映射：
```kotlin
private val LANGUAGE_CODE_MAP = mapOf(
    "CHS" to "ZH",    // Simplified Chinese
    "CHT" to "ZH",    // Traditional Chinese (DeepL不支持CHT，用ZH)
    "ENG" to "EN-US", // English
    "JPN" to "JA",    // Japanese
    "KOR" to "KO",    // Korean
    "FRA" to "FR",    // French
    "DEU" to "DE",    // German
    "ESP" to "ES",    // Spanish
    "ITA" to "IT",    // Italian
    "NLD" to "NL",    // Dutch
    "PLK" to "PL",    // Polish
    "PTB" to "PT-BR", // Portuguese (Brazilian)
    "RUS" to "RU",    // Russian
)
```

### 接口实现
```kotlin
class DeeplTranslator(
    private val httpClient: HttpClient,
) : Translator {
    override val name = "DeepL"
    
    override suspend fun translate(
        texts: List<String>,
        fromLanguage: String,
        toLanguage: String,
        config: TranslatorConfig,
    ): List<String>
    
    override fun supportsLanguagePair(from: String, to: String): Boolean
}
```

### 关键实现细节
1. **API Key**: 从 `config.apiKey` 读取（TranslatorConfig已有此字段）
2. **端点**: `config.apiBase` 或默认 `https://api-free.deepl.com/v2`
3. **字符限制**: 单次请求最多50段文字
4. **重试**: 同上，使用与GPT相同的 `retryWithBackoff` 模式
5. **空文本处理**: 空文本跳过，用占位符保持序号对齐

### DI集成
```kotlin
single<Translator> {
    val config: TranslationConfig = get()
    when (config.translator.translator) {
        TranslatorType.GPT_COMPATIBLE -> GptTranslator(get())
        TranslatorType.DEEPL -> DeeplTranslator(get())
        TranslatorType.NONE -> NoOpTranslator()
        TranslatorType.ORIGINAL -> OriginalTranslator()
        else -> NoOpTranslator()
    }
}
```

### 设置界面
在 `SettingsTranslationScreen` 中添加DeepL选项（需要翻译类型选择器支持DEEPL）。检查是否已有翻译器选择对话框。
- 如果已有选择器，确保 `TranslatorType.DEEPL` 在其中
- 如果DeepL被选择，显示API Key输入框

### 预估文件
| 文件 | 用途 | 预估行数 |
|------|------|---------|
| `translation/translator/DeeplTranslator.kt` | DeepL翻译器 | 200-250 |
| `TranslationModule.kt` (修改) | 添加DEEPL分支 | +3 |
| `SettingsTranslationScreen.kt` (可能修改) | 翻译器选择 | 视情况 |

---

## P2-3: 大图降采样 ⭐⭐

### 目标
在翻译管线开始时，将超大图片（如相机照片12MP+）降采样到 `detectionSize` 以避免OOM和处理过慢。

### 背景
- Python项目处理到 `detection_size`（默认2048）
- 但Python的降采样是在检测器内部（`detection/common.py` 的 `_add_border` 逻辑）
- 当前Java管线：`CtdTextDetector` 使用 `letterbox()` 将图缩放到1024×1024
- 但管线其他步骤（OCR裁剪、遮罩膨胀）用的是原始分辨率

### 架构设计

**在管线入口处添加降采样步骤**：

```kotlin
// 在 TranslationPipeline._translate() 中
// Step 0: 降采样
val processingBitmap = if (maxOf(inputBitmap.width, inputBitmap.height) > config.detector.detectionSize) {
    ImageUtils.downsampleToMaxSize(inputBitmap, config.detector.detectionSize)
} else {
    inputBitmap
}
ctx.originalBitmap = inputBitmap  // 保留原图用于最后输出
// ... 后续步骤用 processingBitmap
```

**`ImageUtils.downsampleToMaxSize()`**:
```kotlin
fun downsampleToMaxSize(bitmap: Bitmap, maxSize: Int): Bitmap {
    val width = bitmap.width
    val height = bitmap.height
    val maxDimension = maxOf(width, height)
    if (maxDimension <= maxSize) return bitmap

    val scale = maxSize.toFloat() / maxDimension
    val newWidth = (width * scale).toInt()
    val newHeight = (height * scale).toInt()
    return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
}
```

### 关键决策
1. **坐标映射回原图**：检测/OCR在降采样图上做，坐标需要映射回原图来渲染
   - 存储缩放比例 `scaleX, scaleY` 到 `TranslationContext`
   - 在渲染步骤前，将 `TextBlock` 坐标按比例放大
   - **或者：渲染也在降采样图上做，最终输出降采样图**（简单方案）
   
   **推荐简单方案**：整个管线在降采样图上运行，输出也是降采样尺寸。原图保留在 `ctx.originalBitmap` 供用户对比。

2. **Letterbox vs 直接resize**：
   - `letterbox()` 保持宽高比，用padding填充 → 适合检测模型输入
   - `createScaledBitmap` 会改变宽高比 → 适合管线处理
   - **推荐**：检测阶段用letterbox（已实现），其他阶段用等比resize（新实现）

### 实现位置
- `ImageUtils.kt`：添加 `downsampleToMaxSize()` 方法
- `TranslationPipeline.kt`：在 `translate()` 方法开头添加降采样逻辑
- `TranslationContext.kt`：添加 `originalBitmap: Bitmap?` 字段

### 预估文件
| 文件 | 用途 | 预估行数 |
|------|------|---------|
| `translation/util/ImageUtils.kt` (修改) | downsampleToMaxSize() | +30 |
| `translation/data/TranslationContext.kt` (修改) | originalBitmap字段 | +3 |
| `translation/pipeline/TranslationPipeline.kt` (修改) | 降采样步骤 | +15 |

---

## P2-4: 模型下载进度UI ⭐⭐⭐

### 目标
创建用户可见的模型下载进度界面，在翻译开始前触发模型下载，展示进度条、状态和取消按钮。

### 背景
- `ModelDownloadManager` 已有 `downloadStatus: StateFlow<DownloadStatus>`
- `DownloadStatus` 是sealed interface：`Idle | Downloading(progress) | Verifying | Ready | Error`
- 模型下载在 `detector.prepare()` 和 `recognizer.prepare()` 中自动触发
- 当前：下载发生在后台，用户不可见，无进度展示

### 架构设计

**方案：复用现有的进度UI模式**

`TranslationPipeline` 已经将进度暴露为 `StateFlow<TranslationProgress>`。可以扩展：

1. 在 `TranslationProgress` sealed class 中添加 `Downloading` 状态
2. 在 `TranslationPipeline.prepare()` 中监听 `ModelDownloadManager.downloadStatus`
3. 将下载进度映射到 `TranslationProgress`
4. `WorkspaceScreen` 已有进度条UI，自动展示

**或者：独立的下载对话框**

创建 `ModelDownloadDialog` composable：
```kotlin
@Composable
fun ModelDownloadDialog(
    status: DownloadStatus,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
)
```

### 推荐方案：扩展现有进度系统

在 `TranslationPipeline` 中添加下载状态映射：
```kotlin
// 在 prepare() 阶段
_progress.value = TranslationProgress.Downloading(0f, "Downloading models...")
modelDownloadManager.downloadStatus.collect { status ->
    when (status) {
        is DownloadStatus.Idle -> {}
        is DownloadStatus.Downloading -> 
            _progress.value = TranslationProgress.Downloading(status.progress, "Downloading... ${(status.progress * 100).toInt()}%")
        is DownloadStatus.Verifying -> 
            _progress.value = TranslationProgress.Downloading(0.95f, "Verifying...")
        is DownloadStatus.Ready -> 
            _progress.value = TranslationProgress.Loading("Models ready")
        is DownloadStatus.Error -> 
            _progress.value = TranslationProgress.Error("Download failed: ${status.message}")
    }
}
```

在 `TranslationProgress` 中添加：
```kotlin
data class Downloading(val progress: Float, val message: String) : TranslationProgress()
```

`WorkspaceScreen` 中对应的UI已经存在（`when(progress)` 块），只需添加 `is Downloading` 分支。

### 关键设计
- **取消支持**：`ModelDownloadManager` 需要添加 `cancelDownload()` 方法（当前可能缺失）
- **重试**：错误状态提供重试按钮
- **跳过检查**：如果模型已下载，跳过下载步骤

### 预估文件
| 文件 | 用途 | 预估行数 |
|------|------|---------|
| `translation/pipeline/TranslationProgress.kt` (修改) | 添加Downloading状态 | +5 |
| `translation/pipeline/TranslationPipeline.kt` (修改) | 下载进度映射 | +25 |
| `translation/model/ModelDownloadManager.kt` (修改) | cancelDownload() | +15 |
| `ui/screens/WorkspaceScreen.kt` (修改) | UI分支 | +10 |
| `ui/components/ModelDownloadIndicator.kt` (可选) | 可复用下载组件 | 60-80 |

---

## P2-5: saveTranslation持久化 ⭐⭐

### 目标
实现 `WorkspaceViewModel.saveTranslation()`，将翻译结果保存到Room数据库，使用户可以在历史记录中查看。

### 背景
- `TranslationHistoryEntity`：已有实体（imagePath, sourceLanguage, targetLanguage, translatedAt, status, coverImageUri）
- `TranslationHistoryDao`：已有DAO（insert, delete, getAll, getById）
- `HistoryViewModel`：已从Room读取并展示
- `WorkspaceViewModel.saveTranslation()`：当前是mock `// Mock save - in real app would save to database`

### 需要保存的数据
1. **翻译元信息**：源语言、目标语言、翻译时间、状态
2. **输入图像**：保存到app内部存储
3. **输出图像**：保存到app内部存储
4. **封面缩略图**：输出图的缩略版本

### 实现步骤

**Step 1: 扩展 `TranslationHistoryEntity`**

当前字段不够 — 缺少翻译文本数据。考虑添加：
```kotlin
@Entity(tableName = "translation_history")
data class TranslationHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val imagePath: String,        // 输入图路径
    val resultImagePath: String?, // 输出图路径
    val sourceLanguage: String,
    val targetLanguage: String,
    val translatedAt: Long,
    val status: String,
    val coverImageUri: String?,
    val textRegions: String?,     // JSON序列化的TextBlock列表
)
```

**注意**：添加新字段需要Room数据库迁移（version++）。

**或者**：简单方案 — 用现有字段，不保存textRegions。

**Step 2: 保存图像文件**

```kotlin
private fun saveBitmapToFile(bitmap: Bitmap, context: Context, prefix: String): String {
    val dir = File(context.filesDir, "translations")
    dir.mkdirs()
    val file = File(dir, "${prefix}_${System.currentTimeMillis()}.png")
    FileOutputStream(file).use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    }
    return file.absolutePath
}
```

**Step 3: 创建缩略图**

```kotlin
private fun createThumbnail(bitmap: Bitmap, maxSize: Int = 200): Bitmap {
    val scale = minOf(maxSize.toFloat() / bitmap.width, maxSize.toFloat() / bitmap.height)
    val w = (bitmap.width * scale).toInt()
    val h = (bitmap.height * scale).toInt()
    return Bitmap.createScaledBitmap(bitmap, w, h, true)
}
```

**Step 4: 插入数据库**

```kotlin
suspend fun saveTranslation() {
    val state = _uiState.value
    val result = state.translationResult ?: return  // 需要存储result到state

    val entity = TranslationHistoryEntity(
        imagePath = saveBitmapToFile(result.inputBitmap, context, "input"),
        resultImagePath = saveBitmapToFile(result.resultBitmap, context, "result"),
        sourceLanguage = state.selectedLanguage,
        targetLanguage = config.translator.targetLanguage,
        translatedAt = System.currentTimeMillis(),
        status = "COMPLETED",
        coverImageUri = saveBitmapToFile(createThumbnail(result.resultBitmap), context, "thumb"),
    )
    databaseProvider.dao.insert(entity)
}
```

### 当前WorkspaceUiState需要的修改
`WorkspaceUiState` 需要存储翻译结果，以便save时使用：
```kotlin
data class WorkspaceUiState(
    // ... existing fields ...
    val translationResult: TranslationResult.Success? = null, // 翻译成功结果
)
```

设置时机：
```kotlin
when (result) {
    is TranslationResult.Success -> {
        _uiState.value = _uiState.value.copy(
            translatedBitmap = result.bitmap,
            translationResult = result,
            progress = TranslationProgress.Done(result.bitmap),
        )
    }
}
```

### 预估文件
| 文件 | 用途 | 预估行数 |
|------|------|---------|
| `ui/viewmodel/WorkspaceViewModel.kt` (修改) | saveTranslation实现 | +40 |
| `data/local/TranslationHistoryEntity.kt` (修改) | 添加字段+迁移 | +10 |
| `data/model/TranslationHistory.kt` (修改) | 领域模型同步 | +3 |
| 数据库迁移（version 1→2） | Room Migration | +20 |

---

## 并行执行策略

```
全部5个轨道互不依赖，可同时启动：

轨道A: P2-1 LaMa修复器 ──────────────────── 预估4-6小时
轨道B: P2-2 DeepL翻译器 ──────────────────── 预估1-2小时
轨道C: P2-3 大图降采样 ──────────────────── 预估0.5-1小时
轨道D: P2-4 下载进度UI ──────────────────── 预估1-2小时
轨道E: P2-5 saveTranslation ──────────────── 预估1-2小时

关键路径: 轨道A（最复杂）
总耗时（并行）: ~4-6小时（受限于轨道A）
总耗时（串行）: ~8-13小时
并行加速比: ~2x
```

---

## 影响分析

| 模块 | 会影响哪些现有文件 | 需要新增文件 | 风险 |
|------|-------------------|-------------|------|
| P2-1 LaMa | TranslationModule, ModelRegistry | LamaInpainter.kt | 模型兼容性 |
| P2-2 DeepL | TranslationModule | DeeplTranslator.kt | API限制 |
| P2-3 降采样 | TranslationPipeline, ImageUtils, TranslationContext | - | 坐标映射 |
| P2-4 下载UI | TranslationProgress, TranslationPipeline, ModelDownloadManager, WorkspaceScreen | (可选)ModelDownloadIndicator.kt | UI布局 |
| P2-5 保存 | WorkspaceViewModel, TranslationHistoryEntity, AppDatabase | - | DB迁移 |

---

## Commit Strategy

```
P2-1: feat(p2): add LaMa ONNX inpainter
  - LamaInpainter.kt, ModelRegistry.kt, TranslationModule.kt

P2-2: feat(p2): add DeepL translator
  - DeeplTranslator.kt, TranslationModule.kt

P2-3: feat(p2): downsample large images before pipeline
  - ImageUtils.kt, TranslationPipeline.kt, TranslationContext.kt

P2-4: feat(p2): add model download progress UI
  - TranslationProgress.kt, TranslationPipeline.kt, ModelDownloadManager.kt, WorkspaceScreen.kt

P2-5: feat(p2): implement saveTranslation persistence
  - WorkspaceViewModel.kt, TranslationHistoryEntity.kt
```

---

## 风险登记

| # | 风险 | 缓解措施 |
|---|------|---------|
| R1 | LaMa ONNX模型导出后与onnxruntime-android不兼容 | 先导出一个简单的512×512 LaMa ONNX并验证推理 |
| R2 | LaMa推理速度太慢（>30秒/张） | 提供较小的tile尺寸配置；考虑ONNX fp16 |
| R3 | DeepL免费层耗尽 | 错误处理中提示用户；支持切换到GPT |
| R4 | 降采样导致小文字无法识别 | 保持 detectionSize 可配置（默认2048已足够） |
| R5 | 模型下载CDN不可用 | 提供多个备用URL；错误提示清晰 |
| R6 | Room数据库迁移失败 | 使用 `fallbackToDestructiveMigration()` 在调试版本 |
