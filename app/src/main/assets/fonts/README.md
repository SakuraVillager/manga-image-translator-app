# CJK Fonts for Manga Image Translator

This directory should contain a CJK font file for text rendering. Due to its large size (>5MB), the font is not committed to the repository.

## Manual Download

**Option A: NotoSansCJK-Regular.ttc (recommended, ~19MB, covers all CJK)**

Download from Google Fonts GitHub:
```
https://github.com/notofonts/noto-cjk/raw/main/Sans/OTC/NotoSansCJK-Regular.ttc
```

**Option B: NotoSansSC-Regular.otf (~8MB, Simplified Chinese only)**

Download from Google Fonts GitHub:
```
https://github.com/googlefonts/noto-cjk/raw/523d033d6cb47f4a80c58a35753646f5c3608a78/Sans/SubsetOTF/SC/NotoSansSC-Regular.otf
```

**Option C: NotoSansCJKsc-Regular.otf (~16MB, Simplified Chinese full)**

Download from Google Fonts GitHub:
```
https://github.com/googlefonts/noto-cjk/raw/main/Sans/OTF/SimplifiedChinese/NotoSansCJKsc-Regular.otf
```

## Placement

Place the downloaded font file in this directory (`app/src/main/assets/fonts/`).

The app uses `Typeface.createFromAsset()` to load the font from `fonts/<filename>` at runtime.

## .gitignore Note

Font files (*.ttc, *.otf, *.ttf) are excluded from version control via `.gitignore` to keep the repository size manageable. New contributors must download the font manually as described above.
