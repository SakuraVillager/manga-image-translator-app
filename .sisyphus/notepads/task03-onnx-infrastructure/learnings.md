# Task 3 - ONNX Infrastructure: Learnings

## Package Structure
- All source files under: `app/src/main/java/com/sakuravillager/manga_translator/`
- ONNX package: `com.sakuravillager.manga_translator.translation.onnx`
- ONNX Runtime dep: `com.microsoft.onnxruntime:onnxruntime-android:1.24.3` via version catalog

## ONNX Runtime API Notes
- `OrtEnvironment.getEnvironment()` returns a thread-safe singleton
- `OrtEnvironment.createSession(byte[], SessionOptions)` creates an `OrtSession`
- `OrtSession.SessionOptions` configures session behavior (optimization level, threads)
- `GraphOptimizationLevel.ORT_ENABLE_ALL` enables all optimizations
- `OnnxTensor.createTensor(OrtEnvironment, FloatBuffer, long[])` creates a tensor from a float buffer with given shape
- Session creation is expensive — run on `Dispatchers.Default`
- `OrtSession.Result.get(int index)` returns `OnnxValue` by output index
- Use `as OnnxTensor` to cast output values

## Normalization Schemes
- CTD model (detection): pixel / 255.0 → [0, 1]
- OCR model (recognition): (pixel - 127.5) / 127.5 → [-1, 1]
