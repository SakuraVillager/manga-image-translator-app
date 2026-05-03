# Plan 2: ONNX Detection + OCR + Textline Merge + Model Download

## TL;DR

> **Quick Summary**: 修复 Plan 1 遗留缺失，搭建 ONNX Runtime + OpenCV 基础设施，实现 CTD 文本检测、48px OCR 识别、文本行合并算法和模型下载管理器，替换所有 NoOp 存根为真实实现。
> 
> **Deliverables**:
> - 6 个缺失数据模型文件 (Quadrilateral, TextBlock, DetectionResult, TranslationContext, TextDirection, TextAlignment)
> - Gradle 依赖修复 + ONNX Runtime 1.24.3 + OpenCV Android SDK
> - ONNX 基础设施层 (OnnxSessionManager, TensorConverter, OrtEnvironment 单例)
> - 模型下载管理器 (下载+断点续传+SHA256校验+进度)
> - CtdTextDetector (CTD ONNX 推理 + DBNet 后处理)
> - Model48pxTextRecognizer (透视裁剪 + 批量推理 + 贪心解码 + 颜色提取)
> - DefaultTextlineMerger (Union-Find + Kruskal MST + 方向投票)
> - Quadrilateral 完整几何方法 (distance, angle, area, getTransformedRegion, sortPoints)
> - Koin DI 模块更新 (替换 NoOp 为工厂模式)
> - 单元测试
> 
> **Estimated Effort**: XL
> **Parallel Execution**: YES - 4 waves
> **Critical Path**: Task 0 (数据模型) → Task 3 (几何方法) → Task 8 (检测器) → Task 10 (DI更新) → Task 11 (测试)

---

## Context

### Original Request
用户完成 Plan 1 (Foundation Skeleton) 后，希望继续实现核心 ONNX 推理模块。Plan 1 存在遗留问题：6 个核心数据模型文件缺失、Gradle 版本目录不完整。Plan 2 需要先修复这些缺失，再搭建 ONNX + OpenCV 基础设施，最终实现文本检测、OCR、文本行合并三个核心模块。

### Interview Summary
**Key Discussions**:
- Plan 1 遗留修复: 先修再建，Plan 2 首要任务是补齐缺失文件
- OpenCV 依赖: 引入 OpenCV Android SDK，用于 findContours/minAreaRect/warpPerspective
- 模型策略: 含模型下载管理器（CDN下载+断点续传+SHA256校验）
- 测试策略: Tests-after（实现后补测试）

**Research Findings**:
- CTD 检测: 输入 [1,3,1024,1024] float32 [0,1]，3 个 ONNX 输出（blks 未使用, mask, lines_map），DBNet 解码 (binarize>0.3, findContours, minAreaRect, unclip ratio=1.5, score>0.6)
- OCR 48px: 透视裁剪→批量(16, 对齐4)→归一化 [-1,1]→ONNX→贪心解码→颜色提取，4666 字符字典
- ONNX Runtime Android 1.24.3: OrtEnvironment 单例，.use{} 资源管理，Dispatchers.Default 推理
- Textline merge: Union-Find + Kruskal MST，canMergeRegion 谓词，递归分割，方向投票

### Metis Review
**Identified Gaps** (addressed):
- ONNX 模型导出: 需要从 Python PyTorch 导出 ONNX 文件，这是前置条件但非 Kotlin 任务
- 内存管理: 两个 ONNX 模型不能同时常驻，需顺序加载/释放
- OCR beam search 复杂度: 先实现贪心解码，beam search 留后续优化
- OpenCV 初始化: 需要 OpenCVLoader.initDebug() 或静态链接
- build.gradle.kts 中 Koin 硬编码版本号需统一到版本目录

---

## Work Objectives

### Core Objective
搭建 ONNX Runtime + OpenCV 基础设施，实现漫画翻译管线的三个核心本地推理模块（文本检测、OCR、文本行合并），替换所有 NoOp 存根，使管线可端到端运行（翻译和渲染仍为存根）。

### Concrete Deliverables
- `translation/data/` — 6 个缺失数据模型 + Quadrilateral 完整几何方法
- `translation/onnx/` — ONNX 会话管理、Tensor 转换工具
- `translation/detection/` — CtdTextDetector 实现
- `translation/ocr/` — Model48pxTextRecognizer 实现 + 字典加载
- `translation/merge/` — DefaultTextlineMerger 实现
- `translation/model/` — 模型下载管理器
- `translation/util/` — 几何工具函数 (polygonDistance, letterbox 等)
- `gradle/libs.versions.toml` — 修复 + 新增 ONNX/OpenCV 条目

### Definition of Done
- [ ] `./gradlew :app:compileDebugKotlin` 编译通过（需 JDK 17-21）
- [ ] CtdTextDetector.detect() 可调用（需 ONNX 模型文件）
- [ ] Model48pxTextRecognizer.recognize() 可调用（需 ONNX 模型文件）
- [ ] DefaultTextlineMerger.merge() 纯算法可测试
- [ ] 模型下载管理器可下载+校验模型文件
- [ ] Koin DI 注入真实实现（非 NoOp）
- [ ] 所有单元测试通过

### Must Have
- 完整的 Quadrilateral 数据类（含 distance, angle, area, getTransformedRegion, sortPoints）
- CTD 文本检测 ONNX 推理 + DBNet 后处理
- 48px OCR ONNX 推理 + 贪心解码 + 颜色提取
- 文本行合并算法（Union-Find + MST）
- ONNX 会话生命周期管理
- 模型下载管理器
- OpenCV Android SDK 集成

### Must NOT Have (Guardrails)
- ❌ 修改现有 ViewModel / Screen / MainActivity
- ❌ 翻译 API 实现（Plan 3）
- ❌ Inpainting 实现（Plan 4）
- ❌ Rendering 实现（Plan 4）
- ❌ LaMa ONNX 修复模型
- ❌ OCR beam search（先贪心解码，beam search 留后续）
- ❌ koin-androidx-compose
- ❌ @Serializable 注解
- ❌ AI slop: 过度注释、不必要的抽象层、泛泛的工具类
- ❌ Python 模型导出脚本（这是 Python 任务，非 Kotlin）

---

## Verification Strategy

> **ZERO HUMAN INTERVENTION** — ALL verification is agent-executed.

### Test Decision
- **Infrastructure exists**: YES (JUnit4 + coroutines-test + Koin Test from Plan 1)
- **Automated tests**: YES (Tests-after — 实现后补充)
- **Framework**: JUnit4 + kotlinx-coroutines-test + Koin Test

### QA Policy
- **Geometry/algorithm**: JVM unit test — assert computed values
- **ONNX inference**: Instrumented test (needs Context for asset loading) — verify tensor shapes
- **Model download**: Instrumented test — verify download + SHA256
- **Integration**: Instrumented test — pipeline with real models

---

## Execution Strategy

### Parallel Execution Waves

