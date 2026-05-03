# manga-image-translator Python → Kotlin/Android 迁移架构规划

## TL;DR

> **核心目标**: 将 Python 版 manga-image-translator 的漫画翻译管线迁移到现有 Kotlin/Android 项目，生成一份架构参考文档，供后续按模块拆分为独立实施任务。
> 
> **产出物**:
> - 完整的模块接口定义（Kotlin interface/data class）
> - 模块依赖关系图
> - 迁移优先级排序与难度评估
> - 技术选型决策记录（ADR）
> - 风险登记表
> - 最小可行管线 vs 完整管线 vs 延伸目标定义
> 
> **预估复杂度**: XL（架构级，涉及7+模块的接口设计与技术映射）
> **并行执行**: YES — 本文档各模块可按优先级并行开发
> **关键路径**: 数据模型定义 → 管线编排器 → 检测模块 → OCR模块 → 翻译模块 → 修复模块 → 渲染模块

---

## Context

### Original Request
用户希望将 Python 版 manga-image-translator 的核心翻译管线迁移到现有 Kotlin/Android 项目 (manga-image-translator-app)。迁移策略为：文字翻译用云端API（GPT/DeepL等），图像处理（检测/OCR等）用ONNX本地运行。这份规划文档是给用户审阅的，不是直接让执行者实现的。

### Interview Summary
**Key Discussions**:
- 翻译策略: 云端API优先（GPT/DeepL/百度等），不使用离线翻译模型
- 图像处理: 检测和OCR通过ONNX Runtime在Android本地运行
- 离线需求: 在线优先，核心功能需要网络
- 目标平台: 仅Android，利用原生API
- 规划范围: 完整架构文档，包含接口定义、优先级、难度评估
- 用户明确表示任务太大，需要分模块逐步实施

**Research Findings**:
- Python项目: 7+核心模块，24+翻译器实现，注册表+缓存模式，async全异步管线
- Kotlin项目: 完整UI壳（Compose/Material3/Room/DataStore），0%管线实现
- Preference字段已预设（translator/textDetector/ocrEngine/imageRepair）但均为stub

### Metis Review
**Identified Gaps** (addressed):
- ONNX模型体积与兼容性: 需要文档化每个模型的大小和建议策略
- 内存压力: Android需顺序加载模型，不能同时常驻
- Inpainting计算密集: 移动端可能需简化或走API
- TextBlock 70+字段: 需要精简为Android友好的数据类
- 大图处理: 需要降采样和位图回收策略
- 配置映射: Python Config → Kotlin DataStore + data class
- 语言代码映射: "CHS"/"JPN" → BCP-47 Locale
- Activity生命周期: 翻译进程需在ViewModel中管理
- 进度反馈: 需要Flow/StateFlow机制

---

## Work Objectives

### Core Objective
生成一份完整的架构迁移参考文档，定义Kotlin版翻译管线的模块边界、接口、数据流和迁移策略，使用户能按此文档逐模块拆分独立实施任务。

### Concrete Deliverables
- Kotlin data class 定义（TranslationContext, Quadrilateral, TextBlock, Config层级）
- 模块接口定义（TextDetector, TextRecognizer, Translator, Inpainter, Renderer）
- 模块依赖图（DAG）
- 每个模块的：难度评级(1-5)、依赖项、推荐技术选型、风险级别
- 迁移优先级排序和并行轨道路线
- 5项ADR（ONNX选择、图像处理库、网络库、并发模型、模型策略）
- 风险登记表（最低8项）
- 最小可行管线定义

### Definition of Done
- [ ] 所有7+Python模块都映射到Kotlin等价物
- [ ] 每个模块接口有输入/输出类型签名
- [ ] 依赖关系无环（DAG可拓扑排序）
- [ ] 每项ADR有明确的推荐和替代方案
- [ ] 风险登记表涵盖技术和产品风险
- [ ] 最小可行管线和完整管线区别清晰

### Must Have
- 模块接口定义（Kotlin interface）
- 核心数据类定义（TranslationContext, TextBlock, Config）
- 管线编排器架构（MangaTranslator → Kotlin TranslationPipeline）
- 检测/OCR模块的ONNX集成方案
- 翻译模块的API接口方案
- 模块依赖图
- 迁移优先级和难度评估

### Must NOT Have (Guardrails)
- 实际代码实现（只定义接口和数据结构签名）
- 色彩化模块（Python的colorizer，移动端不需要）
- 放大模块（Python的upscaling，移动端太重）
- PSD/XCF/GIMP导出（不适用移动端）
- MTPE/手动后编辑（不适用移动端）
- WebSocket/API服务器模式（App是客户端，不是服务端）
- 24+翻译器完整列表（只定义接口+3个初始实现：GPT兼容/DeepL/None）
- AI slop: 过度抽象的实现建议、泛泛而谈的"考虑使用XX模式"

---

## Verification Strategy (MANDATORY)

> 此规划文档为**参考架构文档**，不由自动化测试验证。验收标准为人工审阅。

### Verification Approach
- **完整性**: 覆盖Python项目所有核心管线步骤的映射
- **一致性**: 数据类定义与Python源码字段对齐
- **可行性**: 技术选型均为Android平台验证可用的方案
- **可操作性**: 每个模块足够具体，可独立拆为1-3个实施任务

### Document Quality Criteria
- 所有模块都有：目的、输入/输出、依赖、难度(1-5)、风险、推荐方案
- ADR有明确的推荐+替代+理由
- 最小管线步骤≤5，完整管线≤12
- 无环依赖

---

## Execution Strategy

> 注意：本文档是**规划文档**，不是执行任务列表。以下"任务"是文档的章节，不是代码实施任务。

本文档按以下顺序组织，每个章节可独立审阅：

```
章节1: 架构总览（管线图 + 模块依赖DAG）
章节2: 核心数据类型定义（Kotlin data class）
章节3: 模块接口定义（Kotlin interface）
章节4: 管线编排器架构（TranslationPipeline）
章节5: 模块深度剖析（每模块：接口/难度/依赖/风险/方案）
章节6: 技术决策记录（ADR）
章节7: 管线路线图（最小/标准/完整）
章节8: 风险登记表
章节9: 迁移优先级与并行轨道
章节10: 自审清单
```

---

## TODOs

> 注意：本规划的TODO项是**文档章节**，不是代码实施任务。当用户后续按模块拆分时，每个TODO可独立成为一个实施计划。

- [ ] 1. **数据模型定义** — §2 章节已定义TranslationContext, Quadrilateral, TextBlock, TranslationConfig等核心数据类
  - 当用户决定实现时，拆为独立任务：创建data model Kotlin文件
  - 确保与Python源码字段对齐
  - 优先级: P0（所有模块依赖此）

- [ ] 2. **管线框架搭建** — §4 章节已定义TranslationPipeline, Progress, Result
  - 创建TranslationPipeline骨架类
  - 实现StateFlow<Progress>进度机制
  - 实现错误处理（密封类Result）
  - 优先级: P0（管线编排器）

