# Task 3 - ONNX Infrastructure: Decisions

## Architecture
- `OnnxSessionManager` as a singleton `object` — single `OrtEnvironment` shared across model sessions
- `TensorConverter` as a singleton `object` — stateless utility, no instance needed
- `OnnxConstants.kt` uses top-level `const val` and function rather than a class wrapper

## Thread Safety
- `OnnxSessionManager` uses `@Synchronized` on all access to the tracked sessions list
- Session creation runs on `Dispatchers.Default` via `withContext`

## Tensor Conversion
- `FloatBuffer.wrap(floatArray)` — simplest way to create a FloatBuffer from our computed float array
- NCHW format: channel-first layout (R, G, B) with shape [1, 3, H, W]
- `Bitmap.createScaledBitmap` for resize (bilinear filtering with `filter=true`)
- Resource management: OnnxTensor/OnnxValue NOT closed inside converter methods — caller manages lifecycle

## Method Design
- `extractFloatArray` returns `Array<FloatArray>` for 2D output (e.g., detection boxes)
- `extractFloatBuffer` returns raw `FloatBuffer` for 1D/flat output
- Two normalization variants: `bitmapToNCHWTensor01` and `bitmapToNCHWTensorMinusOneOne`