```
Wave 0 (Start Immediately — fix gaps + dependencies):
├── Task 0: Fix missing data models [quick]
├── Task 1: Fix Gradle + add ONNX/OpenCV deps [quick]
└── Task 2: Model download manager [deep]

Wave 1 (After Wave 0 — infrastructure + geometry + dictionary):
├── Task 3: ONNX infrastructure layer [unspecified-high]
├── Task 4: Quadrilateral geometry methods [deep]
├── Task 5: Geometry utilities [unspecified-high]
└── Task 6: Character set / dictionary loader [quick]

Wave 2 (After Wave 1 — core implementations):
├── Task 7: DefaultTextlineMerger [deep]
├── Task 8: CtdTextDetector [deep]
├── Task 9: Model48pxTextRecognizer [deep]
└── Task 10: Koin DI module update [quick]

Wave 3 (After Wave 2 — tests):
└── Task 11: Unit tests [unspecified-high]

Wave FINAL (After ALL — review):
├── F1: Plan compliance audit (oracle)
├── F2: Code quality review (unspecified-high)
├── F3: Integration smoke test (unspecified-high)
└── F4: Scope fidelity check (deep)
```

### Dependency Matrix

| Task | Depends On | Blocks |
|------|-----------|--------|
| 0 | - | 3,4,5,6,7,8,9,10,11 |
| 1 | - | 2,3,8,9 |
| 2 | 1 | 8,9 |
| 3 | 0,1 | 8,9 |
| 4 | 0 | 5,7,8,9 |
| 5 | 0,4 | 7,8,9 |
| 6 | 1 | 9 |
| 7 | 0,4,5 | 10,11 |
| 8 | 0,1,2,3,4,5 | 10,11 |
| 9 | 0,1,2,3,4,5,6 | 10,11 |
| 10 | 7,8,9 | 11 |
| 11 | 7,8,9,10 | - |

### Agent Dispatch Summary

- **Wave 0**: 3 tasks — T0 `quick`, T1 `quick`, T2 `deep`
- **Wave 1**: 4 tasks — T3 `unspecified-high`, T4 `deep`, T5 `unspecified-high`, T6 `quick`
- **Wave 2**: 4 tasks — T7 `deep`, T8 `deep`, T9 `deep`, T10 `quick`
- **Wave 3**: 1 task — T11 `unspecified-high`
- **FINAL**: 4 tasks — F1 `oracle`, F2 `unspecified-high`, F3 `unspecified-high`, F4 `deep`

---

## TODOs

- [x] 0. Fix missing data models — Quadrilateral, TextBlock, DetectionResult, TranslationContext, TextDirection, TextAlignment

  **What to do**:
  - Create `translation/data/Quadrilateral.kt` — data class with fields: points (List\<PointF\>), text, probability, direction, fgColor, bgColor. Add computed property STUBS: boundingBox, center, angle, area, aspectRatio, fontSize. Add method stubs: distance(), getTransformedRegion(), sortPoints().
  - Create `translation/data/TextBlock.kt` — data class with fields: lines (List\<List\<PointF\>\>), texts, text, translation, language, fontSize, angle, fgColor, bgColor, direction, alignment, lineSpacing. Add computed property stubs: isHorizontal, isVertical, minRect, center.
  - Create `translation/data/DetectionResult.kt` — data class(textlines: List\<Quadrilateral\>, rawMask: Bitmap?, mask: Bitmap?)
  - Create `translation/data/TranslationContext.kt` — data class(inputBitmap, config, imgRgb, textlines, rawMask, refinedMask, textRegions, imgInpainted, imgRendered, resultBitmap, fromLanguage, debugImages) — all fields with defaults
  - Create `translation/data/TextDirection.kt` — enum { AUTO, HORIZONTAL, VERTICAL, HORIZONTAL_RTL }
  - Create `translation/data/TextAlignment.kt` — enum { AUTO, LEFT, CENTER, RIGHT }
  - Ensure all existing files that import from `translation.data.*` compile correctly

  **Must NOT do**:
  - No actual geometry algorithms yet (Task 4 will implement)
  - No @Serializable annotations
  - No new Gradle dependencies

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES (with Task 1, Task 2)
  - **Parallel Group**: Wave 0
  - **Blocks**: Tasks 3, 4, 5, 6, 7, 8, 9, 10, 11
  - **Blocked By**: None

  **References**:
  - `.sisyphus/plans/manga-translator-migration.md` §2.1-2.3 — Complete data class definitions with field types
  - `app/src/main/java/.../translation/data/config/TranslationConfig.kt` — Existing config pattern to follow
  - `app/src/main/java/.../translation/stub/NoOpTextDetector.kt` — References DetectionResult, DetectorConfig
  - `app/src/main/java/.../translation/stub/NoOpTextRecognizer.kt` — References Quadrilateral
  - `app/src/main/java/.../translation/config/TranslationConfigMapper.kt` — References TextDirection
  - `app/src/test/java/.../translation/data/QuadrilateralTest.kt` — Test file expecting Quadrilateral API

  **Acceptance Criteria**:
  - [ ] 6 files exist in `translation/data/`
  - [ ] All imports in existing files resolve correctly
  - [ ] Quadrilateral has: points, text, probability, direction, fgColor, bgColor + stubs for boundingBox, center, angle, area, aspectRatio, fontSize, distance(), getTransformedRegion()
  - [ ] TextBlock has: lines, texts, text, translation, language, fontSize, angle, fgColor, bgColor, direction, alignment + stubs for isHorizontal, isVertical, center
  - [ ] Project compiles: `./gradlew :app:compileDebugKotlin` (requires JDK 17-21)

  **QA Scenarios**:
  ```
  Scenario: Data model files compile
    Tool: Bash
    Steps:
      1. Run: ./gradlew :app:compileDebugKotlin
      2. Assert: BUILD SUCCESSFUL
    Expected Result: All 6 new files compile without errors
    Evidence: .sisyphus/evidence/task-0-compile.txt

  Scenario: Existing imports resolve
    Tool: Bash
    Steps:
      1. Grep for "Unresolved reference" in build output
      2. Assert: None found
    Expected Result: All existing files that import from translation.data.* compile
    Evidence: .sisyphus/evidence/task-0-imports.txt
  ```

  **Commit**: YES
  - Message: `fix(translation): add missing data models from Plan 1`
  - Files: `app/src/main/java/.../translation/data/*.kt`

- [x] 1. Fix Gradle + add ONNX/OpenCV dependencies

  **What to do**:
  - Fix `gradle/libs.versions.toml`: add missing entries for koin (3.5.6), coroutines-test (1.9.0), koin-test, koin-test-junit4 that were referenced in build.gradle.kts but missing from version catalog
  - Add to `gradle/libs.versions.toml`:
    - `onnxruntime = "1.24.3"` + `onnxruntime-android = { group = "com.microsoft.onnxruntime", name = "onnxruntime-android", version.ref = "onnxruntime" }`
    - `opencv = "4.9.0"` + `opencv-android = { group = "org.opencv", name = "opencv-android", version.ref = "opencv" }`
  - Update `app/build.gradle.kts`:
    - Add `implementation(libs.onnxruntime.android)`
    - Add `implementation(libs.opencv.android)`
    - Fix Koin references to use `libs.*` consistently
  - Add ProGuard rule: `-keep class ai.onnxruntime.** { *; }` to `app/proguard-rules.pro`
  - Add ABI filter: `ndk { abiFilters += "arm64-v8a" }` in defaultConfig (for dev builds)

  **Must NOT do**:
  - No koin-androidx-compose
  - No Ktor/OkHttp/Retrofit
  - No LaMa ONNX model dependency

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES (with Task 0, Task 2)
  - **Parallel Group**: Wave 0
  - **Blocks**: Tasks 2, 3, 8, 9
  - **Blocked By**: None

  **References**:
  - `gradle/libs.versions.toml` — Current version catalog (missing Koin entries)
  - `app/build.gradle.kts` — Current build script (references libs.koin.* but catalog incomplete)
  - `app/proguard-rules.pro` — ProGuard rules file
  - ONNX Runtime Android research: version 1.24.3, ProGuard keep rule required, ABI filter recommended

  **Acceptance Criteria**:
  - [ ] `gradle/libs.versions.toml` has: onnxruntime-android, opencv-android, koin, coroutines-test entries
  - [ ] `app/build.gradle.kts` uses `libs.*` for all dependencies consistently
  - [ ] `app/proguard-rules.pro` has ONNX keep rule
  - [ ] `./gradlew :app:assembleDebug` succeeds (or at minimum dependency resolution works)

  **QA Scenarios**:
  ```
  Scenario: Gradle dependency resolution
    Tool: Bash
    Steps:
      1. Run: ./gradlew :app:dependencies --configuration debugRuntimeClasspath
      2. Assert: onnxruntime-android and opencv-android appear
      3. Assert: No FAILED or conflict warnings
    Expected Result: All new dependencies resolve
    Evidence: .sisyphus/evidence/task-1-deps.txt
  ```

  **Commit**: YES
  - Message: `build: fix version catalog and add ONNX/OpenCV dependencies`
  - Files: `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/proguard-rules.pro`