- [ ] 3. **ONNX Runtime集成验证** — ADR-1
  - 导出CTD和48px OCR模型为ONNX格式
  - 在Android设备上测试onnxruntime-android推理
  - 验证模型兼容性和推理速度
  - 优先级: P0（阻塞检测和OCR模块）

- [ ] 4. **文本检测模块** — §5.1
  - 实现TextDetector接口
  - CTD模型ONNX推理适配
  - Bitmap → ONNX Tensor转换
  - 检测结果后处理（NMS、坐标转换）
  - 优先级: P0

- [ ] 5. **OCR识别模块** — §5.2
  - 实现TextRecognizer接口
  - 48px OCR模型ONNX推理适配
  - Quadrilateral区域裁剪（perspective transform）
  - 字体颜色提取
  - 优先级: P0

- [ ] 6. **文本行合并模块** — §5.3
  - 实现TextlineMerger纯算法
  - 图聚类（Union-Find）
  - MST分裂
  - 方向投票和排序
  - 语言过滤
  - 优先级: P1

- [ ] 7. **翻译模块(GPT兼容)** — §5.4
  - 实现Translator接口
  - GPT兼容翻译器（覆盖OpenAI/DeepSeek/Groq/自定义）
  - 语言代码映射
  - 幻觉检测和重试
  - 优先级: P0

- [ ] 8. **翻译模块(DeepL)** — §5.4
  - DeepL API客户端实现
  - 格式保持和分段处理
  - 优先级: P1

- [ ] 9. **遮罩优化模块** — §5.5
  - 实现MaskRefiner纯算法
  - 膨胀和形态学操作（Android Canvas实现）
  - 气泡检测（白色/黑色像素比率）
  - 优先级: P1

- [ ] 10. **简易修复模块** — §5.6
  - 实现SimpleFill Inpainter（白色/背景色填充）
  - 优先级: P1

- [ ] 11. **文本渲染模块** — §5.7
  - 实现TextRenderer
  - CJK横排/竖排文字渲染
  - 透视变换（Matrix.setPolyToPoly）
  - 字号自适应
  - 字体描边
  - 优先级: P1

- [ ] 12. **高级修复模块(LaMa)** — §5.6
  - ONNX LaMa模型推理
  - 分块处理(tiling)
  - 内存优化
  - 优先级: P2

- [ ] 13. **词典系统** — §5.8
  - pre_dict和post_dict正则替换
  - 优先级: P2

- [ ] 14. **ViewModel集成 + UI连接** — §4.2
  - WorkspaceViewModel连接TranslationPipeline
  - 进度条和状态更新
  - 取消机制
  - 优先级: P1

- [ ] 15. **配置持久化** — §2.4
  - DataStore映射TranslationConfig
  - 设置页面联动
  - 优先级: P1

- [ ] 16. **依赖注入框架** — ADR-3
  - Koin设置（推荐轻量DI）
  - Module定义
  - 优先级: P0

- [ ] 17. **模型下载管理器** — ADR-5
  - 运行时模型下载
  - 断点续传
  - SHA256校验
  - 进度反馈
  - 优先级: P0

- [ ] 18. **错误处理 + 重试** — 管线级别
  - 各步骤错误回退策略
  - 翻译API重试
  - 用户友好错误消息
  - 优先级: P1

---

## §1 架构总览

### 1.1 Python管线 → Kotlin管线映射

Python `MangaTranslator._translate()` 的13步管线，在Kotlin中映射为：

```
Python Pipeline:                    Kotlin Pipeline:
───────────────                    ────────────────
[1] Colorization (opt)        →   ❌ 排除（移动端不需要）
[2] Upscaling (opt)           →   ❌ 排除（移动端太重）
[3] Image Load                →   ✅ Bitmap加载 + 预处理
[4] Text Detection            →   ✅ ONNX本地推理（CTD模型优先）
[5] OCR                       →   ✅ ONNX本地推理（48px模型）
[6] Textline Merge            →   ✅ 纯算法（图聚类+MST）
[7] Pre-Dictionary            →   ✅ 纯正则替换
[8] Translation               →   ✅ 云端API（GPT兼容 + DeepL）
[9] Post-Check + Post-Dict    →   ✅ 幻觉检测 + 正则替换
[10] Mask Refinement           →   ✅ 纯算法（膨胀+形态学）
[11] Inpainting                →   ⚠️ 初期简化（白色填充），后续考虑ONNX LaMa
[12] Rendering                 →   ✅ Android Canvas/Paint渲染
[13] Revert Upscale            →   ❌ 排除（无放大步骤则无需回退）
```

### 1.2 模块依赖DAG

```
                    ┌─────────────┐
                    │ TranslationConfig │
                    │ (DataStore/Room) │
                    └──────┬──────┘
                           │
                    ┌──────┴──────┐
                    │ TranslationPipeline │ ─── Kotlin管线编排器
                    │ (ViewModel调用)    │
                    └──────┬──────┘
                           │
         ┌─────────────────┼──────────────────┐
         │                 │                   │
    ┌────┴────┐    ┌──────┴──────┐    ┌──────┴───────┐
    │ TextDetector│    │ TextRecognizer │    │  Translator  │
    │ (ONNX)     │    │ (ONNX)        │    │  (Cloud API) │
    └────┬────┘    └──────┬──────┘    └──────┬───────┘
         │                 │                   │
         └────────┬────────┘                   │
                  │                            │
           ┌──────┴──────┐                     │
           │ TextlineMerger│                    │
           │ (纯算法)       │                    │
           └──────┬──────┘                     │
                  │                            │
           ┌──────┴──────┐                     │
           │ MaskRefiner  │                     │
           │ (纯算法)      │                     │
           └──────┬──────┘                     │
                  │                            │
           ┌──────┴──────┐              ┌──────┴───────┐
           │  Inpainter  │              │              │
           │ (简化/ONNX) │              │   Rendering  │
           └──────┬──────┘              │  (Canvas)     │
                  │                     └──────┬───────┘
                  └───────────┬───────────────┘
                              │
                       ┌──────┴──────┐
                       │ Output      │
                       │ (Bitmap→File)│
                       └─────────────┘
```

### 1.3 数据流全景

```
Bitmap (Android)
  │
  ▼
┌─────────────── TranslationContext ───────────────┐
│ inputBitmap: Bitmap                              │
│ imgRgb: Array<IntArray>?   (ARGB像素数组)         │
│ textlines: List<Quadrilateral>        ← Detection│
│ textRegions: List<TextBlock>          ← Merge    │
│ mask: Bitmap?                          ← Refine  │
│ imgInpainted: Bitmap?                  ← Inpaint │
│ imgRendered: Bitmap?                   ← Render  │
│ resultBitmap: Bitmap?                  ← Final    │
│ progress: StateFlow<Progress>                      │
│ config: TranslationConfig                           │
└────────────────────────────────────────────────────┘
```

---

## §2 核心数据类型定义

### 2.1 Quadrilateral（四边形文本行）

Python → Kotlin映射：

