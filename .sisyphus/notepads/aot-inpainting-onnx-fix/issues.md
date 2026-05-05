# Issues - AOT-GAN ONNX Export Fix

## Encountered Issues

1. **Windows GBK encoding crash in colorama**: The `torch.onnx.export` progress prints emoji characters (✅) which crash on Windows with `UnicodeEncodeError: 'gbk' codec can't encode character '\u2705'`. Fix: set `$env:PYTHONIOENCODING='utf-8'` before running the script.

2. **Working directory issue**: The export script uses relative paths (`models\inpainting\inpainting.ckpt`), so it must be run from the project root `D:\manga-image-translator\manga-image-translator`.

3. **Kotlin LSP not installed**: Can't run lsp_diagnostics on the Kotlin files. Changes are straightforward and syntactically verified manually.

## Remaining Concerns
- The ONNX file (1.2 MB) needs to be manually uploaded to the GitHub release at `https://github.com/zyddnys/manga-image-translator/releases/download/beta-0.3/aot_inpainting.onnx` for the download URL to work
- If the file isn't uploaded, users need to manually place it at `{filesDir}/models/aot_inpainting`
