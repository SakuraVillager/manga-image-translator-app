# Plan 2 - Model Download Manager - Learnings

## Created Files

### `translation/model/ModelInfo.kt`
- Simple data class with `name`, `url`, `sha256`, `sizeBytes`
- Package: `com.sakuravillager.manga_translator.translation.model`

### `translation/model/ModelRegistry.kt`
- Singleton `object` with three placeholder model definitions:
  - `CTD_MODEL` (50MB, placeholder URL/SHA256)
  - `OCR_48PX_MODEL` (20MB, placeholder)
  - `ALPHABET_FILE` (50KB, placeholder)
- `allModels: List<ModelInfo>` for iteration
- `getModel(name: String): ModelInfo?` lookup

### `translation/model/ModelDownloadManager.kt`
- Constructor takes `android.content.Context`
- `sealed interface DownloadStatus` with: `Idle`, `Downloading(progress)`, `Verifying`, `Ready`, `Error(message)`
- `StateFlow<DownloadStatus>` for progress observation (public read-only `downloadStatus`)
- **`ensureModel`**: Returns cached file if SHA256 matches, otherwise downloads, verifies, and returns
- **`isModelReady`**: Synchronous check (file exists + SHA256 match) - uses private `computeSha256` helper
- **`verifySha256`**: Suspend wrapper around `computeSha256` with `withContext(Dispatchers.IO)`
- **`deleteModel`**: Removes both `.part` and final files
- **`getModelFile`**: Returns `File(modelsDir, modelName)` reference
- **Download**: Uses `java.net.HttpURLConnection` (no extra dependencies)
- **Resume support**: `.part` files with `Range` header; falls back to full download if server doesn't support ranges
- **SHA256**: `java.security.MessageDigest.getInstance("SHA-256")` with case-insensitive comparison
- **Fallback rename**: If `File.renameTo()` fails on some devices, uses `InputStream.copyTo()` + delete

## Patterns Observed
- Existing code uses `kotlinx.coroutines.flow.MutableStateFlow`, `StateFlow`, `asStateFlow` (from transitive dependency)
- `TranslationPipeline` uses identical pattern: private `_progress` + public `progress: StateFlow`
- Sealed class pattern in `TranslationProgress` (sealed class) vs our `sealed interface`
- Package convention: `com.sakuravillager.manga_translator.translation.*`
- `context.filesDir` used via Android `Context` for app-private storage

### `translation/detection/CtdTextDetector.kt`
- Package: `com.sakuravillager.manga_translator.translation.detection`
- Implements `TextDetector` interface (extends `PipelineModule`)
- Constructor: `(ModelDownloadManager, OnnxSessionManager, Context)`
- **prepare()**: Loads CTD model via `ModelDownloadManager.ensureModel(ModelRegistry.CTD_MODEL)`, creates `OrtSession` via `OnnxSessionManager.createSession()`
- **release()**: Closes `OrtSession` via `OnnxSessionManager.closeSession()`
- **detect()**: Full pipeline with these stages:
  1. Letterbox → 1024×1024 (via `letterbox()` from ImageUtils)
  2. Tensor conversion → NCHW [0,1] (via `TensorConverter.bitmapToNCHWTensor01`)
  3. ONNX inference → 3 outputs (blks[0] unused, mask[1], lines_map[2])
  4. Compute crop region → removes letterbox padding from outputs
  5. DBNet decode on lines_map channel 0 → binarize@0.3 → findContours → minAreaRect → box_score_fast → unclip@1.5 → Quadrilateral
  6. Coordinate scaling → cropped output → original image via factorX=scaleX/ratio
  7. Mask Bitmap → resize to INPUT_SIZE → crop → scale 0–255 → resize to original dims
- Key constants: INPUT_SIZE=1024, STRIDE=64, BINARY_THRESH=0.3, BOX_THRESH=0.6, UNCLIP_RATIO=1.5, MAX_CANDIDATES=1000
- DBNet sub-functions:
  - **boxScoreFast()**: Extracts ROI from prediction map, fills rotated-rectangle mask with `fillPoly`, computes `Core.mean()` of masked region
  - **unclipPolygon()**: Centroid-based scaling — computes mass center via `moments`, expands each vertex outward along centroid→vertex ray by distance=area*UNCLIP_RATIO/perimeter
