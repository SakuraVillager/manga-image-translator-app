# Learnings - CJK Font Auto-Download

## Date
2026-05-05

## Implementation Summary
Implemented CJK font auto-download in HorizontalTextRenderer using the existing ModelDownloadManager infrastructure.

## Key Details
- Font: NotoSansCJKkr-Regular.otf (Noto Sans CJK KR Regular, Sans2.004 release)
- URL: https://cdn.jsdelivr.net/gh/googlefonts/noto-cjk@Sans2.004/Sans/OTF/Korean/NotoSansCJKkr-Regular.otf
- SHA256: 6bcb2a0703aa137e874fc2dffa85f6c21ba9a67fa329e81b8c801663af7e992a
- Size: 16,433,112 bytes (~16.4 MB)
- Source: googlefonts/noto-cjk Sans2.004 release, mirrored on jsDelivr CDN

## Files Modified
1. ModelRegistry.kt - Added CJK_FONT entry (name, url, sha256, sizeBytes), added to allModels list
2. HorizontalTextRenderer.kt - Added ModelDownloadManager constructor param, updated prepare() with 3-tier fallback (assets -> download -> DEFAULT)
3. TranslationModule.kt - Passed ModelDownloadManager via Koin get() to HorizontalTextRenderer factory

## Font Loading Strategy (priority order)
1. Assets bundled fonts/NotoSansCJK-Regular.ttc
2. Assets bundled fonts/NotoSansCJK-Regular.ttf
3. Runtime download via ModelDownloadManager.ensureModel(ModelRegistry.CJK_FONT)
4. Fallback to Typeface.DEFAULT

## Log Tags
All logs use class name "HorizontalTextRenderer" as tag.
- "Downloading CJK font..." (INFO, before download)
- "Font loaded successfully" (INFO, after successful download + typeface creation)
- "CJK font download failed: {message}" (WARN, if download/verification fails)
- "CJK font not found, falling back to system default" (WARN, final fallback)

## Notes
- The original task proposed URL (https://github.com/notofonts/noto-cjk/releases/download/Sans2.004/09_NotoSansCJKKR-Regular.otf) returns 404
- Correct repo is googlefonts/noto-cjk, not notofonts/noto-cjk
- Release assets are ZIP files only, no individual OTF downloads
- Used jsDelivr CDN for direct OTF access from the git tag

---

# Learnings - GptTranslator Exponential Backoff Retry

## Date
2026-05-05

## Summary
Added `retryWithBackoff<T>()` to GptTranslator.kt to retry failed API calls with exponential backoff.

## Key Details
- New private suspend generic function `retryWithBackoff<T>(maxRetries=3, baseDelayMs=1000, maxDelayMs=30000)`
- Default: 3 retries, 1s base, 30s cap, exponential (1s → 2s → 4s)
- HTTP 429 catches `ClientRequestException` and reads `Retry-After` header for custom delay
- All other exceptions use exponential backoff
- After retries exhausted, exception propagates to outer try/catch which returns original text (existing fallback preserved)
- Uses `TAG = "GptTranslator"` for retry log messages (separate from `name = "GPT Compatible"` used elsewhere)

## Files Modified
1. `GptTranslator.kt` — Added imports (`ClientRequestException`, `kotlinx.coroutines.delay`), `retryWithBackoff<T>()` function, wrapped API POST call

## Logging
- "Attempt {N}/{M} failed: {message}. Retrying in {delay}ms..." (WARN, per retry)
- Existing `Log.e(name, "Translation API error: ...")` still at line 89 for final failure

## Pre-existing Issues
- `HorizontalTextRenderer.kt` had pre-existing compilation errors (RectF, width, height, etc.) unrelated to this change — resolved via added `import android.graphics.RectF` in subsequent work

---

# Learnings - Vertical Text & RTL Rendering

## Date
2026-05-05

## Summary
Added vertical text rendering and horizontal RTL support to `HorizontalTextRenderer.kt`.

## Changes Made

### File Modified
1. `HorizontalTextRenderer.kt` — Added 3 things:
   - `import android.graphics.RectF` and `import com.sakuravillager.manga_translator.translation.data.TextDirection`
   - Direction dispatch in `render()`: `when (region.direction)` checks `VERTICAL` and `HORIZONTAL_RTL` before the existing horizontal loop
   - Two new private methods:
     - `renderVerticalText()` — draws characters top-to-bottom, columns right-to-left with auto font scaling and two-pass border/fill
     - `renderHorizontalRtl()` — draws full text right-aligned with auto font scaling and two-pass border/fill

### Key Details
- `renderVerticalText()` calculates columns based on `rect.height() / (fontSize * 1.2)`, auto-scales down if columns exceed `rect.width() / (fontSize * 1.1)`
- Each character is drawn individually in a nested loop (outer: columns, inner: chars in column)
- `renderHorizontalRtl()` uses `canvas.drawText(text, rect.right - textWidth, y, paint)` for right-alignment
- Both methods preserve the two-pass draw pattern (STROKE then FILL) matching the existing horizontal renderer
- Existing `TextDirection.AUTO` and `TextDirection.HORIZONTAL` handling is completely untouched

### Gotchas
- `RectF` must be explicitly imported even though the original code uses it via type inference from `region.minRect`
- `colX -= fontSize * 1.1f` ambiguity error was a cascade from the missing `RectF` import — once `RectF` is resolved, the type inference works correctly