- [ ] 2. Model download manager

  **What to do**:
  - Create `translation/model/ModelDownloadManager.kt`:
    - Manages model file downloads to app internal storage (`context.filesDir/models/`)
    - Downloads from configurable CDN base URL (default: GitHub Release assets)
    - Supports: download with progress callback, resume interrupted downloads, SHA256 verification
    - Model registry: hardcoded list of model names + URLs + SHA256 + size
    - Download status: StateFlow\<DownloadStatus\> (Idle, Downloading(progress%), Verifying, Ready, Error)
    - `suspend fun ensureModel(modelName: String): File` — download if not exists, verify if exists
    - `fun isModelReady(modelName: String): Boolean` — check if file exists and SHA256 matches
    - `fun deleteModel(modelName: String)` — remove downloaded file
    - Uses OkHttp (already available via transitive dep) or java.net.HttpURLConnection for download
    - Stores partial downloads as `.part` files, renames on completion
  - Create `translation/model/ModelInfo.kt` — data class(name, url, sha256, sizeBytes)
  - Create `translation/model/ModelRegistry.kt` — object with model definitions:
    - `CTD_MODEL = ModelInfo("ctd", "https://...", "sha256...", 50_000_000)`
    - `OCR_48PX_MODEL = ModelInfo("ocr_48px", "https://...", "sha256...", 20_000_000)`
    - `ALPHABET_FILE = ModelInfo("alphabet", "https://...", "sha256...", 50_000)`
  - NOTE: actual URLs and SHA256 will be filled when models are exported to ONNX. Use placeholder URLs for now.

  **Must NOT do**:
  - No UI for download progress (that's ViewModel integration, Plan 5)
  - No model export from PyTorch (that's a Python task)
  - No simultaneous multi-model download

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES (with Task 0, Task 1, but needs Task 1 for Gradle)
  - **Parallel Group**: Wave 0
  - **Blocks**: Tasks 8, 9 (detectors need model files)
  - **Blocked By**: Task 1 (Gradle deps)

  **References**:
  - `.sisyphus/plans/manga-translator-migration.md` §5 ADR-5 — Model distribution strategy (runtime download + local cache)
  - `.sisyphus/plans/manga-translator-migration.md` §6 ADR-5 — Model size estimates (CTD 50-80MB, OCR 20-50MB)
  - `app/src/main/java/.../data/preferences/AppPreferences.kt` — Existing preferences pattern

  **Acceptance Criteria**:
  - [ ] `ModelDownloadManager.kt` exists with download/verify/delete/ensure methods
  - [ ] `ModelInfo.kt` and `ModelRegistry.kt` exist
  - [ ] SHA256 verification works (test with local test file)
  - [ ] Download progress callback works
  - [ ] Resume from partial download works
  - [ ] Project compiles

  **QA Scenarios**:
  ```
  Scenario: SHA256 verification
    Tool: Bash (unit test)
    Steps:
      1. Create test file with known content
      2. Compute SHA256
      3. Call verifySha256(file, expectedHash)
      4. Assert: returns true for correct hash, false for wrong hash
    Expected Result: SHA256 verification is correct
    Evidence: .sisyphus/evidence/task-2-sha256.txt

  Scenario: Model ensure with existing file
    Tool: Bash (unit test)
    Steps:
      1. Pre-create a model file with correct SHA256
      2. Call ensureModel("test_model")
      3. Assert: no download triggered, returns existing file
    Expected Result: Skip download when file already valid
    Evidence: .sisyphus/evidence/task-2-skip.txt
  ```

  **Commit**: YES
  - Message: `feat(translation): add model download manager`
  - Files: `app/src/main/java/.../translation/model/*.kt`

- [ ] 3. ONNX infrastructure layer

  **What to do**:
  - Create `translation/onnx/OnnxSessionManager.kt`:
    - Wraps OrtEnvironment (singleton) and OrtSession lifecycle
    - `suspend fun createSession(modelBytes: ByteArray, options: OrtSession.SessionOptions?): OrtSession` — creates session on Dispatchers.Default
    - `fun closeSession(session: OrtSession)` — safely closes session
    - `fun closeAll()` — close all managed sessions
    - Session tracking: maintains weak references or explicit list
    - Thread safety: session creation/close synchronized
  - Create `translation/onnx/TensorConverter.kt`:
    - `fun bitmapToNCHWTensor(env: OrtEnvironment, bitmap: Bitmap, targetWidth: Int, targetHeight: Int, normalizeMean: FloatArray, normalizeStd: FloatArray): OnnxTensor` — converts Android Bitmap to ONNX float32 tensor in NCHW format
    - `fun bitmapToNCHWTensor01(env: OrtEnvironment, bitmap: Bitmap, targetWidth: Int, targetHeight: Int): OnnxTensor` — normalize to [0,1] (for CTD detection)
    - `fun bitmapToNCHWTensorMinusOneOne(env: OrtEnvironment, bitmap: Bitmap, targetWidth: Int, targetHeight: Int): OnnxTensor` — normalize to [-1,1] (for OCR)
    - `fun extractFloatArray(result: OrtSession.Result, index: Int): Array<FloatArray>` — extract 2D float output
    - `fun extractFloatBuffer(result: OrtSession.Result, index: Int): FloatBuffer` — extract float buffer
    - All methods use Kotlin `.use {}` for resource safety
  - Create `translation/onnx/OnnxConstants.kt`:
    - `const val ORT_TAG = "OnnxRuntime"`
    - Default SessionOptions factory (ALL_OPT, intraOpThreads=4)

  **Must NOT do**:
  - No NNAPI yet (add later as optimization)
  - No model loading from assets (that's the caller's responsibility)
  - No direct bitmap manipulation beyond tensor conversion

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES (with Tasks 4, 5, 6, after Task 0+1)
  - **Parallel Group**: Wave 1
  - **Blocks**: Tasks 8, 9
  - **Blocked By**: Tasks 0, 1

  **References**:
  - ONNX Runtime Android research (from librarian agent): OrtEnvironment singleton, .use{} pattern, Dispatchers.Default
  - `app/src/main/java/.../translation/di/KoinInitializer.kt` — Existing initialization pattern
  - Python `detection/ctd_utils/basemodel.py` — TextDetBaseDNN: blobFromImage(1/255.0)
  - Python `ocr/model_48px.py` line 86-118 — OCR normalize: (pixel-127.5)/127.5

  **Acceptance Criteria**:
  - [ ] 3 files exist in `translation/onnx/`
  - [ ] OnnxSessionManager can create and close sessions
  - [ ] TensorConverter.bitmapToNCHWTensor01 produces correct shape [1,3,H,W]
  - [ ] TensorConverter.bitmapToNCHWTensorMinusOneOne produces values in [-1,1]
  - [ ] Project compiles

  **QA Scenarios**:
  ```
  Scenario: Tensor shape and range verification
    Tool: Bash (instrumented test)
    Steps:
      1. Create 10x10 ARGB_8888 test Bitmap
      2. Call bitmapToNCHWTensor01(env, bitmap, 10, 10)
      3. Assert: tensor.shape == [1, 3, 10, 10]
      4. Assert: all values >= 0 && <= 1
    Expected Result: Correct NCHW tensor shape and value range
    Evidence: .sisyphus/evidence/task-3-tensor.txt

  Scenario: Session lifecycle
    Tool: Bash (instrumented test)
    Steps:
      1. Create OnnxSessionManager
      2. Load test ONNX model bytes (small test model or mock)
      3. Create session, assert non-null
      4. Close session, assert no crash
    Expected Result: Session lifecycle works
    Evidence: .sisyphus/evidence/task-3-session.txt
  ```

  **Commit**: YES
  - Message: `feat(translation): add ONNX infrastructure layer`
  - Files: `app/src/main/java/.../translation/onnx/*.kt`

- [ ] 4. Quadrilateral geometry methods

  **What to do**:
  - Update `translation/data/Quadrilateral.kt` — replace ALL stubs with real implementations:
    - `val boundingBox: RectF` — min/max of all points
    - `val center: PointF` — average of 4 points
    - `val angle: Float` — compute from structure vectors: arccos(dot(normalize(v1), (1,0)))
    - `val area: Float` — shoelace formula for polygon area
    - `val aspectRatio: Float` — ||v2|| / ||v1|| where v1=structure[1]-structure[0], v2=structure[2]-structure[3]
    - `val fontSize: Float` — min(||v1||, ||v2||)
    - `val structure: List<PointF>` — 4 edge midpoints: mid(pts[0],pts[1]), mid(pts[2],pts[3]), mid(pts[1],pts[2]), mid(pts[3],pts[0])
    - `fun distance(other: Quadrilateral, rho: Float = 0.5f): Float` — if assignedDirection==null: min point-to-point distance; else: pattern-match via convex hull area / fontSize
    - `fun getTransformedRegion(bitmap: Bitmap, direction: TextDirection, textHeight: Int): Bitmap` — perspective crop using OpenCV findHomography + warpPerspective. If VERTICAL: rotate 90° CCW after warp.
    - `companion object { fun sortPoints(pts: List<PointF>): List<PointF> }` — sort 4 points to TL/TR/BR/BL order. Compute pairwise vectors, find 2 longest sides, determine direction (h/v), sort accordingly.

  **Must NOT do**:
  - No shapely dependency — use pure math (shoelace, Euclidean distance)
  - No numpy — use Kotlin math
  - getTransformedRegion may use OpenCV (Imgproc.findHomography, Imgproc.warpPerspective)

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES (with Tasks 3, 5, 6, after Task 0)
  - **Parallel Group**: Wave 1
  - **Blocks**: Tasks 5, 7, 8, 9
  - **Blocked By**: Task 0

  **References**:
  - Python `utils/generic.py:356-599` — Quadrilateral original: structure, aspect_ratio, font_size, angle, distance, get_transformed_region, sort_pnts
  - `.sisyphus/plans/manga-translator-migration.md` §2.1 — Quadrilateral field definitions
  - Python `utils/generic.py:445-520` — sort_pnts algorithm: find longest sides, determine h/v, sort TL/TR/BR/BL
  - Python `utils/generic.py:521-599` — get_transformed_region: structure vectors, ratio, findHomography, warpPerspective, rotate for vertical

  **Acceptance Criteria**:
  - [ ] All computed properties return mathematically correct values
  - [ ] angle returns radians in [0, π)
  - [ ] area returns positive float (shoelace formula)
  - [ ] sortPoints produces TL/TR/BR/BL ordering
  - [ ] distance returns min point-to-point when direction is null
  - [ ] getTransformedRegion produces Bitmap of height=textHeight with correct perspective
  - [ ] Project compiles

  **QA Scenarios**:
  ```
  Scenario: Quadrilateral geometry calculations
    Tool: Bash (unit test)
    Steps:
      1. Create Quadrilateral with known points: (0,0), (10,0), (10,5), (0,5)
      2. Assert: area == 50f (approximately)
      3. Assert: center == (5, 2.5)
      4. Assert: aspectRatio > 1 (wider than tall)
      5. Assert: fontSize > 0
    Expected Result: All geometry calculations correct
    Evidence: .sisyphus/evidence/task-4-geometry.txt

  Scenario: sortPoints produces consistent ordering
    Tool: Bash (unit test)
    Steps:
      1. Create Quadrilateral with shuffled points
      2. Call sortPoints
      3. Assert: first point is top-left (min x + min y)
      4. Assert: points are in clockwise order
    Expected Result: Consistent TL/TR/BR/BL ordering
    Evidence: .sisyphus/evidence/task-4-sortpoints.txt
  ```

  **Commit**: YES (groups with Task 5)
  - Message: `feat(translation): implement Quadrilateral geometry methods`
  - Files: `app/src/main/java/.../translation/data/Quadrilateral.kt`

- [ ] 5. Geometry utilities

  **What to do**:
  - Create `translation/util/GeometryUtils.kt`:
    - `fun polygonDistance(ptsA: List<PointF>, ptsB: List<PointF>): Float` — minimum distance between two convex polygons (min of all edge-edge and point-edge distances)
    - `fun shoelaceArea(points: List<PointF>): Float` — polygon area via shoelace formula
    - `fun convexHull(points: List<PointF>): List<PointF>` — Graham scan or Andrew's monotone chain
    - `fun pointToSegmentDistance(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float`
    - `fun segmentToSegmentDistance(...): Float`
    - `fun midpoint(a: PointF, b: PointF): PointF`
    - `fun euclideanDistance(a: PointF, b: PointF): Float`
    - `fun normalize(vec: PointF): PointF` — unit vector
    - `fun dot(a: PointF, b: PointF): Float`
    - `fun norm(vec: PointF): Float` — L2 norm
  - Create `translation/util/ImageUtils.kt`:
    - `fun letterbox(bitmap: Bitmap, targetSize: Int, stride: Int = 64): LetterboxResult` — scale to fit within targetSize keeping aspect ratio, pad bottom+right with black. Returns (paddedBitmap, ratio, dw, dh).
    - `data class LetterboxResult(val bitmap: Bitmap, val ratio: Float, val dw: Int, val dh: Int)`
    - `fun resizeBitmap(bitmap: Bitmap, width: Int, height: Int): Bitmap` — using Bitmap.createScaledBitmap
  - Create `translation/util/OpenCVUtils.kt`:
    - `fun ensureOpenCVLoaded(): Boolean` — calls OpenCVLoader.initDebug() or checks static init
    - `fun bitmapToMat(bitmap: Bitmap): Mat` — convert Android Bitmap to OpenCV Mat
    - `fun matToBitmap(mat: Mat): Bitmap` — convert OpenCV Mat to Android Bitmap

  **Must NOT do**:
  - No shapely/numpy dependencies
  - No unnecessary abstractions

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES (with Tasks 3, 4, 6, but depends on Task 4 for Quadrilateral reference)
  - **Parallel Group**: Wave 1
  - **Blocks**: Tasks 7, 8, 9
  - **Blocked By**: Tasks 0, 4

  **References**:
  - Python `utils/generic.py:653-750` — quadrilateral_can_merge_region: uses Polygon.distance (shapely) → need polygonDistance()
  - Python `detection/ctd_utils/utils/imgproc_utils.py` — letterbox() algorithm
  - Python `utils/generic.py:384-410` — dist(), midpoint(), norm() helpers

  **Acceptance Criteria**:
  - [ ] 3 files exist in `translation/util/`
  - [ ] polygonDistance returns 0 for overlapping polygons, positive for separated
  - [ ] shoelaceArea matches known polygon areas
  - [ ] letterbox produces correctly padded bitmap with recorded dw/dh
  - [ ] OpenCV bitmap↔Mat conversion works
  - [ ] Project compiles

  **QA Scenarios**:
  ```
  Scenario: Polygon distance calculation
    Tool: Bash (unit test)
    Steps:
      1. Two non-overlapping squares: [(0,0),(1,0),(1,1),(0,1)] and [(3,0),(4,0),(4,1),(3,1)]
      2. Call polygonDistance
      3. Assert: distance == 2.0f (gap between them)
    Expected Result: Correct polygon distance
    Evidence: .sisyphus/evidence/task-5-polydist.txt

  Scenario: Letterbox padding
    Tool: Bash (instrumented test)
    Steps:
      1. Create 800x600 Bitmap
      2. Call letterbox(bitmap, 1024)
      3. Assert: result.bitmap.width == 1024, result.bitmap.height == 1024
      4. Assert: dw + dh > 0 (some padding added)
    Expected Result: Correct letterbox with padding
    Evidence: .sisyphus/evidence/task-5-letterbox.txt
  ```

  **Commit**: YES (groups with Task 4)
  - Message: `feat(translation): add geometry and image utilities`
  - Files: `app/src/main/java/.../translation/util/*.kt`

- [ ] 6. Character set / dictionary loader

  **What to do**:
  - Create `assets/alphabet-all-v7.txt` — copy the 4666-character dictionary from Python project at `D:\manga-image-translator\manga-image-translator\models\ocr\alphabet-all-v7.txt`
  - Create `translation/ocr/OcrDictionary.kt`:
    - Loads dictionary from assets: `fun load(context: Context): List<String>`
    - Caches loaded dictionary in memory
    - Special token constants: `const val PAD = 0`, `const val START = 1`, `const val END = 2`, `const val SEP = 3`, `const val UNK = 4`, `const val SPACE = 5`
    - `fun decodeTokenIds(ids: IntArray): String` — maps token IDs to characters, handles \<S\> (skip), \</S\> (stop), \<SP\> (→space), other special tokens (skip)
    - `val size: Int` — dictionary size (4666)

  **Must NOT do**:
  - No hardcoded dictionary in Kotlin code (load from file)
  - No network download of dictionary (always bundled in assets)

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES (with Tasks 3, 4, 5)
  - **Parallel Group**: Wave 1
  - **Blocks**: Task 9
  - **Blocked By**: Task 1 (assets directory setup)

  **References**:
  - `D:\manga-image-translator\manga-image-translator\models\ocr\alphabet-all-v7.txt` — 4666-character source file
  - Python `ocr/model_48px.py` line 121-140 — token decoding: <S> skip, </S> break, <SP> → ' '
  - Python OCR research: token positions PAD=0, <S>=1, </S>=2, <SEP>=3, <UNK>=4, <SP>=5

  **Acceptance Criteria**:
  - [ ] `assets/alphabet-all-v7.txt` exists with 4666 lines
  - [ ] OcrDictionary loads from assets and caches
  - [ ] decodeTokenIds correctly maps token IDs to text
  - [ ] Special tokens handled: \<S\> skipped, \</S\> stops, \<SP\> → space
  - [ ] dictionary.size == 4666

  **QA Scenarios**:
  ```
  Scenario: Dictionary loading
    Tool: Bash (instrumented test)
    Steps:
      1. Call OcrDictionary.load(context)
      2. Assert: size == 4666
      3. Assert: dictionary[1] == "<S>"
      4. Assert: dictionary[5] == "<SP>"
    Expected Result: Dictionary loads correctly from assets
    Evidence: .sisyphus/evidence/task-6-dict.txt

  Scenario: Token decoding
    Tool: Bash (unit test)
    Steps:
      1. Create OcrDictionary with test entries
      2. Call decodeTokenIds(intArrayOf(1, 65, 66, 2)) — <S>, 'A', 'B', </S>
      3. Assert: result == "AB" (start/end tokens stripped)
    Expected Result: Correct text decoding
    Evidence: .sisyphus/evidence/task-6-decode.txt
  ```

  **Commit**: YES
  - Message: `feat(translation): add OCR dictionary loader`
  - Files: `app/src/main/java/.../translation/ocr/OcrDictionary.kt`, `app/src/main/assets/alphabet-all-v7.txt`

- [ ] 7. DefaultTextlineMerger — Union-Find + Kruskal MST algorithm

  **What to do**:
  - Create `translation/merge/DefaultTextlineMerger.kt` — implements TextlineMerger interface
  - Create `translation/merge/UnionFind.kt` — classic Union-Find with path compression + union by rank
  - Create `translation/merge/MergePredicates.kt`:
    - `fun quadrilateralCanMergeRegion(a: Quadrilateral, b: Quadrilateral, ...): Boolean` — full merge predicate from Python, with all the axis-aligned/non-axis-aligned branches
    - Parameters: ratio=1.9, discardConnectionGap=2, charGapTolerance, charGapTol2, fontSizeRatioTol, aspectRatioTol
    - Override values for coverage mode: aspectRatioTol=1.3, fontSizeRatioTol=2, charGapTol=1, charGapTol2=3
  - Create `translation/merge/MstSplitter.kt`:
    - `fun splitTextRegion(bboxes: List<Quadrilateral>, indices: Set<Int>, width: Int, height: Int, gamma: Float = 0.5f, sigma: Float = 2f): List<Set<Int>>` — recursive MST split algorithm
    - Case 1 node: return as-is
    - Case 2 nodes: check distance < (1+gamma)*max(fontSize) AND angleDiff < 0.2*PI
    - Case 3+ nodes: build MST via Kruskal, analyze heaviest edge, split or merge based on statistics
  - `merge()` implementation:
    1. Build adjacency graph via quadrilateralCanMergeRegion
    2. Find connected components via BFS
    3. Split each component via splitTextRegion
    4. For each region: vote direction, sort, compute colors
    5. Construct TextBlock objects
  - Direction voting: majority vote, tie-break by most extreme aspect_ratio
  - Sort: horizontal → by centroid.y ascending, vertical → by centroid.x descending
  - TextBlock: fontSize=min, angle=mean-90° (abs<3°→0), prob=exp(area-weighted log-prob mean)

  **Must NOT do**:
  - No networkx or shapely dependency
  - No Python interop
  - No is_valuable_text filter (that's pipeline-level, not merge-level)

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES (with Tasks 8, 9, after Tasks 4+5)
  - **Parallel Group**: Wave 2
  - **Blocks**: Tasks 10, 11
  - **Blocked By**: Tasks 0, 4, 5

  **References**:
  - Python `textline_merge/__init__.py` — dispatch(), merge_bboxes_text_region(), split_text_region()
  - Python `utils/generic.py:653-750` — quadrilateral_can_merge_region() full predicate logic
  - Python `utils/generic.py:536-599` — Quadrilateral.distance_impl() pattern matching
  - Python `utils/textblock.py:39-200` — TextBlock construction, angle, font_size, prob calculation
  - Textline merge research (from explore agent): complete algorithm with pseudocode, all constants

  **Acceptance Criteria**:
  - [ ] `DefaultTextlineMerger.kt` implements TextlineMerger interface
  - [ ] `UnionFind.kt` works correctly (path compression + rank)
  - [ ] `MergePredicates.kt` has quadrilateralCanMergeRegion with all branches
  - [ ] `MstSplitter.kt` handles 1-node, 2-node, 3+node cases
  - [ ] merge() returns List\<TextBlock\> with correct font_size, angle, prob, colors
  - [ ] Project compiles

  **QA Scenarios**:
  ```
  Scenario: Union-Find correctness
    Tool: Bash (unit test)
    Steps:
      1. Create UnionFind with 5 elements
      2. Union(0,1), Union(2,3), Union(1,3)
      3. Assert: find(0) == find(2) (same component)
      4. Assert: find(0) != find(4) (different component)
    Expected Result: Union-Find with path compression works
    Evidence: .sisyphus/evidence/task-7-unionfind.txt

  Scenario: Merge simple text regions
    Tool: Bash (unit test)
    Steps:
      1. Create 3 Quadrilaterals: 2 close horizontal, 1 far away
      2. Call merge(textlines, 800, 600)
      3. Assert: result.size == 2 (two regions: close pair + singleton)
      4. Assert: first region has 2 textlines, sorted by y
    Expected Result: Correct merging of nearby textlines
    Evidence: .sisyphus/evidence/task-7-merge.txt

  Scenario: MST split of distant textlines
    Tool: Bash (unit test)
    Steps:
      1. Create 4 Quadrilaterals: 2 pairs separated by large gap
      2. Call merge
      3. Assert: result.size == 2 (split into two regions)
    Expected Result: MST correctly splits distant groups
    Evidence: .sisyphus/evidence/task-7-split.txt
  ```

  **Commit**: YES
  - Message: `feat(translation): implement DefaultTextlineMerger with Union-Find and MST`
  - Files: `app/src/main/java/.../translation/merge/*.kt`

- [ ] 8. CtdTextDetector — ONNX CTD model + DBNet postprocessing

  **What to do**:
  - Create `translation/detection/CtdTextDetector.kt` — implements TextDetector interface
  - `prepare()`: load ONNX model via ModelDownloadManager + OnnxSessionManager, create OrtSession
  - `release()`: close OrtSession
  - `detect(bitmap, config)`:
    1. **Preprocess**: letterbox to 1024×1024, convert to NCHW [0,1] float32 tensor
    2. **Inference**: session.run() → 3 outputs (blks, mask, lines_map)
    3. **Crop padding**: mask and lines_map sliced to remove letterbox padding
    4. **Postprocess mask**: squeeze, *255, uint8, resize to original image dims via Imgproc.resize
    5. **DBNet decode**:
       - Take lines_map channel 0 (shrink_map)
       - Binarize: > 0.3
       - findContours on binary mask (Imgproc.findContours)
       - For each contour (up to 1000):
         - minAreaRect → 4 rotated rect points (Imgproc.minAreaRect)
         - Compute score: box_score_fast (mean of pred values within contour polygon — Imgproc.fillPoly + Core.mean)
         - Unclip: expand polygon by area*1.5/perimeter (approximate via centroid scaling or mask dilation)
         - minAreaRect of expanded → 4 points
         - Scale points to original image dimensions
       - Filter: only keep boxes with score > 0.6
    6. **Create Quadrilaterals**: sortPoints() for each, set text="", prob=score
    7. **Mask resize**: cv2.resize equivalent via Imgproc.resize
    8. **Return**: DetectionResult(textlines, rawMask=mask, mask=null)
  - Constants: binary_thresh=0.3, box_thresh=0.6, unclip_ratio=1.5, max_candidates=1000, input_size=1024, stride=64

  **Must NOT do**:
  - No YOLO detection decoding (blks output is unused)
  - No refine_mask (currently no-op in Python)
  - No image rotation/inversion/gamma (pipeline-level features, not detector-level)

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES (with Tasks 7, 9, after prerequisites)
  - **Parallel Group**: Wave 2
  - **Blocks**: Tasks 10, 11
  - **Blocked By**: Tasks 0, 1, 2, 3, 4, 5

  **References**:
  - Python `detection/ctd.py` — ComicTextDetector._infer(): full pipeline
  - Python `detection/ctd_utils/basemodel.py` — TextDetBaseDNN: blobFromImage(1/255.0), 3 outputs
  - Python `detection/ctd_utils/utils/db_utils.py` — SegDetectorRepresenter: binarize(0.3), findContours, minAreaRect, unclip, box_score_fast
  - Python `detection/ctd_utils/utils/imgproc_utils.py` — letterbox()
  - CTD detection research (from explore agent): complete data flow, tensor shapes, all constants

  **Acceptance Criteria**:
  - [ ] `CtdTextDetector.kt` implements TextDetector interface
  - [ ] prepare() loads model and creates OrtSession
  - [ ] detect() returns DetectionResult with Quadrilaterals and mask Bitmap
  - [ ] Letterbox padding correctly removed from outputs
  - [ ] DBNet decoding: binarize > 0.3, findContours, minAreaRect, score > 0.6
  - [ ] Unclip approximation produces expanded polygons
  - [ ] Project compiles

  **QA Scenarios**:
  ```
  Scenario: Detection preprocessing
    Tool: Bash (instrumented test)
    Steps:
      1. Create 800x600 test Bitmap
      2. Call letterbox(bitmap, 1024)
      3. Assert: result.bitmap size == 1024x1024
      4. Assert: dw + dh > 0 (padded)
      5. Convert to NCHW tensor, assert shape [1,3,1024,1024]
    Expected Result: Correct letterbox and tensor conversion
    Evidence: .sisyphus/evidence/task-8-preprocess.txt

  Scenario: DBNet decode on synthetic mask
    Tool: Bash (instrumented test)
    Steps:
      1. Create synthetic shrink_map (white rectangles on black)
      2. Run DBNet decode (binarize, findContours, minAreaRect, score, filter)
      3. Assert: returns correct number of Quadrilaterals
      4. Assert: each quad has 4 sorted points and prob > 0.6
    Expected Result: DBNet decode works on synthetic data
    Evidence: .sisyphus/evidence/task-8-dbnet.txt
  ```

  **Commit**: YES
  - Message: `feat(translation): implement CtdTextDetector with ONNX and DBNet`
  - Files: `app/src/main/java/.../translation/detection/*.kt`

- [ ] 9. Model48pxTextRecognizer — ONNX OCR + greedy decoding + color extraction

  **What to do**:
  - Create `translation/ocr/Model48pxTextRecognizer.kt` — implements TextRecognizer interface
  - `prepare()`: load ONNX model via ModelDownloadManager + OnnxSessionManager, load dictionary
  - `release()`: close OrtSession
  - `recognize(bitmap, textlines, config)`:
    1. **Text direction**: use each Quadrilateral's direction field (set by sortPoints) — simplified vs Python's graph approach
    2. **Perspective crop**: for each textline, call getTransformedRegion(bitmap, direction, 48) → (48, W, 3) Bitmap
    3. **Batch assembly**: sort by width ascending, chunk into groups of 16, zero-pad to maxWidth aligned to 4
    4. **Normalize**: (pixel - 127.5) / 127.5 → [-1, 1], transpose NHWC→NCHW
    5. **ONNX inference**: session.run() with [N, 3, 48, W_padded] input. Also pass `widths` array for encoder mask computation.
       - **Greedy decode** (NOT beam search for now): for each sample in batch, auto-regressive loop:
         - Start token = 1
         - At each step: run decoder forward, pick argmax token
         - Stop at end token (2) or max_seq_length=255
       - Extract: char_ids, prob, fg_pred[L,3], bg_pred[L,3], fg_ind_pred[L,2], bg_ind_pred[L,2]
    6. **Decode text**: OcrDictionary.decodeTokenIds(char_ids)
    7. **Color extraction**: per-character running average with indicator gating
       - has_fg = fg_ind[1] > fg_ind[0]
       - has_bg = bg_ind[1] > bg_ind[0]
       - If has_fg: accumulate fg_pred*255
       - If has_bg: accumulate bg_pred*255
       - Else: use fg_pred*255 as bg fallback
       - Final: clamp to [0,255]
    8. **Update textlines**: set text, probability, fgColor, bgColor for each Quadrilateral
    9. **Return**: updated List\<Quadrilateral\>
  - Constants: TEXT_HEIGHT=48, MAX_CHUNK_SIZE=16, MAX_SEQ_LENGTH=255, PROB_THRESHOLD=0.2

  **Must NOT do**:
  - No beam search (implement greedy decode first, beam search is later optimization)
  - No text direction graph (use quad.direction directly)
  - No multi-page context
  - No dictionary download (always bundled in assets)

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES (with Tasks 7, 8, after prerequisites)
  - **Parallel Group**: Wave 2
  - **Blocks**: Tasks 10, 11
  - **Blocked By**: Tasks 0, 1, 2, 3, 4, 5, 6

  **References**:
  - Python `ocr/model_48px.py` — Model48pxOCR._infer(): full pipeline, batch assembly, normalization
  - Python `ocr/common.py` — CommonOCR: text direction detection, recognize flow
  - Python `utils/generic.py:521-599` — get_transformed_region: perspective crop
  - Python `ocr/model_48px.py:121-175` — decode + color extraction: AvgMeter, indicator gating
  - OCR research (from explore agent): complete data flow, tensor shapes, character set, all constants

  **Acceptance Criteria**:
  - [ ] `Model48pxTextRecognizer.kt` implements TextRecognizer interface
  - [ ] prepare() loads model + dictionary
  - [ ] recognize() returns List\<Quadrilateral\> with text, probability, fgColor, bgColor populated
  - [ ] Perspective crop via getTransformedRegion works for both h and v directions
  - [ ] Batch assembly: sort by width, chunk=16, pad to align-4
  - [ ] Greedy decode produces text from token IDs
  - [ ] Color extraction: running average with indicator gating
  - [ ] Project compiles

  **QA Scenarios**:
  ```
  Scenario: Batch assembly
    Tool: Bash (unit test)
    Steps:
      1. Create list of region widths: [30, 50, 20, 60]
      2. Sort ascending: [20, 30, 50, 60]
      3. Chunk (max 16), align max width to 4: 60→60 (already aligned)
      4. Assert: batch shape [4, 48, 60, 3]
    Expected Result: Correct batch assembly with padding
    Evidence: .sisyphus/evidence/task-9-batch.txt

  Scenario: Color extraction logic
    Tool: Bash (unit test)
    Steps:
      1. Mock fg_pred=[[0.8,0.2,0.1]], fg_ind=[[0.2,0.8]], bg_ind=[[0.7,0.3]]
      2. Run color extraction
      3. Assert: fg = (204, 51, 26) — 0.8*255≈204, 0.2*255≈51, 0.1*255≈26
      4. Assert: bg uses fg as fallback (has_bg=false → bg_ind[0]>bg_ind[1])
    Expected Result: Correct color extraction with indicator gating
    Evidence: .sisyphus/evidence/task-9-color.txt
  ```

  **Commit**: YES
  - Message: `feat(translation): implement Model48pxTextRecognizer with ONNX and greedy decode`
  - Files: `app/src/main/java/.../translation/ocr/Model48pxTextRecognizer.kt`

- [ ] 10. Koin DI module update — replace NoOp stubs with factory pattern

  **What to do**:
  - Update `translation/di/TranslationModule.kt`:
    - Replace `single<TextDetector> { NoOpTextDetector() }` with factory pattern:
      ```kotlin
      single<TextDetector> {
          when (get<TranslationConfig>().detector.detector) {
              DetectorType.CTD -> CtdTextDetector(get(), get(), get())
              DetectorType.NONE -> NoOpTextDetector()
              else -> NoOpTextDetector() // fallback
          }
      }
      ```
    - Same pattern for TextRecognizer, TextlineMerger
    - Add new single definitions:
      - `single { OnnxSessionManager() }`
      - `single { ModelDownloadManager(get()) }` (needs Context)
      - `single { OcrDictionary }`
    - Keep NoOp stubs for: Inpainter, TextRenderer, Translator, MaskRefiner (not implemented yet)
  - Update `translation/di/KoinInitializer.kt` if needed
  - Ensure androidContext is available for ModelDownloadManager

  **Must NOT do**:
  - No changes to existing ViewModel/Screen
  - No koin-androidx-compose
  - Don't remove NoOp implementations (still needed for unimplemented modules)

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: NO (depends on Tasks 7, 8, 9)
  - **Parallel Group**: Wave 2 (after T7/T8/T9)
  - **Blocks**: Task 11
  - **Blocked By**: Tasks 7, 8, 9

  **References**:
  - `app/src/main/java/.../translation/di/TranslationModule.kt` — Current Koin module
  - `app/src/main/java/.../translation/di/KoinInitializer.kt` — Current Koin init
  - `app/src/main/java/.../translation/data/config/DetectorType.kt` — Enum for factory dispatch

  **Acceptance Criteria**:
  - [ ] Koin module provides CtdTextDetector when config.detector == CTD
  - [ ] Koin module provides Model48pxTextRecognizer when config.ocr == MODEL_48PX
  - [ ] Koin module provides DefaultTextlineMerger
  - [ ] NoOp stubs still provided for Inpainter, TextRenderer, Translator, MaskRefiner
  - [ ] OnnxSessionManager and ModelDownloadManager provided
  - [ ] Koin module loads without errors

  **QA Scenarios**:
  ```
  Scenario: Koin module loads with real implementations
    Tool: Bash (unit test with koin-test)
    Steps:
      1. Start Koin with updated TranslationModule
      2. Assert: get<TextDetector>() is CtdTextDetector (when config=CTD)
      3. Assert: get<TextRecognizer>() is Model48pxTextRecognizer
      4. Assert: get<TextlineMerger>() is DefaultTextlineMerger
      5. Assert: get<Inpainter>() is NoOpInpainter (still stub)
    Expected Result: Real implementations injected for implemented modules
    Evidence: .sisyphus/evidence/task-10-koin.txt
  ```

  **Commit**: YES
  - Message: `feat(translation): update Koin DI with real implementations`
  - Files: `app/src/main/java/.../translation/di/*.kt`

- [ ] 11. Unit tests

  **What to do**:
  - Create `app/src/test/java/.../translation/data/QuadrilateralGeometryTest.kt`:
    - Test area: square, rectangle, rotated quadrilateral
    - Test center: known points
    - Test angle: horizontal vs vertical quads
    - Test aspectRatio: wide vs tall
    - Test fontSize: min of structure vectors
    - Test sortPoints: shuffled points → TL/TR/BR/BL
    - Test distance: null direction → min point distance
  - Create `app/src/test/java/.../translation/merge/DefaultTextlineMergerTest.kt`:
    - Test UnionFind: union, find, path compression
    - Test quadrilateralCanMergeRegion: close quads merge, far quads don't
    - Test splitTextRegion: 1-node, 2-node, 3+node cases
    - Test merge: simple 2-region merge, distant groups split
    - Test direction voting: majority, tie-break
    - Test TextBlock construction: font_size=min, angle=mean-90°, prob=exp(...)
  - Create `app/src/test/java/.../translation/util/GeometryUtilsTest.kt`:
    - Test polygonDistance: overlapping (0), separated (positive)
    - Test shoelaceArea: known polygons
    - Test pointToSegmentDistance: perpendicular, endpoint cases
    - Test letterbox: correct padding, ratio, dw, dh
  - Create `app/src/test/java/.../translation/ocr/OcrDictionaryTest.kt`:
    - Test dictionary loading (instrumented)
    - Test decodeTokenIds: start/end/space tokens
  - Create `app/src/test/java/.../translation/model/ModelDownloadManagerTest.kt`:
    - Test SHA256 verification
    - Test ensureModel with existing valid file (skip download)
    - Test ensureModel with corrupted file (re-download)

  **Must NOT do**:
  - No instrumented tests for ONNX inference (needs real model files)
  - No large bitmaps in tests

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: NO (depends on all implementation tasks)
  - **Parallel Group**: Wave 3
  - **Blocks**: None
  - **Blocked By**: Tasks 7, 8, 9, 10

  **References**:
  - Tasks 4, 5, 7, 8, 9 output — Implementation code to test
  - `app/src/test/java/.../translation/data/QuadrilateralTest.kt` — Existing test pattern
  - `app/src/test/java/.../translation/data/TranslationConfigTest.kt` — Existing test pattern

  **Acceptance Criteria**:
  - [ ] 5 test files exist
  - [ ] `./gradlew :app:testDebugUnitTest` passes with 0 failures
  - [ ] Coverage: geometry, merge, dictionary, model manager

  **QA Scenarios**:
  ```
  Scenario: All unit tests pass
    Tool: Bash
    Steps:
      1. Run: ./gradlew :app:testDebugUnitTest
      2. Assert: BUILD SUCCESSFUL, 0 test failures
    Expected Result: All Plan 2 unit tests pass
    Evidence: .sisyphus/evidence/task-11-tests.txt
  ```

  **Commit**: YES
  - Message: `test(translation): add unit tests for Plan 2 modules`
  - Files: `app/src/test/java/.../translation/**/*.kt`

---

## Final Verification Wave (MANDATORY — after ALL implementation tasks)

- [ ] F1. **Plan Compliance Audit** — `oracle`
  Read the plan end-to-end. For each "Must Have": verify implementation exists (read file, check compile). For each "Must NOT Have": search codebase for forbidden patterns — reject with file:line if found. Check evidence files exist in .sisyphus/evidence/. Compare deliverables against plan.
  Output: `Must Have [N/N] | Must NOT Have [N/N] | Tasks [N/N] | VERDICT: APPROVE/REJECT`

- [ ] F2. **Code Quality Review** — `unspecified-high`
  Run `tsc --noEmit` equivalent (kotlinc check) + lint. Review all changed files for: `as any`/`@Suppress`, empty catches, `Log.d` in prod (should use timber or remove), commented-out code, unused imports. Check AI slop: excessive comments, over-abstraction, generic names.
  Output: `Build [PASS/FAIL] | Lint [PASS/FAIL] | Files [N clean/N issues] | VERDICT`

- [ ] F3. **Integration Smoke Test** — `unspecified-high`
  Start from clean state. Verify Koin module loads without crash. Verify each module can be instantiated. If ONNX models available, run detection+OCR on test image. If not, verify code paths are correct.
  Output: `Koin [PASS/FAIL] | Models [PASS/FAIL/N/A] | Pipeline [PASS/FAIL] | VERDICT`

- [ ] F4. **Scope Fidelity Check** — `deep`
  For each task: read "What to do", read actual diff. Verify 1:1 — everything in spec was built (no missing), nothing beyond spec was built (no creep). Check "Must NOT do" compliance. Detect cross-task contamination. Flag unaccounted changes.
  Output: `Tasks [N/N compliant] | Contamination [CLEAN/N issues] | VERDICT`

---

## Commit Strategy

- **Task 0**: `fix(translation): add missing data models from Plan 1` — translation/data/*.kt
- **Task 1**: `build: fix version catalog and add ONNX/OpenCV dependencies` — gradle/libs.versions.toml, app/build.gradle.kts
- **Task 2**: `feat(translation): add model download manager` — translation/model/*.kt
- **Task 3**: `feat(translation): add ONNX infrastructure layer` — translation/onnx/*.kt
- **Task 4+5**: `feat(translation): add Quadrilateral geometry methods and utilities` — translation/data/Quadrilateral.kt, translation/util/*.kt
- **Task 6**: `feat(translation): add OCR dictionary loader` — translation/ocr/Dictionary.kt, assets/alphabet-all-v7.txt
- **Task 7**: `feat(translation): implement DefaultTextlineMerger` — translation/merge/*.kt
- **Task 8**: `feat(translation): implement CtdTextDetector` — translation/detection/*.kt
- **Task 9**: `feat(translation): implement Model48pxTextRecognizer` — translation/ocr/*.kt
- **Task 10**: `feat(translation): update Koin DI with real implementations` — translation/di/*.kt
- **Task 11**: `test(translation): add unit tests for Plan 2 modules` — test/**/*.kt

---

## Success Criteria

### Verification Commands
```bash
./gradlew :app:compileDebugKotlin    # Expected: BUILD SUCCESSFUL (JDK 17-21)
./gradlew :app:testDebugUnitTest     # Expected: All tests pass, 0 failures
```

### Final Checklist
- [ ] All "Must Have" items present
- [ ] All "Must NOT Have" items absent
- [ ] CtdTextDetector replaces NoOpTextDetector in Koin
- [ ] Model48pxTextRecognizer replaces NoOpTextRecognizer in Koin
- [ ] DefaultTextlineMerger replaces NoOpTextlineMerger in Koin
- [ ] Model download manager functional
- [ ] Quadrilateral has all geometry methods implemented
- [ ] All unit tests pass
