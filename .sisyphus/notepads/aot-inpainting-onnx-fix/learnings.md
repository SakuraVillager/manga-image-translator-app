# Learnings - AOT-GAN ONNX Export Fix

## ONNX Export
- The export script at `D:\manga-image-translator\manga-image-translator\export_onnx.py` successfully generates `aot_inpainting.onnx`
- Must set `PYTHONIOENCODING=utf-8` when running on Windows to avoid GBK/emoji encoding crash in colorama
- AOTGenerator has 5,679,244 parameters
- ONNX opset version 18, IR version 10
- Input: `[1, 4, H, W]` (dynamic H, W), Output: `[1, 3, H, W]` (dynamic H, W)
- The AOTWrapper in export_onnx.py splits the 4-channel input into mask[:,0:1] and img[:,1:4], matching Android's NCHW layout

## Generated ONNX File
- File: `models/inpainting/aot_inpainting.onnx`
- Size: 1,295,885 bytes (1.2 MB)
- SHA256: `8d9af65348c17b7749c32c4fad62a4dc6b3fe267bf1ad64fb4f41062a49da848`

## ModelRegistry.kt
- URL changed from `inpainting.ckpt` (PyTorch, 22.8 MB) to `aot_inpainting.onnx` (ONNX, 1.2 MB)
- The ONNX file needs to be uploaded to the GitHub release at the same path
- KDoc updated to reflect the ONNX export was done

## AotInpainter.kt
- Removed redundant `env: OrtEnvironment` field — now uses `sessionManager.environment` from OnnxSessionManager
- The `prepare()` method already correctly uses `modelDownloadManager.ensureModel()` + `sessionManager.createSession()`
- No changes needed to tensor building logic (channel0=mask, 1-3=RGB is correct)

## OnnxSessionManager.kt
- Exposed `environment: OrtEnvironment` as a public property (was `private val env`)