```kotlin
data class Quadrilateral(
    val points: List<PointF>,      // 4个角点 (Python: pts np.ndarray 4×2)
    val text: String = "",         // 识别的文字
    val probability: Float = 0f,  // 置信度
    val fgColor: Int? = null,     // 前景色 ARGB
    val bgColor: Int? = null,     // 背景色 ARGB
    val direction: TextDirection = TextDirection.AUTO,
) {
    enum class TextDirection { AUTO, HORIZONTAL, VERTICAL, HORIZONTAL_RTL }

    // 计算属性
    val boundingBox: RectF          // 外接矩形
    val center: PointF              // 中心点
    val angle: Float                // 旋转角度(度)
    val area: Float                 // 面积
    val aspectRatio: Float          // 宽高比
    val fontSize: Float             // 估算字号

    fun getTransformedRegion(bitmap: Bitmap, direction: TextDirection, textHeight: Int): Bitmap
    fun distance(other: Quadrilateral): Float
}
```

### 2.2 TextBlock（合并文本区域）

```kotlin
data class TextBlock(
    val lines: List<List<PointF>>,  // N行, 每行4个点 (Python: lines N×4×2)
    val texts: List<String>,         // 每行识别的原文
    val text: String,                // 合并后的原文
    var translation: String = "",   // 翻译后文字
    val language: String = "",      // 检测语言 (CHS/JPN/ENG等)
    var targetLanguage: String = "", // 目标语言
    val fontSize: Int,              // 字号
    val angle: Float,               // 旋转角度
    val fgColor: Int?,              // 前景色
    val bgColor: Int?,              // 背景色
    val direction: TextDirection,   // 排版方向
    val alignment: TextAlignment,   // 对齐方式
    val lineSpacing: Float = 1f,    // 行距
) {
    enum class TextDirection { AUTO, HORIZONTAL, VERTICAL, HORIZONTAL_RTL }
    enum class TextAlignment { AUTO, LEFT, CENTER, RIGHT }

    val isHorizontal: Boolean
    val isVertical: Boolean
    val minRect: List<PointF>
    val unrotatedSize: Pair<Int, Int>
    val center: PointF

    fun getFontColors(): Pair<Int?, Int?>  // (fg, bg)
}
```

### 2.3 TranslationContext（管线上下文）

```kotlin
data class TranslationContext(
    // 输入
    val inputBitmap: Bitmap,
    val config: TranslationConfig,

    // 中间结果（逐步填充）
    var imgRgb: Array<IntArray>? = null,
    var textlines: List<Quadrilateral> = emptyList(),
    var rawMask: Bitmap? = null,
    var refinedMask: Bitmap? = null,
    var textRegions: List<TextBlock> = emptyList(),
    var imgInpainted: Bitmap? = null,
    var imgRendered: Bitmap? = null,
    var resultBitmap: Bitmap? = null,

    // 状态
    val fromLanguage: String = "",
    var debugImages: MutableMap<String, Bitmap> = mutableMapOf(),
)
```

### 2.4 TranslationConfig（配置层级）

```kotlin
data class TranslationConfig(
    val detector: DetectorConfig = DetectorConfig(),
    val ocr: OcrConfig = OcrConfig(),
    val translator: TranslatorConfig = TranslatorConfig(),
    val inpainter: InpainterConfig = InpainterConfig(),
    val renderer: RendererConfig = RendererConfig(),
    val kernelSize: Int = 3,
    val maskDilationOffset: Int = 20,
    val filterText: String? = null,
    val preDictPath: String? = null,
    val postDictPath: String? = null,
)

data class DetectorConfig(
    val detector: DetectorType = DetectorType.CTD,
    val detectionSize: Int = 2048,
    val textThreshold: Float = 0.5f,
    val boxThreshold: Float = 0.75f,
    val unclipRatio: Float = 2.3f,
    val detRotate: Boolean = false,
    val detAutoRotate: Boolean = false,
    val detInvert: Boolean = false,
    val detGammaCorrect: Boolean = false,
)

data class OcrConfig(
    val ocrEngine: OcrEngineType = OcrEngineType.MODEL_48PX,
    val minTextLength: Int = 0,
    val ignoreBubble: Int = 0,
)

data class TranslatorConfig(
    val translator: TranslatorType = TranslatorType.GPT_COMPATIBLE,
    val targetLanguage: String = "CHS",
    val skipLanguage: String? = null,
    val apiKey: String? = null,
    val apiBase: String? = null,
    val model: String? = null,
    val gptConfig: GptConfig? = null,
)

data class InpainterConfig(
    val inpainter: InpainterType = InpainterType.LAMA_LARGE,
    val inpaintingSize: Int = 2048,
)

data class RendererConfig(
    val renderer: RendererType = RendererType.DEFAULT,
    val alignment: TextAlignment = TextAlignment.AUTO,
    val fontSizeOffset: Int = 0,
    val fontSizeMinimum: Int = -1,
    val direction: TextDirection = TextDirection.AUTO,
    val disableFontBorder: Boolean = false,
    val fontColor: String? = null,
    val lineSpacing: Float? = null,
    val rtl: Boolean = true,
)

// Enumerations
enum class DetectorType { CTD, DEFAULT, DBCONVNEXT, CRAFT, PADDLE, NONE }
enum class OcrEngineType { MODEL_48PX, MODEL_32PX, MODEL_48PX_CTC, MOCR }
enum class TranslatorType { GPT_COMPATIBLE, DEEPL, BAIDU, YOUDAO, NONE, ORIGINAL }
enum class InpainterType { LAMA_LARGE, LAMA_MPE, AOT, SIMPLE_FILL, NONE }
enum class RendererType { DEFAULT, MANGA2ENG, NONE }
```

---

## §3 模块接口定义

### 3.1 统一基接口模式

Python的注册表模式 → Kotlin的策略模式：

```kotlin
interface PipelineModule {
    val name: String
    suspend fun prepare()    // 加载模型/初始化
    suspend fun release()    // 释放资源
    val isReady: Boolean     // 模型是否已加载
}
```

### 3.2 TextDetector（文本检测）

```kotlin
interface TextDetector : PipelineModule {
    override val name: String
    suspend fun detect(
        bitmap: Bitmap,
        config: DetectorConfig
    ): DetectionResult
}

data class DetectionResult(
    val textlines: List<Quadrilateral>,
    val rawMask: Bitmap?,
    val mask: Bitmap?,
)
```

### 3.3 TextRecognizer（OCR）

```kotlin
interface TextRecognizer : PipelineModule {
    override val name: String
    suspend fun recognize(
        bitmap: Bitmap,
        textlines: List<Quadrilateral>,
        config: OcrConfig
    ): List<Quadrilateral>   // 返回带.text的Quadrilateral
}
```

### 3.4 Translator（翻译）

```kotlin
interface Translator : PipelineModule {
    override val name: String
    val supportedSourceLanguages: Set<String>
    val supportedTargetLanguages: Set<String>

    suspend fun translate(
        texts: List<String>,
        fromLanguage: String,
        toLanguage: String,
        config: TranslatorConfig
    ): List<String>

    fun supportsLanguagePair(from: String, to: String): Boolean
}
```

