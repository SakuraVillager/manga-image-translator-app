# Plan 1: Foundation Skeleton — 数据模型 + 管线框架 + 模块接口 + Koin DI

## TL;DR

> **Quick Summary**: 为漫画翻译App搭建完整的Kotlin骨架层——定义所有数据模型、7个模块接口、管线编排器、Koin DI配置和NoOp存根实现，使后续模块开发可以独立并行。
> 
> **Deliverables**:
> - 7个核心数据类 (TranslationContext, Quadrilateral, TextBlock, TranslationConfig + 子配置)
> - 7个模块接口 (TextDetector, TextRecognizer, Translator, Inpainter, TextRenderer, TextlineMerger, MaskRefiner)
> - 管线编排器 TranslationPipeline + 进度/结果密封类
> - Koin DI 模块配置
> - 7个 NoOp 存根实现
> - AppPreferences → TranslationConfig 映射器
> - 单元测试 (数据类 + 管线状态机 + Koin验证)
> 
> **Estimated Effort**: Medium
> **Parallel Execution**: YES - 5 waves
> **Critical Path**: Task 1 (数据模型) → Task 3 (管线框架) → Task 5 (Koin) → Task 7 (集成测试)

---

## Context

### Original Request
用户要求为"计划1：数据模型 + 管线框架 + DI"生成具体的代码实施计划。这是从manga-image-translator迁移架构文档中拆出的第一个实施计划。

### Interview Summary
**Key Decisions**:
- 包含所有7个模块接口定义（作为后续模块实现的契约）
- DI框架：Koin
- 需要单元测试
- TranslationConfig 从 AppPreferences 单向构建，不创建新 DataStore
- TextlineMerger/MaskRefiner 改为 interface（与DI一致），不再用 object
- 不修改任何现有 ViewModel/Screen/MainActivity

**Exclusions**:
- 不添加 ONNX Runtime / Ktor / OkHttp 依赖
- 不修改现有 WorkspaceViewModel / SettingsTranslationScreen / MainActivity
- 不实现任何实际算法（图聚类、形态学、透视变换、像素扫描）
- 不添加 koin-androidx-compose
- 不实现 DictionarySystem 独立模块（管道内用 stub）

### Metis Review
**Identified Gaps** (addressed):
- AppPreferences↔TranslationConfig 共存策略: 单向映射，TranslationConfig 是运行时对象
- TextlineMerger/MaskRefiner DI兼容: 改为 interface + NoOp impl
- Bitmap 测试: JVM 单元测试(数据类) + instrumented test(管线)
- Koin 版本: 锁定 3.5.6
- 存根行为规范: 每个 NoOp 有明确的返回值定义
- 词典方法: 管道内 private stub，pass-through
- 排除项清单: 已明确列出

---

## Work Objectives

### Core Objective
搭建翻译管线的完整Kotlin骨架——数据类型、接口契约、管线编排、DI注入和存根实现——使后续计划（ONNX检测/OCR、翻译API、渲染）可以并行开发。

### Concrete Deliverables
- `translation/data/` — 7个数据类 + 5个枚举
- `translation/api/` — 8个接口（PipelineModule + 7个模块接口）
- `translation/pipeline/` — TranslationPipeline, TranslationProgress, TranslationResult
- `translation/stub/` — 7个 NoOp 实现
- `translation/di/` — Koin 模块定义
- `translation/config/` — AppPreferences → TranslationConfig 映射器
- `translation/` — 所有新增 Gradle 依赖

### Definition of Done
- [ ] `./gradlew :app:assembleDebug` 编译通过，0 error
- [ ] `./gradlew :app:testDebugUnitTest` 全部通过
- [ ] 所有新增文件在 `com.sakuravillager.manga_translator.translation` 包下
- [ ] NoOp 管线可端到端运行（返回 NoText 结果，不崩溃）
- [ ] Koin 模块验证测试通过

### Must Have
- 完整的数据类定义（字段与架构文档对齐）
- 7个模块接口（方法签名与架构文档对齐）
- 管线编排器（8步顺序执行，进度反馈，取消支持）
- Koin DI wiring
- NoOp 存根（每个方法有明确返回值）
- 数据类单元测试
- 管线状态机测试
- Koin 模块加载测试

### Must NOT Have (Guardrails)
- ❌ 任何 ONNX / ML 推理代码或依赖
- ❌ 任何 HTTP 客户端代码或依赖（Ktor/OkHttp/Retrofit）
- ❌ 修改现有 WorkspaceViewModel / WorkspaceScreen / SettingsTranslationScreen / MainActivity
- ❌ 实际算法实现（图聚类、形态学、透视变换、像素扫描）
- ❌ koin-androidx-compose 依赖
- ❌ 新的 DataStore 实例（只用现有的 app_preferences）
- ❌ @Serializable 注解（除非确认含序列化）
- ❌ AI slop: 过度注释、不必要的抽象层、泛泛的工具类

---

## Verification Strategy

> **ZERO HUMAN INTERVENTION** — ALL verification is agent-executed.

### Test Decision
- **Infrastructure exists**: Partial (只有 ExampleUnitTest.kt 空壳)
- **Automated tests**: YES (TDD-style: 接口和数据类先写测试)
- **Framework**: JUnit 4 + kotlinx-coroutines-test + Koin Test

