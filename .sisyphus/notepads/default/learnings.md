## 2026-05-03: TranslationPipeline + TextBlock fixes

### Changes made:
1. **TranslationPipeline.kt** - `prepare()` now calls ALL 7 modules (added merger, translator, maskRefiner, inpainter, renderer)
2. **TranslationPipeline.kt** - `release()` in finally block now releases ALL 7 modules
3. **TranslationPipeline.kt** - Added defensive `bitmap.copy(Bitmap.Config.ARGB_8888, false)` before `renderer.render()` to prevent aliasing
4. **TextBlock.kt** - `isHorizontal` now computed from direction (`direction != TextDirection.VERTICAL`)
5. **TextBlock.kt** - `isVertical` now computed from direction (`direction == TextDirection.VERTICAL`)
6. **TextBlock.kt** - `center` now computed from minRect (`PointF(r.centerX(), r.centerY())`)
7. **build.gradle.kts** - Fixed pre-existing `compilerOptions` -> `kotlinOptions` for Gradle 9.5.0 compatibility

### Build:
- `./gradlew :app:compileDebugKotlin` passes (BUILD SUCCESSFUL)
- Only warning is pre-existing OpenCVUtils.kt deprecation

### Notes:
- The build.gradle.kts issue was pre-existing (Kotlin 2.0.21 + Gradle 9.5.0). `compilerOptions` DSL not available in `android {}` block with this combo; replaced with `kotlinOptions { jvmTarget = "11" }`.

## 2026-05-05: AOT-GAN ONNX Inpainter

### Changes made:
1. **AotInpainter.kt** (new, 239 lines) — ONNX-based AOT-GAN inpainter implementing `Inpainter` interface
2. **ModelRegistry.kt** — Added `AOT_INPAINTING_MODEL` with export instructions
3. **InpainterConfig.kt** — Default changed from `LAMA_LARGE` to `AOT`
4. **TranslationModule.kt** — Conditional DI: AOT/LAMA_LARGE/LAMA_MPE → AotInpainter, SIMPLE_FILL/NONE → SimpleFillInpainter

### AotInpainter design:
- Follows same lifecycle pattern as CtdTextDetector (prepare → inpaint → release)
- Preprocessing: mask alignment, optional scale-down if > inpaintingSize, builds NCHW [1,4,H,W] tensor
  - Channel order: MASK first (0/1 thresholded at 127), then R/G/B normalized to [-1,1]
  - Matches Python's `torch.cat([mask, img], dim=1)` order
- Inference: dynamic input name detection via `sess.inputNames.iterator().next()`
- Postprocessing: clip output to [-1,1], denormalize via (val+1)*127.5, resize to original if scaled, blend with original mask
- Resource cleanup: 3-level try-finally nesting to ensure both `inputTensor` and `results` are closed even if `sess.run()` throws
- Thread-safe via `withContext(Dispatchers.Default)`

### Key differences from LaMa:
- AOT does NOT use FFT — fully ONNX-compatible
- AOT normalization is `/127.5 - 1.0` (not `/255.0`)
- AOT concat order is `cat([mask, img])` — mask channel FIRST

## P2-4: Model Download Progress UI
- Added Downloading and Error states to TranslationProgress sealed class
- Added cancelDownload() to ModelDownloadManager with connection tracking and cancellation flag
- Cancel: disconnects HttpURLConnection -> throws CancellationException -> pipeline returns Cancelled
- Wire download progress via ViewModel collecting DownloadStatus StateFlow (not pipeline) - simpler approach
- Added Downloading (with progress bar + cancel button) and Error UI branches in WorkspaceScreen.kt
- ModelDownloadManager is a Koin singleton, accessed via KoinJavaComponent.get() in ViewModel
- Build verified: :app:compileDebugKotlin SUCCESSFUL