### 3.5 Inpainter（修复）

```kotlin
interface Inpainter : PipelineModule {
    override val name: String
    suspend fun inpaint(
        bitmap: Bitmap,
        mask: Bitmap,
        config: InpainterConfig
    ): Bitmap
}
```

### 3.6 TextRenderer（渲染）

```kotlin
interface TextRenderer : PipelineModule {
    override val name: String
    suspend fun render(
        bitmap: Bitmap,
        textRegions: List<TextBlock>,
        config: RendererConfig
    ): Bitmap
}
```

### 3.7 TextlineMerger（文本行合并）

```kotlin
object TextlineMerger {
    fun merge(
        textlines: List<Quadrilateral>,
        imageWidth: Int,
        imageHeight: Int
    ): List<TextBlock>
}
```

### 3.8 MaskRefiner（遮罩优化）

```kotlin
object MaskRefiner {
    fun refine(
        textRegions: List<TextBlock>,
        bitmap: Bitmap,
        rawMask: Bitmap?,
        kernelSize: Int = 3,
        dilationOffset: Int = 20
    ): Bitmap
}
```

---

## §4 管线编排器架构

### 4.1 TranslationPipeline（核心管线）

```kotlin
class TranslationPipeline(
    private val detector: TextDetector,
    private val recognizer: TextRecognizer,
    private val merger: TextlineMerger,
    private val translator: Translator,
    private val maskRefiner: MaskRefiner,
    private val inpainter: Inpainter,
    private val renderer: TextRenderer,
    private val config: TranslationConfig,
) {
    private val _progress = MutableStateFlow<TranslationProgress>(TranslationProgress.Idle)
    val progress: StateFlow<TranslationProgress> = _progress.asStateFlow()

    suspend fun translate(inputBitmap: Bitmap): TranslationResult {
        val ctx = TranslationContext(inputBitmap = inputBitmap, config = config)
        try {
            _progress.value = TranslationProgress.Loading("准备模型...")
            detector.prepare()
            recognizer.prepare()

            _progress.value = TranslationProgress.Processing("检测文本...", 0.1f)
            val detectionResult = detector.detect(inputBitmap, config.detector)
            ctx.textlines = detectionResult.textlines
            ctx.rawMask = detectionResult.rawMask

            if (ctx.textlines.isEmpty()) {
                return TranslationResult.NoText(inputBitmap)
            }

            _progress.value = TranslationProgress.Processing("识别文字...", 0.25f)
            ctx.textlines = recognizer.recognize(inputBitmap, ctx.textlines, config.ocr)

            if (ctx.textlines.all { it.text.isBlank() }) {
                return TranslationResult.NoText(inputBitmap)
            }

            _progress.value = TranslationProgress.Processing("合并文本行...", 0.35f)
            ctx.textRegions = merger.merge(ctx.textlines, inputBitmap.width, inputBitmap.height)

            // Pre-dictionary
            ctx.textRegions = applyPreDictionary(ctx.textRegions, config)

            _progress.value = TranslationProgress.Processing("翻译中...", 0.45f)
            val texts = ctx.textRegions.map { it.text }
            val translations = translator.translate(texts, "auto", config.translator.targetLanguage, config.translator)
            ctx.textRegions = ctx.textRegions.zip(translations) { region, translation ->
                region.copy(translation = translation)
            }

            // Post-dictionary + validation
            ctx.textRegions = applyPostDictionary(ctx.textRegions, config)
            ctx.textRegions = filterInvalidTranslations(ctx.textRegions)

            _progress.value = TranslationProgress.Processing("优化遮罩...", 0.6f)
            ctx.refinedMask = maskRefiner.refine(ctx.textRegions, inputBitmap, ctx.rawMask)

            _progress.value = TranslationProgress.Processing("修复图像...", 0.7f)
            ctx.imgInpainted = inpainter.inpaint(inputBitmap, ctx.refinedMask, config.inpainter)

            _progress.value = TranslationProgress.Processing("渲染文字...", 0.85f)
            ctx.imgRendered = renderer.render(ctx.imgInpainted!!, ctx.textRegions, config.renderer)

            _progress.value = TranslationProgress.Done(inputBitmap)
            return TranslationResult.Success(ctx.imgRendered!!, ctx.textRegions)

        } catch (e: CancellationException) {
            return TranslationResult.Cancelled
        } catch (e: Exception) {
            return TranslationResult.Error(e.message ?: "Unknown error", e)
        } finally {
            detector.release()
            recognizer.release()
        }
    }
}

sealed class TranslationProgress {
    object Idle : TranslationProgress()
    data class Loading(val message: String) : TranslationProgress()
    data class Processing(val message: String, val progress: Float) : TranslationProgress()
    data class Done(val result: Bitmap) : TranslationProgress()
}

sealed class TranslationResult {
    data class Success(val bitmap: Bitmap, val textRegions: List<TextBlock>) : TranslationResult()
    data class NoText(val originalBitmap: Bitmap) : TranslationResult()
    object Cancelled : TranslationResult()
    data class Error(val message: String, val exception: Exception) : TranslationResult()
}
```

### 4.2 ViewModel集成

```kotlin
class WorkspaceViewModel(
    private val pipeline: TranslationPipeline
) : ViewModel() {

    private val _uiState = MutableStateFlow(WorkspaceUiState())
    val uiState: StateFlow<WorkspaceUiState> = _uiState.asStateFlow()

    private var translationJob: Job? = null

    fun startTranslation(bitmap: Bitmap, config: TranslationConfig) {
        translationJob?.cancel()
        translationJob = viewModelScope.launch {
            pipeline.progress.collect { progress ->
                _uiState.value = _uiState.value.copy(progress = progress)
            }
        }
        viewModelScope.launch {
            when (val result = pipeline.translate(bitmap)) {
                is TranslationResult.Success -> { /* update UI */ }
                is TranslationResult.NoText -> { /* show message */ }
                is TranslationResult.Cancelled -> { /* reset */ }
                is TranslationResult.Error -> { /* show error */ }
            }
        }
    }

    fun cancelTranslation() {
        translationJob?.cancel()
    }
}
```

---

## §5 模块深度剖析

### 5.1 TextDetection（文本检测）⭐⭐⭐⭐

| 属性 | 值 |
|------|-----|
| **Python来源** | `detection/` 目录：6种检测器(default/ctd/craft/dbconvnext/paddle/none) |
| **Python接口** | `CommonDetector.detect(image, detect_size, text_threshold, box_threshold, unclip_ratio, invert, gamma_correct, rotate, auto_rotate, verbose)` → `(List[Quadrilateral], raw_mask, mask)` |
| **Kotlin接口** | `TextDetector.detect(bitmap, config): DetectionResult` |
| **输入** | Android Bitmap (RGB) + DetectorConfig |
| **输出** | List<Quadrilateral>（文本区域坐标）+ Mask Bitmap |
| **依赖** | ONNX Runtime Android, Coroutines |
| **难度** | 4/5（ONNX模型兼容性是核心风险） |
| **风险** | 高 — ONNX模型兼容性、内存占用、推理速度 |
| **预估LOC** | 500-700 |
| **推荐方案** | CTD模型优先（漫画专用检测器），ONNX Runtime inference |