### QA Policy
Each task includes agent-executed QA scenarios.
- **Data classes**: JVM unit test — assert field defaults, enum coverage
- **Pipeline**: Instrumented test (needs Bitmap) — run NoOp pipeline, verify state transitions
- **Koin**: JVM unit test with koin-test — verify all definitions resolve

---

## Execution Strategy

### Parallel Execution Waves

```
Wave 1 (Start Immediately — data models + enums):
├── Task 1: Core data models (TranslationContext, Quadrilateral, TextBlock)
├── Task 2: Config data models (TranslationConfig + 5 sub-configs + enums)
└── Task 3: Gradle dependencies update (Koin, coroutines-test, koin-test)

Wave 2 (After Wave 1 — interfaces + pipeline):
├── Task 4: Pipeline module interfaces (PipelineModule + 7 interfaces)
├── Task 5: Pipeline orchestrator (TranslationPipeline + Progress + Result)
└── Task 6: NoOp stub implementations (7 stubs)

Wave 3 (After Wave 2 — DI + mapping):
├── Task 7: Koin DI module + AppInitializer
└── Task 8: AppPreferences → TranslationConfig mapper

Wave 4 (After Wave 3 — tests):
├── Task 9: Data model unit tests
├── Task 10: Pipeline state machine tests
└── Task 11: Koin module verification tests

Wave FINAL (After ALL — review):
├── F1: Plan compliance audit (oracle)
├── F2: Code quality review (unspecified-high)
└── F3: Integration smoke test (unspecified-high)
```

### Dependency Matrix

| Task | Depends On | Blocks |
|------|-----------|--------|
| 1 | - | 4,5,6,9 |
| 2 | - | 4,5,8,9 |
| 3 | - | 7,10,11 |
| 4 | 1,2 | 5,6,10 |
| 5 | 1,4 | 6,7,10 |
| 6 | 4,5 | 7,10 |
| 7 | 3,5,6,8 | 11 |
| 8 | 2 | 7 |
| 9 | 1,2 | - |
| 10 | 3,4,5,6 | - |
| 11 | 3,7 | - |

### Agent Dispatch Summary

- **Wave 1**: 3 tasks — T1 `quick`, T2 `quick`, T3 `quick`
- **Wave 2**: 3 tasks — T4 `quick`, T5 `unspecified-high`, T6 `quick`
- **Wave 3**: 2 tasks — T7 `quick`, T8 `quick`
- **Wave 4**: 3 tasks — T9 `quick`, T10 `unspecified-high`, T11 `quick`
- **FINAL**: 3 tasks — F1 `oracle`, F2 `unspecified-high`, F3 `unspecified-high`

---

## TODOs

- [x] 1. Core data models — TranslationContext, Quadrilateral, TextBlock

  **What to do**:
  - Create `translation/data/Quadrilateral.kt` with data class and computed property stubs
  - Create `translation/data/TextBlock.kt` with data class and computed property stubs
  - Create `translation/data/TranslationContext.kt` with all intermediate result fields
  - Create `translation/data/TextDirection.kt` enum
  - Create `translation/data/TextAlignment.kt` enum
  - Create `translation/data/DetectionResult.kt` data class
  - Computed properties: define with STUB implementations (return 0f, RectF(), PointF(), etc.)
  - `Quadrilateral.getTransformedRegion()`: stub returns input bitmap (identity)
  - `TextBlock.getFontColors()`: stub returns Pair(null, null)

  **Must NOT do**:
  - No actual geometry algorithms (angle/area/distance calculation)
  - No perspective transform implementation in getTransformedRegion()
  - No pixel analysis in getFontColors()
  - No @Serializable annotations

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES (with Task 2, Task 3)
  - **Parallel Group**: Wave 1
  - **Blocks**: Tasks 4, 5, 6, 9
  - **Blocked By**: None

  **References**:
  - `.sisyphus/plans/manga-translator-migration.md` §2.1-2.3 — Complete data class definitions with field types
  - `app/src/main/java/com/sakuravillager/manga_translator/data/model/ViewState.kt` — Existing data model pattern (enum)
  - `app/src/main/java/com/sakuravillager/manga_translator/data/model/TranslationHistory.kt` — Existing data class pattern
  - Python: `D:\manga-image-translator\manga-image-translator\manga_translator\utils\textblock.py` — TextBlock original (70+ fields, but we only need the 15-20 core ones)
  - Python: `D:\manga-image-translator\manga-image-translator\manga_translator\utils\generic.py:356-599` — Quadrilateral original

  **Acceptance Criteria**:
  - [ ] Files exist: `translation/data/Quadrilateral.kt`, `TextBlock.kt`, `TranslationContext.kt`, `TextDirection.kt`, `TextAlignment.kt`, `DetectionResult.kt`
  - [ ] All data classes have correct fields per architecture doc §2
  - [ ] All enum values match architecture doc §2.4
  - [ ] Quadrilateral has computed properties: boundingBox, center, angle, area, aspectRatio, fontSize (stubs OK)
  - [ ] TextBlock has computed properties: isHorizontal, isVertical, center, getFontColors() (stubs OK)
  - [ ] Project compiles: `./gradlew :app:compileDebugKotlin` succeeds

  **QA Scenarios**:

  ```
  Scenario: Data classes instantiate with default values
    Tool: Bash
    Preconditions: Project compiles
    Steps:
      1. Run: ./gradlew :app:compileDebugKotlin
      2. Assert: BUILD SUCCESSFUL in output
    Expected Result: Compilation succeeds with 0 errors
    Evidence: .sisyphus/evidence/task-1-compile-success.txt

  Scenario: Quadrilateral default construction
    Tool: Bash (instrumented test)
    Preconditions: App installed on device/emulator
    Steps:
      1. Run test that creates: Quadrilateral(points = listOf(PointF(0f,0f),PointF(1f,0f),PointF(1f,1f),PointF(0f,1f)))
      2. Assert: text == "", probability == 0f, direction == AUTO
    Expected Result: Default values match architecture spec
    Evidence: .sisyphus/evidence/task-1-quad-defaults.txt
  ```

  **Commit**: YES
  - Message: `feat(translation): add core data models (Quadrilateral, TextBlock, TranslationContext)`
  - Files: `app/src/main/java/com/sakuravillager/manga_translator/translation/data/*.kt`

