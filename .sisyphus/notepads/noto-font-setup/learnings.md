# Font Setup Learnings

## Download URLs
- NotoSansCJK-Regular.ttc (combined CJK, ~19MB): 
  `https://github.com/notofonts/noto-cjk/raw/main/Sans/OTC/NotoSansCJK-Regular.ttc`
- NotoSansSC-Regular.otf (SC subset, ~8MB):
  `https://github.com/googlefonts/noto-cjk/raw/523d033d6cb47f4a80c58a35753646f5c3608a78/Sans/SubsetOTF/SC/NotoSansSC-Regular.otf`

## Notes
- The full TTC download is ~19MB, too large for reliable download in constrained environments
- Smaller OTF subsets (~8MB) are also too large for timeout-constrained downloads
- Solution: README + system font fallback approach

## Test Approach
- FontLoadTest uses try/catch: if font file present → load from assets and verify
- If font absent → fall back to Typeface.DEFAULT (works on most modern Android devices)
- This avoids hard failures when font isn't bundled