**关键实现要点**:
- Python使用numpy数组(H,W,C) → Android使用Bitmap (ARGB_8888)
- 需要Bitmap → ONNX Tensor转换（归一化、通道排序）
- 检测器预处理（border/rotate/invert/gamma）需用Android Canvas/Matrix实现
- CTD模型是漫画专用，效果最好，优先迁移
- 模型文件约50-100MB，需下载或APK内置

**推荐迁移顺序**: CTD > default > none > craft > paddle > dbconvnext

### 5.2 TextRecognizer（OCR文字识别）⭐⭐⭐⭐

| 属性 | 值 |
|------|-----|
| **Python来源** | `ocr/` 目录：4种OCR(32px/48px/48px_ctc/mocr) |
| **Python接口** | `CommonOCR.recognize(image, textlines, config, device, verbose)` → `List[Quadrilateral]` (修改.text属性) |
| **Kotlin接口** | `TextRecognizer.recognize(bitmap, textlines, config): List<Quadrilateral>` |
| **输入** | Bitmap + List<Quadrilateral>（检测输出） |
| **输出** | List<Quadrilateral>（带.text文字） |
| **依赖** | ONNX Runtime Android |
| **难度** | 4/5 |
| **风险** | 高 — CJK字体识别需模型精确 |
| **预估LOC** | 400-600 |
| **推荐方案** | 48px模型优先（Python版推荐） |

**关键实现要点**:
- OCR从Quadrilateral中裁剪文字区域（perspective transform）
- Android用`Canvas.drawBitmap` + `Matrix`实现透视裁剪
- 模型需要逐区域推理，不是全图推理
- 字体颜色提取（fg_color/bg_color）通过像素分析实现
- 48px模型是默认推荐，准确率与速度的平衡点

### 5.3 TextlineMerger（文本行合并）⭐⭐⭐

| 属性 | 值 |
|------|-----|
| **Python来源** | `textline_merge/__init__.py`：基于networkx的图聚类+MST算法 |
| **Python接口** | `dispatch(textlines, width, height, verbose)` → `List[TextBlock]` |
| **Kotlin接口** | `TextlineMerger.merge(textlines, imageWidth, imageHeight): List<TextBlock>` |
| **输入** | List<Quadrilateral> + 图片尺寸 |
| **输出** | List<TextBlock>（合并后的文本区域） |
| **依赖** | 无外部依赖（纯算法） |
| **难度** | 3/5 |
| **风险** | 中 — 需要重写图算法 |
| **预估LOC** | 300-400 |
| **推荐方案** | 无networkx依赖，用Kotlin手写图算法 |

**关键实现要点**:
- Python用networkx做图聚类和最小生成树(MST)
- Kotlin需手写：并查集(Union-Find) + Kruskal MST
- 合并条件：距离 < 阈值、字号相近、角度兼容
- 方向投票：多数投票决定区域方向（横排/竖排）
- 语言过滤：跳过目标语言的文字区域
- 文本筛选：`is_valuable_text()` 逻辑

### 5.4 Translator（翻译器）⭐⭐⭐

| 属性 | 值 |
|------|-----|
| **Python来源** | `translators/` 目录：24+翻译器实现 |
| **Python接口** | `CommonTranslator.translate(from_lang, to_lang, queries, use_mtpe)` → `List[str]` |
| **Kotlin接口** | `Translator.translate(texts, fromLanguage, toLanguage, config): List<String>` |
| **输入** | List<String>（原文列表）+ 源/目标语言 |
| **输出** | List<String>（译文列表） |
| **依赖** | Ktor/OkHttp（HTTP客户端） |
| **难度** | 3/5（API类） |
| **风险** | 中 — API密钥管理和速率限制 |
| **预估LOC** | 300-400（GPT兼容）+ 150-200（DeepL） |
| **推荐方案** | 优先实现GPT兼容（覆盖OpenAI/DeepSeek/Groq/自定义）和DeepL |

**关键实现要点**:
- Python有24+翻译器，Kotlin只需定义接口 + 3个初始实现
- GPT兼容翻译器可覆盖：OpenAI, DeepSeek, Groq, 自定义OpenAI兼容端点
- 语言代码映射：内部 "CHS"/"JPN"/"ENG" ↔ API "zh"/"ja"/"en"
- 幻觉检测：重复检测（连续N个相同字符）、目标语言比率检测
- 上下文翻译：ChatGPT支持前页翻译作为上下文
- 后翻译校验需要速率控制和重试机制

### 5.5 MaskRefiner（遮罩优化）⭐⭐

| 属性 | 值 |
|------|-----|
| **Python来源** | `mask_refinement/`：形态学运算 + 气泡检测 |
| **Python接口** | `dispatch(text_regions, raw_image, raw_mask, method, dilation_offset, ignore_bubble, verbose, kernel_size)` → `np.ndarray` |
| **Kotlin接口** | `MaskRefiner.refine(textRegions, bitmap, rawMask, kernelSize, dilationOffset): Bitmap` |
| **输入** | List<TextBlock> + Bitmap + 原始Mask |
| **输出** | 优化后的Mask Bitmap |
| **依赖** | Android Bitmap API |
| **难度** | 2/5 |
| **风险** | 低 |
| **预估LOC** | 200-300 |
| **推荐方案** | Android Canvas + Bitmap操作 |

**关键实现要点**:
- 核心操作：膨胀(dilate)、裁剪到文字区域、形态学运算
- Android用`Canvas.drawPath` + `Paint` + `Bitmap` 实现类似OpenCV的形态学操作
- 气泡检测：白色/黑色像素比率判断是否为对话框区域
- kernel_size和dilation_offset控制遮罩扩展程度

### 5.6 Inpainter（图像修复）⭐ → ⭐⭐⭐⭐⭐（渐进式）

| 属性 | 值 |
|------|-----|
| **Python来源** | `inpainting/` 目录：6种修复器(AOT/LaMa_Large/LaMa_MPE/SD/None/Original) |
| **Python接口** | `CommonInpainter.inpaint(image, mask, config, inpainting_size, verbose)` → `np.ndarray` |
| **Kotlin接口** | `Inpainter.inpaint(bitmap, mask, config): Bitmap` |
| **输入** | 修复后Bitmap + Mask Bitmap |
| **输出** | 修复后Bitmap（文字区域被填充/替换） |
| **依赖** | MVP：无（简单填充）；后期：ONNX Runtime |
| **难度** | 1/5（简单填充）→ 5/5（LaMa ONNX） |
| **风险** | 低→高 |
| **预估LOC** | 80-100（简单）→ 800（LaMa） |
| **推荐方案** | MVP先实现SimpleFill（白色/邻近像素填充），后期考虑ONNX LaMa |