- [x] 2. Config data models — TranslationConfig + sub-configs + enums

  **What to do**:
  - Create `translation/data/config/TranslationConfig.kt` with nested config classes
  - Create `translation/data/config/DetectorConfig.kt`
  - Create `translation/data/config/OcrConfig.kt`
  - Create `translation/data/config/TranslatorConfig.kt`
  - Create `translation/data/config/InpainterConfig.kt`
  - Create `translation/data/config/RendererConfig.kt`
  - Create `translation/data/config/GptConfig.kt` (placeholder with apiKey/apiBase/model)
  - Create 5 enum files in `translation/data/config/`:
    - `DetectorType.kt` { CTD, DEFAULT, DBCONVNEXT, CRAFT, PADDLE, NONE }
    - `OcrEngineType.kt` { MODEL_48PX, MODEL_32PX, MODEL_48PX_CTC, MOCR }
    - `TranslatorType.kt` { GPT_COMPATIBLE, DEEPL, BAIDU, YOUDAO, NONE, ORIGINAL }
    - `InpainterType.kt` { LAMA_LARGE, LAMA_MPE, AOT, SIMPLE_FILL, NONE }
    - `RendererType.kt` { DEFAULT, MANGA2ENG, NONE }
  - All config classes use Kotlin default values matching Python defaults per architecture doc §2.4

  **Must NOT do**:
  - No DataStore persistence code
  - No @Serializable annotations
  - No validation logic beyond type safety

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES (with Task 1, Task 3)
  - **Parallel Group**: Wave 1
  - **Blocks**: Tasks 4, 5, 8, 9
  - **Blocked By**: None

  **References**:
  - `.sisyphus/plans/manga-translator-migration.md` §2.4 — Complete config hierarchy with field names, types, defaults
  - `app/src/main/java/com/sakuravillager/manga_translator/data/preferences/AppPreferences.kt` — Existing string-based preferences to map TO
  - Python: `D:\manga-image-translator\manga-image-translator\manga_translator\config.py` — Original Pydantic config (lines 154-350)

  **Acceptance Criteria**:
  - [ ] Files exist: 6 config data classes + 5 enum files
  - [ ] TranslationConfig contains all 5 sub-configs with correct default values
  - [ ] DetectorConfig.default values: detector=CTD, detectionSize=2048, textThreshold=0.5f, boxThreshold=0.75f
  - [ ] TranslatorConfig.default values: translator=GPT_COMPATIBLE, targetLanguage="CHS"
  - [ ] All enum values match architecture doc §2.4
  - [ ] Project compiles

  **QA Scenarios**:

  ```
  Scenario: Config defaults match Python defaults
    Tool: Bash
    Steps:
      1. Run: ./gradlew :app:testDebugUnitTest --tests "*ConfigTest*"
      2. Assert: Tests pass, default values verified
    Expected Result: TranslationConfig() creates config with all Python-matching defaults
    Evidence: .sisyphus/evidence/task-2-config-defaults.txt

  Scenario: Enum completeness
    Tool: Bash
    Steps:
      1. Run test asserting DetectorType.values().size == 6
      2. Run test asserting TranslatorType.values().size == 6
      3. Run test asserting InpainterType.values().size == 5
    Expected Result: All enums have correct member count per architecture doc
    Evidence: .sisyphus/evidence/task-2-enum-completeness.txt
  ```

  **Commit**: YES
  - Message: `feat(translation): add TranslationConfig and module type enums`
  - Files: `app/src/main/java/com/sakuravillager/manga_translator/translation/data/config/*.kt`

