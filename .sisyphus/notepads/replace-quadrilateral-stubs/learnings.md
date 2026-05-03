# Quadrilateral.kt Implementation Learnings

## Build Infrastructure Fixes
- Gradle wrapper had to be upgraded from 8.13 to 9.5.0 because Java 25 is not supported by Gradle 8.x's bundled Kotlin compiler
- OpenCV Maven artifact in `libs.versions.toml` was wrong: `org.opencv:opencv-android:4.9.0` doesn't exist. Correct is `org.opencv:opencv:4.9.0` (available on Maven Central since OpenCV 4.9.0)
- Both fixes were necessary to compile the project

## OpenCV API Notes
- `Imgproc.getPerspectiveTransform(Mat, Mat)` works with CV_32F matrices (4x2)
- `Mat.put(Int, Int, FloatArray!)` takes a flat row-major array for filling matrix data
- `Utils.bitmapToMat(Bitmap, Mat)` converts bitmap to OpenCV Mat
- `Utils.matToBitmap(Mat, Bitmap)` requires pre-allocated Bitmap (no single-arg convenience overload in this OpenCV version)
- `Core.rotate(Mat, Mat, Int)` for post-warp rotation of vertical text

## Pre-existing Issues (unrelated to Quadrilateral.kt)
- `KoinInitializer.kt` - type mismatch (KoinApplication vs Koin)
- `OnnxConstants.kt` - unresolved GraphOptimizationLevel
- `TensorConverter.kt` - unresolved shape, type inference issues
- `TranslationPipeline.kt` - List vs MutableList assignment mismatches

## Edge Cases Handled
- Empty points list: center, boundingBox return default values
- < 4 points: structure, area, angle, aspectRatio, fontSize return safe defaults
- Degenerate quads (fontSize=0): distance returns 0f for pattern match branch
- textHeight <= 0: getTransformedRegion returns original bitmap
- Degenerate quads in getTransformedRegion: minimum output dimension is 1px