**关键实现要点**:
- **SimpleFill（MVP）**: 用Canvas在mask区域绘制白色或背景色。Python的NoneInpainter就是白色填充
- **LaMa ONNX（后期）**: 需要裁剪mask区域为tile，推理，合并。onnxruntime-android兼容性需验证
- **内存注意**: 大图需分块处理(tiling)，避免OOM
- **Python的tifling策略**: 图像按`inpainting_size`(2048)分块，分别推理后合并

### 5.7 TextRenderer（文本渲染）⭐⭐⭐⭐

| 属性 | 值 |
|------|-----|
| **Python来源** | `rendering/` 目录：FreeType渲染 + OpenCV透视变换 |
| **Python接口** | `dispatch(img, text_regions, font_path, ...)` → `np.ndarray` |
| **Kotlin接口** | `TextRenderer.render(bitmap, textRegions, config): Bitmap` |
| **输入** | 修复后Bitmap + List<TextBlock> |
| **输出** | 渲染后Bitmap（带翻译文字） |
| **依赖** | Android Canvas/Paint/Typeface |
| **难度** | 4/5（CJK排版是核心难点） |
| **风险** | 高 — 竖排文字、透视变换、字号自适应 |
| **预估LOC** | 800-1200 |
| **推荐方案** | Android Canvas + Paint + Matrix（替代FreeType + OpenCV） |

**核心难点**（这是整个项目最难迁移的模块之一）:
- **竖排文字渲染**: Python用FreeType逐字渲染竖排CJK。Android `Paint` + `Canvas`支持`Paint.VERTICAL_TEXT_FLAG`但效果不同
- **透视变换**: Python用`cv2.findHomography` + `cv2.warpPerspective`。Android用`Matrix.setPolyToPoly`实现类似效果
- **字号自适应**: 翻译文字可能比原文长/短，需动态调整字体大小和区域大小
- **字体描边**: Python用FreeType stroke。Android用`Paint.setStrokeWidth` + `Paint.setStyle(Paint.Style.STROKE_AND_FILL)`
- **行间距和字间距**: CJK文字的排版规则复杂
- **对齐方式**: 左对齐/居中/右对齐/自动检测

**转录Python关键逻辑到Android的关键映射**:

| Python | Android |
|-------|---------|
| `freetype-py` 渲染字体 | `android.graphics.Paint` + `Canvas.drawText` |
| `cv2.findHomography` | `android.graphics.Matrix.setPolyToPoly` |
| `cv2.warpPerspective` | `Canvas.drawBitmap(bitmap, matrix, paint)` |
| `np.ndarray` 透视变换 | `Matrix`仿射/透视变换 |
| `freetype.Font` 字体加载 | `Typeface.createFromFile` / `Typeface.create` |
| `shapely.geometry.Polygon` | 手写多边形计算工具 |

### 5.8 Dictionary（词典系统）⭐

| 属性 | 值 |
|------|-----|
| **Python来源** | `manga_translator.py`: `load_dictionary()` + `apply_dictionary()` |
| **Kotlin接口** | `object DictionarySystem { fun applyPre(text, dict): String; fun applyPost(text, dict): String }` |
| **难度** | 1/5 |
| **风险** | 低 |
| **预估LOC** | 100-150 |
| **推荐方案** | Kotlin Regex |

**关键实现要点**:
- 词典格式：每行`pattern replacement`（pattern是正则表达式）
- 前词典(pre_dict)：翻译前应用，修正OCR常见错误
- 后词典(post_dict)：翻译后应用，修正翻译不当之处
- 支持仅匹配模式（只有pattern没有replacement则删除匹配内容）

### 5.9 PipelineOrchestrator（管线编排器）⭐⭐⭐

| 属性 | 值 |
|------|-----|
| **Python来源** | `manga_translator.py`: `MangaTranslator._translate()` |
| **Kotlin实现** | `TranslationPipeline` class |
| **难度** | 3/5 |
| **风险** | 中 — 需要正确处理每个步骤的错误和回退 |
| **预估LOC** | 300-400 |
| **推荐方案** | Kotlin Coroutines + StateFlow |

**关键实现要点**:
- Python的async → Kotlin的suspend
- Python的try/catch per step with ignore_errors → Kotlin的Result/密封类
- Python的progress_hooks → Kotlin的StateFlow<Progress>
- Python的batch processing → Kotlin的Channel/Flow
- 模型生命周期管理：Python的TTL cache → Kotlin的引用计数 + 手动释放

---

## §6 技术决策记录（ADR）

### ADR-1: ONNX Runtime vs TensorFlow Lite 用于检测/OCR推理

**状态**: 推荐

**背景**: Python项目使用PyTorch模型进行文本检测和OCR。Android需要选择一个ML推理框架。

**选项**:
1. **ONNX Runtime Android** — 推荐
   - 优点: Python项目可以导出ONNX模型直接使用；社区活跃；支持CPU/GPU/NPU
   - 缺点: APK体积增加约10-15MB（per ABI）；部分opset可能不兼容
   - 兼容性: 需验证CTD和48px OCR模型的ONNX导出

2. **TensorFlow Lite**
   - 优点: Android原生支持；模型更小（TFLite格式）
   - 缺点: 需要PyTorch → ONNX → TFLite双重转换；转换易出错
   - 兼容性: 某些PyTorch操作不支持TFLite

3. **ML Kit (Google)**
   - 优点: 预训练模型（文字检测+识别）；免维护
   - 缺点: 模型不可控；可能不支持漫画专用检测；依赖Google Play Service

**决策**: 选用ONNX Runtime Android。需在项目初期验证模型兼容性（第一个实施任务）。

**回退方案**: 如果ONNX模型不兼容，退回到ML Kit做文字检测+识别，翻译仍然用API。

---

### ADR-2: Android图像处理 — OpenCV Android SDK vs 原生 android.graphics

**状态**: 推荐

**背景**: Python项目大量使用OpenCV（cv2）进行图像处理。Android需要选择图像处理方案。

**选项**:
1. **原生 android.graphics（Bitmap/Canvas/Matrix/Paint）** — 推荐
   - 优点: 零额外依赖；APK大小不增加；Android原生性能优化
   - 缺点: 功能不如OpenCV全面；某些算法需要手写（形态学、透视变换）
   - 实现: 大部分OpenCV操作在Android API中有对应：
     - `cv2.resize` → `Bitmap.createScaledBitmap`
     - `cv2.warpPerspective` → `Matrix.setPolyToPoly` + `Canvas.drawBitmap`
     - `cv2.dilate/erode` → `Canvas.drawPath` 多次绘制
     - `cv2.findHomography` → 需手写或简化为仿射变换

2. **OpenCV Android SDK（~15-20MB）**
   - 优点: 直接使用cv2函数，迁移成本最低
   - 缺点: APK增加15-20MB；native库增加启动时间；维护负担
   - 实现: 使用OpenCV Java绑定