- [x] 3. Gradle dependencies update — Koin + test libraries

  **What to do**:
  - Add to `gradle/libs.versions.toml`:
    - `koin = "3.5.6"` (version)
    - `koin-core = { group = "io.insert-koin", name = "koin-core", version.ref = "koin" }`
    - `koin-android = { group = "io.insert-koin", name = "koin-android", version.ref = "koin" }`
    - `koin-test = { group = "io.insert-koin", name = "koin-test", version.ref = "koin" }`
    - `koin-test-junit4 = { group = "io.insert-koin", name = "koin-test-junit4", version.ref = "koin" }`
    - `coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version = "1.9.0" }`
  - Add to `app/build.gradle.kts`:
    - `implementation(libs.koin.core)`
    - `implementation(libs.koin.android)`
    - `testImplementation(libs.koin.test)`
    - `testImplementation(libs.koin.test.junit4)`
    - `testImplementation(libs.coroutines.test)`
  - Verify project still compiles after changes

  **Must NOT do**:
  - No koin-androidx-compose
  - No Ktor/OkHttp/Retrofit
  - No ONNX Runtime
  - No Robolectric

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES (with Task 1, Task 2)
  - **Parallel Group**: Wave 1
  - **Blocks**: Tasks 7, 10, 11
  - **Blocked By**: None

  **References**:
  - `gradle/libs.versions.toml` — Current version catalog structure
  - `app/build.gradle.kts` — Current dependency declarations

  **Acceptance Criteria**:
  - [ ] `gradle/libs.versions.toml` contains koin, coroutines-test entries
  - [ ] `app/build.gradle.kts` contains implementation + testImplementation for Koin and coroutines-test
  - [ ] `./gradlew :app:assembleDebug` succeeds
  - [ ] No existing dependencies are removed or modified

  **QA Scenarios**:

  ```
  Scenario: Gradle sync and build
    Tool: Bash
    Steps:
      1. Run: ./gradlew :app:assembleDebug
      2. Assert: BUILD SUCCESSFUL
    Expected Result: New dependencies resolve and compile
    Evidence: .sisyphus/evidence/task-3-gradle-build.txt

  Scenario: No dependency conflicts
    Tool: Bash
    Steps:
      1. Run: ./gradlew :app:dependencies --configuration debugRuntimeClasspath
      2. Assert: No FAILED or conflict warnings for koin/coroutines
    Expected Result: Clean dependency resolution
    Evidence: .sisyphus/evidence/task-3-dependencies.txt
  ```

  **Commit**: YES
  - Message: `build: add Koin DI and coroutines-test dependencies`
  - Files: `gradle/libs.versions.toml`, `app/build.gradle.kts`

