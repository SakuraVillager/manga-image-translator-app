# ui-conversion Decisions

## Architectural
- Single Activity + NavHost (no fragments)
- Manual ViewModel factory (no Hilt/Koin)
- Room for history, DataStore for settings
- Type-safe navigation with @Serializable
- Mock data for translation results (placeholder UI)
- Theme: MangaTranslatorTheme reads from DataStore for dynamic theme switching

## UI/UX
- SegmentedButtonRow for theme mode (System/Light/Dark)
- Color scheme cards: Default, Dynamic, Green Apple
- Pure Black Dark Mode toggle
- Bottom navigation bar on Home/History/Settings only
- ViewToggle (Original/Translated) SegmentedButton

## Data
- AppPreferences: themeMode, colorScheme, pureBlackDarkMode, appLanguage, tabletInterface, translator, textDirection, textDetector, ocrEngine, imageRepair
- TranslationHistoryEntity: id, imagePath, sourceLanguage, targetLanguage, translatedAt, status, coverImageUri