3. **混合方案（推荐**）: 核心管线用android.graphics，仅形态学操作考虑手写简化版
   - 大部分操作可以在Canvas上完成
   - 遮罩的膨胀和腐蚀可以用`BlurMaskFilter`或多次绘制模拟
   - 如果后期需要更复杂的CV操作再引入OpenCV

**决策**: 优先使用android.graphics。形态学操作（膨胀、腐蚀）用Canvas多次绘制实现。仅在android.graphics完全无法胜任时考虑OpenCV。

---

### ADR-3: 网络库 — Ktor vs Retrofit vs OkHttp

**状态**: 推荐

**背景**: 云端翻译API需要HTTP客户端。项目目前没有网络库。

**选项**:
1. **Ktor Client** — 推荐
   - 优点: Kotlin原生协程支持；DSL风格API；多平台潜力；轻量级
   - 缺点: 文档不如Retrofit丰富；社区生态较小
   - 实现: `HttpClient(Android)` + `ContentNegotiation(Json)` 插件

2. **Retrofit + OkHttp**
   - 优点: 最成熟的Android网络库；大量示例；注解驱动
   - 缺点: 代码风格偏Java；协程支持需要额外适配器
   - 实现: 定义`MangaTranslationApi`接口

3. **OkHttp 原生**
   - 优点: 最底层控制；灵活性最高
   - 缺点: 需要手写JSON序列化/反序列化；代码量大

**决策**: 选用Ktor Client。与Kotlin协程天然集成，代码简洁，与Compose ViewModel配合良好。

**回退方案**: 如果Ktor遇到兼容性问题，可替换为Retrofit（接口兼容）。

---

### ADR-4: 并发模型 — Kotlin Coroutines vs RxJava vs Callback

**状态**: 推荐

**背景**: Python使用asyncio全异步管线。Android需要一个并发方案。

**选项**:
1. **Kotlin Coroutines + StateFlow** — 推荐
   - 优点: 与Python asyncio最接近的概念映射；ViewModel集成最自然；代码简洁
   - 缺点: 需要理解协程生命周期
   - 实现: `suspend fun translate()` + `MutableStateFlow<Progress>`

2. **RxJava**
   - 优点: 成熟的异步框架
   - 缺点: 学习曲线陡；代码冗长；与Kotlin风格不搭

3. **Callback-based**
   - 优点: 最简单
   - 缺点: 回调地狱；难以组合和取消

**决策**: 选用Kotlin Coroutines + StateFlow。管线每个步骤是`suspend fun`，进度通过`StateFlow`传播到ViewModel。

**关键映射**:
- Python `async def` → Kotlin `suspend fun`
- Python `await` → Kotlin `await()`（协程内）
- Python `_progress_hooks` → Kotlin `StateFlow<TranslationProgress>`
- Python `TranslationInterrupt` → Kotlin `CancellationException`
- Python `ignore_errors` flag → Kotlin `Result<>.getOrElse {}`
- Python batch processing → Kotlin `Flow`

---

### ADR-5: 模型分发策略 — APK内置 vs 运行时下载

**状态**: 推荐

**背景**: ONNX模型文件较大（检测器50-100MB，OCR 20-50MB，修复器100-200MB）。需要决定如何分发模型。

**选项**:
1. **运行时下载 + 本地缓存** — 推荐（MVP）
   - 优点: APK保持小巧（<30MB）；模型按需下载；可更新模型
   - 缺点: 首次使用需等待下载；需要网络连接；需要下载管理UI
   - 实现: 首次使用时检查模型文件，不存在则从CDN下载到`app/data/models/`

2. **APK内置（Asset/RAW）**
   - 优点: 离线可用；无需下载
   - 缺点: APK体积增加150-300MB；更新模型需发新版；用户即使不用也下载

3. **App Bundle Dynamic Feature**
   - 优点: 按需分发；减小基础APK体积
   - 缺点: 实现复杂；需要Play Core Library

**决策**: MVP使用运行时下载 + 本地缓存。模型文件托管在CDN（可用GitHub Release Assets），下载到应用私有目录。后续可考虑App Bundle动态特性。

**模型预估大小**:

| 模型 | 用途 | 预估大小(ONNX) | 优先级 |
|-----|------|--------------|--------|
| CTD (comic_text_detector) | 文本检测 | 50-80MB | P0(必须) |
| 48px OCR model | 文字识别 | 20-50MB | P0(必须) |
| LaMa Large | 图像修复 | 80-120MB | P2(后期) |
| AOT Inpainter | 图像修复 | 50-80MB | P2(后期) |

**总下载量(MVP)**: 70-130MB（检测+OCR）
**总下载量(完整)**: 200-330MB（加入修复模型）

---

## §7 管线路线图

### 7.1 最小可行管线（MVP）

```
Bitmap → TextDetection(ONNX) → OCR(ONNX) → Merge → Translation(API) → MaskRefine → SimpleFill → CanvasRender → Bitmap
```

步骤: 5步核心
预估工作量: 2-3个独立实施任务
目标: 能完成一次完整的漫画翻译流程

### 7.2 标准管线

```
MVP + LaMa修复(ONNX) + 进度反馈 + 多翻译器支持 + 配置持久化
```

步骤: 8步核心 + 3步增强
预估工作量: 5-8个独立实施任务
目标: 生产可用，质量接近Python版

### 7.3 完整管线

```
标准管线 + 多检测器选择 + 高级渲染(竖排/横排) + 词典系统 + 后翻译校验 + 批量处理
```

步骤: 12步核心 + 5步增强
预估工作量: 12-15个独立实施任务
目标: 功能完整，接近Python版所有核心功能

---

## §8 风险登记表

---

## §8 风险登记表