- [x] 4. Pipeline module interfaces — PipelineModule + 7 module interfaces

  **What to do**:
  - Create `translation/api/PipelineModule.kt` — base interface with name, prepare(), release(), isReady
  - Create `translation/api/TextDetector.kt` — interface with detect(bitmap, config): DetectionResult
  - Create `translation/api/TextRecognizer.kt` — interface with recognize(bitmap, textlines, config): List<Quadrilateral>
  - Create `translation/api/Translator.kt` — interface with translate(texts, fromLang, toLang, config): List<String>, plus supportedLanguages
  - Create `translation/api/Inpainter.kt` — interface with inpaint(bitmap, mask, config): Bitmap
  - Create `translation/api/TextRenderer.kt` — interface with render(bitmap, textRegions, config): Bitmap
  - Create `translation/api/TextlineMerger.kt` — interface with merge(textlines, width, height): List<TextBlock>
  - Create `translation/api/MaskRefiner.kt` — interface with refine(textRegions, bitmap, rawMask, kernelSize, dilationOffset): Bitmap
  - All interfaces follow architecture doc §3 signatures exactly

  **Must NOT do**:
  - No implementation classes (that's Task 6)
  - No default implementations on interfaces
  - No Android-specific imports (keep interface pure Kotlin except Bitmap)

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: NO (depends on Task 1, 2 for data types)
  - **Parallel Group**: Wave 2 (with Task 5, 6 — but 5 depends on 4)
  - **Blocks**: Tasks 5, 6, 10
  - **Blocked By**: Tasks 1, 2

  **References**:
  - `.sisyphus/plans/manga-translator-migration.md` §3 — Complete interface definitions
  - Python `detection/common.py:10-69` — CommonDetector interface (detect method signature)
  - Python `ocr/common.py:11-40` — CommonOCR interface (recognize method signature)
  - Python `translators/common.py:105-160` — CommonTranslator interface (translate method signature)
  - Python `inpainting/common.py:7-24` — CommonInpainter interface (inpaint method signature)

  **Acceptance Criteria**:
  - [ ] 8 interface files exist in `translation/api/`
  - [ ] PipelineModule has: name: String, suspend prepare(), suspend release(), val isReady: Boolean
  - [ ] TextDetector.detect() returns DetectionResult (not tuple)
  - [ ] TextRecognizer.recognize() returns List<Quadrilateral>
  - [ ] Translator has supportedSourceLanguages + supportedTargetLanguages + supportsLanguagePair()
  - [ ] TextlineMerger is an interface (NOT object)
  - [ ] MaskRefiner is an interface (NOT object)
  - [ ] Project compiles

  **QA Scenarios**:

  ```
  Scenario: All interfaces compile
    Tool: Bash
    Steps:
      1. Run: ./gradlew :app:compileDebugKotlin
      2. Assert: BUILD SUCCESSFUL, 0 errors
    Expected Result: All 8 interfaces compile without issues
    Evidence: .sisyphus/evidence/task-4-interfaces-compile.txt
  ```

  **Commit**: YES
  - Message: `feat(translation): add pipeline module interfaces`
  - Files: `app/src/main/java/com/sakuravillager/manga_translator/translation/api/*.kt`

- [x] 5. Pipeline orchestrator — TranslationPipeline + Progress + Result

  **What to do**:
  - Create `translation/pipeline/TranslationProgress.kt` — sealed class (Idle, Loading, Processing, Done)
  - Create `translation/pipeline/TranslationResult.kt` — sealed class (Success, NoText, Cancelled, Error)
  - Create `translation/pipeline/TranslationPipeline.kt`:
    - Constructor: all 7 module interfaces + TranslationConfig
    - `val progress: StateFlow<TranslationProgress>`
    - `suspend fun translate(inputBitmap: Bitmap): TranslationResult`
    - Pipeline executes 8 steps: Detect → Recognize → Merge → Translate → Refine Mask → Inpaint → Render → Finalize
    - Progress updates at each step via `_progress.value = ...`
    - CancellationException handling → TranslationResult.Cancelled
    - Error handling → TranslationResult.Error
    - Early exit when no text detected → TranslationResult.NoText
    - finally block calls detector.release() and recognizer.release()
    - Private stub methods: applyPreDictionary() (pass-through), applyPostDictionary() (pass-through), filterInvalidTranslations() (return input)
  - Pipeline class should be testable with NoOp modules (from Task 6)

  **Must NOT do**:
  - No actual algorithm implementations
  - No Bitmap manipulation (crop, resize, etc.)
  - No dictionary loading from files

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: NO (depends on Task 1, 4)
  - **Parallel Group**: Wave 2 (starts after Task 4)
  - **Blocks**: Tasks 6, 7, 10
  - **Blocked By**: Tasks 1, 4

  **References**:
  - `.sisyphus/plans/manga-translator-migration.md` §4.1 — Complete pipeline pseudocode
  - Python `manga_translator/manga_translator.py:432-622` — _translate() method (exact step sequence)
  - Python `manga_translator/manga_translator.py:360-430` — translate() method (prepare + _translate + context save)

  **Acceptance Criteria**:
  - [ ] TranslationPipeline class compiles with all 7 module dependencies
  - [ ] translate() method has 8 pipeline steps in correct order
  - [ ] progress StateFlow emits: Idle → Loading → Processing(detect,0.1) → Processing(ocr,0.25) → ... → Done
  - [ ] NoOp pipeline returns TranslationResult.NoText (because TextlineMerger.merge returns empty)
  - [ ] CancellationException is caught and returns TranslationResult.Cancelled
  - [ ] Generic Exception caught and returns TranslationResult.Error(message, exception)
  - [ ] finally block releases detector and recognizer

  **QA Scenarios**:

  ```
  Scenario: Pipeline with NoOp modules runs end-to-end
    Tool: Bash (instrumented test)
    Steps:
      1. Create TranslationPipeline with all NoOp modules
      2. Call translate(testBitmap)
      3. Assert: result is TranslationResult.NoText
      4. Assert: progress sequence includes Idle, Loading, Processing, Done
    Expected Result: Pipeline completes without crash, returns NoText
    Evidence: .sisyphus/evidence/task-5-pipeline-noop.txt

  Scenario: Pipeline cancellation
    Tool: Bash (unit test with runTest)
    Steps:
      1. Launch translate() in a coroutine
      2. Cancel the coroutine after 100ms
      3. Assert: result is TranslationResult.Cancelled
    Expected Result: Cancellation handled gracefully
    Evidence: .sisyphus/evidence/task-5-pipeline-cancel.txt
  ```

  **Commit**: YES
  - Message: `feat(translation): add TranslationPipeline orchestrator with progress and result types`
  - Files: `app/src/main/java/com/sakuravillager/manga_translator/translation/pipeline/*.kt`

- [x] 6. NoOp stub implementations — 7 stubs for all module interfaces

  **What to do**:
  - Create `translation/stub/NoOpTextDetector.kt` — detect() returns DetectionResult(emptyList(), null, null)
  - Create `translation/stub/NoOpTextRecognizer.kt` — recognize() returns input textlines unchanged
  - Create `translation/stub/NoOpTranslator.kt` — translate() returns input texts unchanged (identity); supportedLanguages = all
  - Create `translation/stub/NoOpInpainter.kt` — inpaint() returns input bitmap unchanged
  - Create `translation/stub/NoOpTextRenderer.kt` — render() returns input bitmap unchanged
  - Create `translation/stub/NoOpTextlineMerger.kt` — merge() returns emptyList()
  - Create `translation/stub/NoOpMaskRefiner.kt` — refine() returns rawMask or blank Bitmap
  - All stubs: prepare() logs + sets isReady=true, release() logs + sets isReady=false
  - All stubs: name property returns "NoOp[ModuleName]"

  **Must NOT do**:
  - No actual processing logic
  - No error throwing (stubs should be usable)
  - No TODO/FIXME comments (implement in later plans)

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: NO (depends on Task 4, 5 for interfaces + pipeline)
  - **Parallel Group**: Wave 2
  - **Blocks**: Tasks 7, 10
  - **Blocked By**: Tasks 4, 5

  **References**:
  - `.sisyphus/plans/manga-translator-migration.md` §3 — Interface definitions (to implement)
  - `translation/api/` — Interfaces from Task 4

  **Acceptance Criteria**:
  - [ ] 7 NoOp files exist in `translation/stub/`
  - [ ] Each implements its corresponding interface
  - [ ] NoOpTextDetector.detect() returns DetectionResult(emptyList(), null, null)
  - [ ] NoOpTranslator.translate() returns same list as input
  - [ ] NoOpInpainter.inpaint() returns input bitmap
  - [ ] NoOpTextlineMerger.merge() returns emptyList()
  - [ ] All prepare() set isReady = true
  - [ ] All release() set isReady = false
  - [ ] Project compiles

  **QA Scenarios**:

  ```
  Scenario: All stubs instantiate and run
    Tool: Bash (instrumented test)
    Steps:
      1. Create each NoOp stub
      2. Call prepare() on each, assert isReady == true
      3. Call the primary method on each
      4. Call release() on each, assert isReady == false
    Expected Result: All stubs run without crash
    Evidence: .sisyphus/evidence/task-6-stubs-run.txt
  ```

  **Commit**: YES (groups with Task 5)
  - Message: `feat(translation): add NoOp stub implementations for all module interfaces`
  - Files: `app/src/main/java/com/sakuravillager/manga_translator/translation/stub/*.kt`

- [x] 7. Koin DI module + application initialization

  **What to do**:
  - Create `translation/di/TranslationModule.kt` — Koin module providing:
    - All 7 NoOp stubs as `single<PipelineModule>(named("detector")) { NoOpTextDetector() }` etc.
    - Actually: use `single<TextDetector> { NoOpTextDetector() }` pattern for type-safe injection
    - `single { TranslationPipeline(get(), get(), get(), get(), get(), get(), get(), get()) }`
    - `single { TranslationConfig() }` (default config; later plans will add mapper)
  - Create `translation/di/KoinInitializer.kt` — helper object to startKoin with the translation module
  - **DO NOT** call startKoin in MainActivity (that's deferred to integration plan)
  - KoinInitializer should be callable from Application class or test

  **Must NOT do**:
  - No koin-androidx-compose
  - No modification to existing MainActivity
  - No modification to existing ViewModels

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: NO (depends on Tasks 3, 5, 6, 8)
  - **Parallel Group**: Wave 3
  - **Blocks**: Task 11
  - **Blocked By**: Tasks 3, 5, 6, 8

  **References**:
  - `gradle/libs.versions.toml` — Koin version (from Task 3)
  - `app/build.gradle.kts` — Koin dependencies (from Task 3)
  - `app/src/main/java/com/sakuravillager/manga_translator/MainActivity.kt` — Current initialization pattern (DO NOT MODIFY)

  **Acceptance Criteria**:
  - [ ] TranslationModule.kt provides all 7 module interfaces + TranslationPipeline + TranslationConfig
  - [ ] KoinInitializer.start() can be called to initialize Koin with the module
  - [ ] Koin module compiles and loads without errors
  - [ ] Existing app behavior unchanged (Koin not yet wired to Activity)

  **QA Scenarios**:

  ```
  Scenario: Koin module loads all definitions
    Tool: Bash (unit test with koin-test)
    Steps:
      1. Start Koin with TranslationModule
      2. Assert: get<TextDetector>() is NoOpTextDetector
      3. Assert: get<TranslationPipeline>() is TranslationPipeline
      4. Assert: get<TranslationConfig>() has correct defaults
    Expected Result: All definitions resolve to correct types
    Evidence: .sisyphus/evidence/task-7-koin-load.txt
  ```

  **Commit**: YES
  - Message: `feat(translation): add Koin DI module with NoOp stub bindings`
  - Files: `app/src/main/java/com/sakuravillager/manga_translator/translation/di/*.kt`

- [x] 8. AppPreferences → TranslationConfig mapper

  **What to do**:
  - Create `translation/config/TranslationConfigMapper.kt` with:
    - `fun AppPreferences.toTranslationConfig(): TranslationConfig` — extension function
    - Maps string fields to typed enums:
      - `translator: "GPT-4 Vision"` → `TranslatorType.GPT_COMPATIBLE`
      - `textDetector: "default_contour"` → `DetectorType.CTD`
      - `ocrEngine: "google_cloud_vision"` → `OcrEngineType.MODEL_48PX`
      - `imageRepair: "inpaint_lama"` → `InpainterType.LAMA_LARGE`
      - `textDirection: "auto_detect_vertical"` → `TextDirection.AUTO`
    - Unknown string values map to sensible defaults (log warning)
  - This is ONE-WAY mapping only (AppPreferences → TranslationConfig)
  - No write-back to DataStore

  **Must NOT do**:
  - No reverse mapping (TranslationConfig → AppPreferences)
  - No new DataStore instance
  - No modification to AppPreferences or PreferencesRepository

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES (with Task 7, after Task 2)
  - **Parallel Group**: Wave 3
  - **Blocks**: Task 7
  - **Blocked By**: Task 2

  **References**:
  - `app/src/main/java/com/sakuravillager/manga_translator/data/preferences/AppPreferences.kt` — Source: 5 translation-related string fields
  - `.sisyphus/plans/manga-translator-migration.md` §2.4 — Target: TranslationConfig with typed enums

  **Acceptance Criteria**:
  - [ ] `translation/config/TranslationConfigMapper.kt` exists
  - [ ] AppPreferences.toTranslationConfig() extension function works
  - [ ] "GPT-4 Vision" maps to TranslatorType.GPT_COMPATIBLE
  - [ ] "default_contour" maps to DetectorType.CTD
  - [ ] "inpaint_lama" maps to InpainterType.LAMA_LARGE
  - [ ] Unknown string values map to first enum value with a logged warning

  **QA Scenarios**:

  ```
  Scenario: All known preference strings map correctly
    Tool: Bash (unit test)
    Steps:
      1. Create AppPreferences with default values
      2. Call toTranslationConfig()
      3. Assert: config.translator.translator == TranslatorType.GPT_COMPATIBLE
      4. Assert: config.detector.detector == DetectorType.CTD
    Expected Result: All mappings produce correct enum values
    Evidence: .sisyphus/evidence/task-8-mapper-known.txt

  Scenario: Unknown preference string falls back gracefully
    Tool: Bash (unit test)
    Steps:
      1. Create AppPreferences(translator = "unknown_translator")
      2. Call toTranslationConfig()
      3. Assert: config.translator.translator == TranslatorType.GPT_COMPATIBLE (default)
    Expected Result: No crash, sensible default
    Evidence: .sisyphus/evidence/task-8-mapper-unknown.txt
  ```

  **Commit**: YES (groups with Task 7)
  - Message: `feat(translation): add AppPreferences to TranslationConfig mapper`
  - Files: `app/src/main/java/com/sakuravillager/manga_translator/translation/config/TranslationConfigMapper.kt`

- [x] 9. Data model unit tests

  **What to do**:
  - Create `app/src/test/java/.../translation/data/QuadrilateralTest.kt`:
    - Test default construction
    - Test property access (text, probability, direction)
    - Test enum values (TextDirection, TextAlignment)
  - Create `app/src/test/java/.../translation/data/TextBlockTest.kt`:
    - Test default construction
    - Test text concatenation
    - Test enum values
  - Create `app/src/test/java/.../translation/data/config/TranslationConfigTest.kt`:
    - Test default values match Python defaults
    - Test all sub-configs have correct defaults
    - Test all enums have correct member count
  - Create `app/src/test/java/.../translation/config/TranslationConfigMapperTest.kt`:
    - Test all known string → enum mappings
    - Test unknown string fallback
  - These are JVM unit tests (no Android dependencies needed for data classes)

  **Must NOT do**:
  - No instrumented tests (these are pure data class tests)
  - No Bitmap creation (data classes don't need it)

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES (after Tasks 1, 2)
  - **Parallel Group**: Wave 4
  - **Blocks**: None
  - **Blocked By**: Tasks 1, 2

  **References**:
  - Tasks 1, 2 output — Data classes and configs
  - Task 8 output — Mapper

  **Acceptance Criteria**:
  - [ ] 4 test files exist
  - [ ] `./gradlew :app:testDebugUnitTest` passes with 0 failures
  - [ ] Test coverage: all enum values, all default values, all mapper mappings

  **QA Scenarios**:

  ```
  Scenario: All data model tests pass
    Tool: Bash
    Steps:
      1. Run: ./gradlew :app:testDebugUnitTest
      2. Assert: BUILD SUCCESSFUL, 0 test failures
    Expected Result: All data model unit tests pass
    Evidence: .sisyphus/evidence/task-9-unit-tests.txt
  ```

  **Commit**: YES
  - Message: `test(translation): add unit tests for data models and config mapper`
  - Files: `app/src/test/java/com/sakuravillager/manga_translator/translation/**/*.kt`

- [x] 10. Pipeline state machine tests

  **What to do**:
  - Create `app/src/androidTest/java/.../translation/pipeline/TranslationPipelineTest.kt`:
    - Test: NoOp pipeline returns NoText
    - Test: progress sequence includes Idle → Loading → Processing → Done
    - Test: cancellation returns Cancelled
    - Test: exception in a step returns Error
  - These are instrumented tests (need Bitmap for pipeline input)
  - Use a small test bitmap (1x1 or 10x10 ARGB_8888)

  **Must NOT do**:
  - No JVM-only test for pipeline (needs Bitmap)
  - No large bitmaps that cause OOM

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES (after Tasks 3, 4, 5, 6)
  - **Parallel Group**: Wave 4
  - **Blocks**: None
  - **Blocked By**: Tasks 3, 4, 5, 6

  **References**:
  - Task 5 output — TranslationPipeline
  - Task 6 output — NoOp stubs

  **Acceptance Criteria**:
  - [ ] Pipeline test file exists in androidTest
  - [ ] Test: NoOp pipeline with 1x1 bitmap returns NoText
  - [ ] Test: Progress flow is Idle → Loading → Processing → (NoText exit)
  - [ ] Test: Coroutine cancellation results in Cancelled

  **QA Scenarios**:

  ```
  Scenario: Pipeline instrumented tests pass
    Tool: Bash
    Steps:
      1. Run: ./gradlew :app:connectedDebugAndroidTest
      2. Assert: All pipeline tests pass
    Expected Result: Pipeline state machine works correctly with NoOp modules
    Evidence: .sisyphus/evidence/task-10-pipeline-tests.txt
  ```

  **Commit**: YES (groups with Task 11)
  - Message: `test(translation): add pipeline state machine instrumented tests`
  - Files: `app/src/androidTest/java/com/sakuravillager/manga_translator/translation/pipeline/*.kt`

- [x] 11. Koin module verification tests

  **What to do**:
  - Create `app/src/test/java/.../translation/di/TranslationModuleTest.kt`:
    - Use koin-test to verify module loads
    - Test: get<TextDetector>() returns NoOpTextDetector
    - Test: get<TextRecognizer>() returns NoOpTextRecognizer
    - Test: get<Translator>() returns NoOpTranslator
    - Test: get<Inpainter>() returns NoOpInpainter
    - Test: get<TextRenderer>() returns NoOpTextRenderer
    - Test: get<TextlineMerger>() returns NoOpTextlineMerger
    - Test: get<MaskRefiner>() returns NoOpMaskRefiner
    - Test: get<TranslationPipeline>() returns TranslationPipeline
    - Test: get<TranslationConfig>() returns TranslationConfig with defaults
  - These are JVM unit tests using koin-test

  **Must NOT do**:
  - No Android context required for koin-test (use koin-core test)

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES (after Tasks 3, 7)
  - **Parallel Group**: Wave 4
  - **Blocks**: None
  - **Blocked By**: Tasks 3, 7

  **References**:
  - Task 7 output — Koin module

  **Acceptance Criteria**:
  - [ ] Koin test file exists
  - [ ] All 9 Koin definitions resolve to correct types
  - [ ] `./gradlew :app:testDebugUnitTest` passes

  **QA Scenarios**:

  ```
  Scenario: Koin verification test passes
    Tool: Bash
    Steps:
      1. Run: ./gradlew :app:testDebugUnitTest --tests "*TranslationModuleTest*"
      2. Assert: All definitions resolve
    Expected Result: Koin DI wiring is correct
    Evidence: .sisyphus/evidence/task-11-koin-test.txt
  ```

  **Commit**: YES (groups with Task 10)
  - Message: `test(translation): add Koin module verification tests`
  - Files: `app/src/test/java/com/sakuravillager/manga_translator/translation/di/*.kt`

---

## Final Verification Wave (after ALL tasks)

- [x] F1. **Plan Compliance Audit** — `oracle`
  Read the plan. For each "Must Have": verify implementation exists (read file, check compile). For each "Must NOT Have": grep codebase for forbidden patterns. Verify all data class fields match architecture doc. Output: VERDICT

- [x] F2. **Code Quality Review** — `unspecified-high`
  Run `./gradlew :app:compileDebugKotlin` + lint. Review all new files for: `as any`/`@Suppress`, empty catches, console.log, commented-out code, unused imports. Check AI slop: excessive comments, over-abstraction. Output: VERDICT

- [x] F3. **Integration Smoke Test** — `unspecified-high`
  Run `./gradlew :app:testDebugUnitTest` + `./gradlew :app:assembleDebug`. Verify NoOp pipeline can be instantiated via Koin and called with a test Bitmap. Output: VERDICT

---

## Commit Strategy

- **Task 1**: `feat(translation): add core data models` — translation/data/*.kt
- **Task 2**: `feat(translation): add TranslationConfig and module type enums` — translation/data/config/*.kt
- **Task 3**: `build: add Koin DI and coroutines-test dependencies` — gradle/libs.versions.toml, app/build.gradle.kts
- **Task 4**: `feat(translation): add pipeline module interfaces` — translation/api/*.kt
- **Task 5+6**: `feat(translation): add TranslationPipeline and NoOp stubs` — translation/pipeline/*.kt, translation/stub/*.kt
- **Task 7+8**: `feat(translation): add Koin DI module and config mapper` — translation/di/*.kt, translation/config/*.kt
- **Task 9**: `test(translation): add data model unit tests` — test/.../translation/data/*.kt
- **Task 10+11**: `test(translation): add pipeline and Koin verification tests` — test/.../translation/pipeline/*.kt, test/.../translation/di/*.kt

---

## Success Criteria

### Verification Commands
```bash
./gradlew :app:assembleDebug          # Expected: BUILD SUCCESSFUL
./gradlew :app:testDebugUnitTest       # Expected: All tests pass, 0 failures
./gradlew :app:compileDebugKotlin      # Expected: 0 errors
```

### Final Checklist
- [ ] All "Must Have" items present
- [ ] All "Must NOT Have" items absent
- [ ] NoOp pipeline runs end-to-end (returns NoText, no crash)
- [ ] Koin module loads all definitions
- [ ] All unit tests pass
- [ ] Existing app functionality unchanged (no regression)