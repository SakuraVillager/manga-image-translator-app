# P2-2: DeepL Translator Implementation - Learnings

## Completed Work
- Created `DeeplTranslator.kt` - Full DeepL REST API v2 translator implementing `Translator` interface
- Modified `TranslationModule.kt` - Added `TranslatorType.DEEPL -> DeeplTranslator(get())` DI branch
- Created `DeeplTranslatorTest.kt` - Test coverage for: successful translation, API error, empty input, unsupported target language, blank texts, language pair support, code mapping, name

## Key Patterns Followed
- **GptTranslator pattern**: Same constructor signature (`HttpClient` with default), same `retryWithBackoff` method, same `prepare()`/`release()` lifecycle
- **DTO pattern**: Separate request/response data classes with `@Serializable` and `@SerialName` annotations (following ChatCompletionDtos pattern)
- **Test pattern**: Ktor `MockEngine` for HTTP mocking, `runTest` for coroutines, same structure as `GptTranslatorTest`

## Design Decisions
- **Language validation**: Return original text if target language not in `LANGUAGE_CODE_MAP` (DeepL requires `target_lang`)
- **Source auto-detection**: If source not in map, omit `source_lang` from request (DeepL auto-detects)
- **`supportsLanguagePair`**: Returns true if at least one language is supported (source can be auto-detected)
- **Default API base**: `https://api-free.deepl.com/v2` (configurable via `apiBase` in settings)
- **Auth header**: `DeepL-Auth-Key {apiKey}` format per DeepL spec

## Note on SettingsTranslationScreen.kt
- Already has `"deepl"` key in `translatorNames` map with display name "DeepL"
- The `SettingsOptionDialog` iterates all entries, so "DeepL" option already appears in the selector
- No changes needed - the string "deepl" maps to `TranslatorType.DEEPL` via `safeEnumValue` uppercasing

## Pre-existing Issues (not caused by this task)
- Room-generated code has constructor mismatch in `TranslationHistoryDao_Impl.java`
- `OcrDictionaryTest.kt` references `decodeTokenIds` which is not available
