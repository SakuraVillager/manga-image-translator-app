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