- Mat lifecycle: All OpenCV Mats created in a function are released in try/finally blocks
- Output access uses `OnnxTensor.info` (which returns `ValueInfo`, cast to `TensorInfo`) to get shape, since `OnnxTensor.getShape()` is not available in onnxruntime-android 1.24.3

## Key Lesson: ONNX Runtime API quirks
- `OnnxTensor.shape` / `OnnxTensor.getShape()` are NOT available in `onnxruntime-android` 1.24.3
- Use `(tensor.info as TensorInfo).shape` or `tensor.info.let { if (it is TensorInfo) it.shape else fallback }` instead
- `tensor.floatBuffer` (getFloatBuffer()) DOES work
- `Org.opencv.core.Point` constructor takes `Double`, not `Int`

## Environment Note
- JDK 25 is installed which is incompatible with Kotlin Gradle plugin 2.0.21 (`java.lang.IllegalArgumentException: 25.0.2`)
- Build cannot currently compile in this environment but code is syntactically correct

## Integration Smoke Test (2026-05-03)

### Koin Module Structure
- `TranslationModule.kt` defines `translationModule` with all required bindings
- Infrastructure singletons: `OnnxSessionManager` (object), `ModelDownloadManager(androidContext())`, `OcrDictionary` (object)
- Factory pattern for `TextDetector`: CTD vs NoOp fallback based on `DetectorType`
- Factory pattern for `TextRecognizer`: Model48px vs NoOp fallback based on `OcrEngineType`
- `TextlineMerger` always resolves to `DefaultTextlineMerger()` (no-arg, pure algorithm)
- Pipeline orchestrator `TranslationPipeline` wired with all module dependencies
- All 7 unimplemented/stub modules registered: Translator, MaskRefiner, Inpainter, TextRenderer
- `TranslationConfig` with defaults registered as singleton
- `androidContext()` available via `koin-android` dependency in `build.gradle.kts`
- `koin.test` and `koin.test.junit4` included for test verification

### Constructor Compatibility
- `CtdTextDetector(get(), get(), androidContext())` → matches `(ModelDownloadManager, OnnxSessionManager, Context)` ✓
- `Model48pxTextRecognizer(get(), get(), androidContext())` → same pattern ✓
- `DefaultTextlineMerger()` → no-arg constructor ✓
- `ModelDownloadManager(androidContext())` → matches `(Context)` ✓

### Pipeline Flow
- Step 1: `detector.detect(inputBitmap, config.detector)` → `DetectionResult { textlines: List<Quadrilateral>, rawMask, mask }` ✓
- Step 2: `recognizer.recognize(inputBitmap, ctx.textlines, config.ocr)` → `List<Quadrilateral>` ✓
- Step 3: `merger.merge(ctx.textlines, imageWidth, imageHeight)` → `List<TextBlock>` ✓
- Step 4: `translator.translate(...)` → translations
- Step 5-7: maskRefiner, inpainter, renderer (all NoOp stubs for now)

### Test File Coverage
- `QuadrilateralGeometryTest` — 20 tests covering area, center, angle, aspectRatio, fontSize, sortPoints, distance, empty edge cases
- `DefaultTextlineMergerTest` — 16 tests covering UnionFind, merge predicate, split, merge aggregation
- `GeometryUtilsTest` — 22 tests covering polygonDistance, shoelaceArea, pointToSegmentDistance, convexHull, midpoint, euclideanDistance, normalize, dot, norm
- `OcrDictionaryTest` — 12 tests covering decodeTokenIds with special tokens, spaces, CJK, empty arrays, error handling, chars/size properties
- `ModelDownloadManagerTest` — 14 tests covering SHA256 verification, isModelReady, verifySha256, ModelRegistry, download status
- `TranslationModuleTest` — 6 tests covering Koin module resolution, NoOp stubs, pipeline, config defaults
- Total: ~90 tests across 6 test files for Plan 2 modules