| # | 风险 | 影响 | 可能性 | 严重度 | 缓解措施 |
|---|------|------|--------|--------|----------|
| R1 | ONNX模型与onnxruntime-android不兼容 | 管线无法运行 | 中 | 高 | 早期验证：导出CTD和48px OCR为ONNX，在Android设备上测试推理。回退：使用ML Kit |
| R2 | Android内存不足（加载2+个大模型） | 应用崩溃 | 高 | 高 | 顺序加载模型，用完立即释放；只保留当前需要的模型在内存中 |
| R3 | CJK竖排文字渲染效果差 | 翻译结果视觉质量差 | 高 | 高 | 优先验证竖排渲染原型；可参考Python FreeType逻辑手写Paint渲染 |
| R4 | 检测/OCR在低端Android设备上推理速度慢(>10秒) | 用户体验差 | 中 | 中 | 支持降采样(detection_size参数)；提供低精度模式；进度反馈 |
| R5 | 翻译API不稳定或速率限制 | 翻译失败 | 低 | 中 | 实现重试机制+指数退避；支持多个翻译器回退；缓存相同文本翻译 |
| R6 | 大图(>4000px)导致OOM | 应用崩溃 | 中 | 高 | 自动降采样到detection_size(默认2048)；原图保存后处理小图；及时回收Bitmap |
| R7 | Activity生命周期中断翻译 | 数据丢失/进程终止 | 高 | 中 | 翻译在ViewModel + CoroutineScope中运行；支持取消和恢复 |
| R8 | 模型下载中断或失败 | 首次使用体验差 | 中 | 中 | 支持断点续传；模型文件校验SHA256；清晰的重试UI |
| R9 | Android Bitmap与numpy数组格式差异 | 图像处理结果不一致 | 中 | 中 | 建立统一的Bitmap↔数组转换层；ARGB_8888到RGB的转换需注意通道顺序 |
| R10 | Python的OpenCV透视变换精度高于Android Canvas | 文字定位偏差 | 低 | 低 | Matrix.setPolyToPoly最大支持4点透视变换；Python也是4点映射，精度应类似 |
| R11 | 透视变换文字区域裁剪精度 | OCR识别率下降 | 中 | 中 | 需要精确实现Quadrilateral区域裁剪；对比Python和Android的裁剪结果 |
| R12 | 多语言字体缺失 | 渲染效果差/显示方框 | 高 | 中 | 内置常用CJK字体(日语/中文/韩语)；使用Android系统字体作为回退；Noto Sans CJK |
| R13 | Context敏感性翻译(多页漫画) | 翻译质量降低 | 低 | 低 | 第一版不支持跨页上下文；后续版本通过保留前页翻译实现上下文传递 |
| R14 | App体积限制(Google Play >150MB需AAB) | 分发困难 | 低 | 中 | 模型按需下载不内置；APK本身控制在30MB以内 |
| R15 | Python代码中大量算法优化(line merge, mask refinement)是numpy向量化 | 重写为Kotlin性能下降 | 中 | 中 | 核心算法需保持Kotlin实现的高效性；热点路径考虑使用IntArray而非通用容器 |

---

## §9 迁移优先级与并行轨道

### 9.1 核心轨道（按依赖顺序）

```
轨道A: 基础设施（无依赖）
  A1: 数据模型定义 (TranslationContext, Quadrilateral, TextBlock, Config)
  A2: 管线框架 + 进度机制 (TranslationPipeline, Progress, Result)
  A3: DI框架集成 (Hilt/Koin)
  A4: ONNX Runtime集成 + 模型下载框架

轨道B: 核心模块（依赖轨道A）
  B1: 文本检测 (CTD模型 → ONNX → TextDetector)
  B2: OCR识别 (48px模型 → ONNX → TextRecognizer)
  B3: 文本行合并 (纯算法 → TextlineMerger)
  B4: 遮罩优化 (纯算法 → MaskRefiner)

轨道C: 翻译层（依赖轨道A）
  C1: 翻译接口 + GPT兼容翻译器
  C2: DeepL翻译器
  C3: 词典系统 (pre/post-dict)

轨道D: 图像处理（依赖轨道A + B）
  D1: 简易修复 (SimpleFill → Inpainter)
  D2: 文本渲染 (Canvas/Paint → TextRenderer)
  D3: 高级修复 (LaMa ONNX → Inpainter，D1后可独立迭代)

轨道E: 集成与优化（依赖全部）
  E1: ViewModel集成 + UI连接
  E2: 配置持久化 (DataStore映射)
  E3: 错误处理 + 重试机制
  E4: 大图优化 + 内存管理
```

### 9.2 并行度

```
        Week 1-2:  A1 ─┐ A3 ─┐
                          │     │
        Week 2-3:  A2 ─┘ A4 ─┘
                          │
        Week 3-5:  B1 ─┐ B3 ─┐ C1 ─┐ C2 ─┐
                    B2 ─┘ B4 ─┘     │     │
                                  C3 ─┘     │
                                            │
        Week 5-7:  D1 ─┐ D2 ─┐             │
                    │     │             │
        Week 7-8:           D3 ─┐         │
                                  │         │
        Week 8-10: E1─┐ E2─┐ E3─┐ E4─┐    │
                    └────┴────┴────┘───────┘
```

### 9.3 难度评级

| 模块 | 难度(1-5) | 风险 | 依赖 | 预估Kotlin LOC | 推荐技术 |
|------|----------|------|------|--------------|---------|
| A1 数据模型 | 2 | 低 | 无 | 400-500 | Kotlin data class + enum |
| A2 管线框架 | 3 | 中 | A1 | 300-400 | Kotlin Coroutines + StateFlow |
| A3 DI框架 | 2 | 低 | 无 | 100-150 | Koin (轻量) |
| A4 ONNX集成 | 4 | 高 | 无 | 200-300 | onnxruntime-android |
| B1 文本检测 | 4 | 高 | A1,A4 | 500-700 | ONNX Runtime + CTD模型 |
| B2 OCR | 4 | 高 | A1,A4 | 400-600 | ONNX Runtime + 48px模型 |
| B3 文本行合并 | 3 | 中 | A1 | 300-400 | 纯算法（图聚类） |
| B4 遮罩优化 | 2 | 低 | A1 | 200-300 | Android Bitmap + Canvas |
| C1 GPT翻译器 | 3 | 中 | A1 | 300-400 | Ktor/OkHttp + JSON |
| C2 DeepL翻译器 | 2 | 低 | A1 | 150-200 | Ktor/OkHttp + JSON |
| C3 词典系统 | 1 | 低 | 无 | 100-150 | Kotlin Regex |
| D1 简易修复 | 1 | 低 | A1 | 80-100 | Bitmap填充 |
| D2 文本渲染 | 4 | 高 | A1,B | 800-1200 | Android Canvas/Paint |
| D3 LaMa修复 | 5 | 高 | A4 | 600-800 | ONNX Runtime + LaMa模型 |
| E1 ViewModel集成 | 2 | 低 | 全部 | 200-300 | Compose ViewModel |
| E2 配置持久化 | 1 | 低 | A1 | 100-150 | DataStore |
| E3 错误处理 | 2 | 低 | A2 | 150-200 | Kotlin Result/ sealed class |
| E4 大图优化 | 3 | 中 | A2 | 200-300 | BitmapFactory.Options + 内存管理 |

**总预估Kotlin LOC**: 4500-7000行（不含测试和UI）

---

## §10 自审清单

- [ ] ✅ 所有7+核心管线步骤映射到Kotlin
- [ ] ✅ 每个模块有输入/输出类型签名
- [ ] ✅ 依赖关系无环
- [ ] ✅ 每项ADR有推荐和替代方案
- [ ] ✅ 风险登记表包含8+项
- [ ] ✅ 最小/标准/完整管线区别清晰
- [ ] ✅ 排除项（色彩化、放大、GIMP导出等）明确标注
- [ ] ✅ 技术选型基于Android平台验证可用的方案

---

## Final Verification Wave (after all implementation — N/A for this doc)

> 本文档为参考架构文档，不需要自动化执行验证。

- [ ] F1. **文档完整性审阅** — 确认所有章节已填写
- [ ] F2. **技术可行性审阅** — 确认所有技术选型在Android平台可行
- [ ] F3. **人工审阅** — 用户确认规划满足需求
- [ ] F4. **范围审阅** — 确认排除项正确，无遗漏核心模块