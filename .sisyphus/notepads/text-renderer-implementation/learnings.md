# Learnings - HorizontalTextRenderer Implementation

## TextBlock.minRect
- `TextBlock.minRect` was stubbed to return `RectF()` unconditionally
- Fixed to compute bounding box from `lines` field (List<List<PointF>>)
- This is essential for both production usage and testing

## Font Border (Stroke) on API 28
- `Paint.setStrokeColor()` is API 29+, but minSdk is 28
- Solution: two-pass draw approach — first draw as STROKE with border color, then as FILL with foreground color
- This avoids API level issues while achieving the same visual result as FILL_AND_STROKE with separate stroke color

## CJK Font Loading
- Font asset directory `app/src/main/assets/fonts/` exists but contains only README.md and .gitkeep
- Fallback chain: NotoSansCJK-Regular.ttc → NotoSansCJK-Regular.ttf → Typeface.DEFAULT
- The FontLoadTest already handles the case where font is not bundled

## Testing Patterns
- Use `AndroidJUnit4` runner for instrumented tests
- Use `runBlocking { }` to call suspend functions in tests
- Use `ApplicationProvider.getApplicationContext<Application>()` for context
- Use `Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)` for test bitmaps
- `Canvas.drawColor(color)` for solid fill
- `Bitmap.sameAs(other)` for pixel-level equality comparison
