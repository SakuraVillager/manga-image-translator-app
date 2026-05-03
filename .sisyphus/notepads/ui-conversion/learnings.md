# ui-conversion Learnings

## Conventions
- Package name: `com.sakuravillager.manga_translator` (underscore, no hyphens)
- Theme colors: TaupePrimary (#926B62), SuccessGreen (#547C62), CardGreenBackground (#DCE7DD)
- Light surface: SurfaceLight (#FAFCF8)
- Dark workspace: DarkWorkspaceBackground (#1C1B1F)
- Material Icons extended for all icons
- Room Entity uses autoGenerate=true for id
- DataStore uses Preferences DataStore (not Proto)
- Navigation: type-safe with kotlin serialization
- Compose Compiler Plugin for Kotlin 2.0+ (NOT composeOptions)

## Gotchas
- Kotlin 2.0.21: Must use `org.jetbrains.kotlin.plugin.compose` Gradle plugin, NOT `composeOptions` block
- Package directory must match package name: `manga-translator` → `manga_Translator` on filesystem
- Room KSP: processor must be in annotationProcessorPath for ksp { }
- PickVisualMedia on Android 13+ doesn't need READ_MEDIA_IMAGES permission
- Folder browsing (OpenDocumentTree) needs READ_EXTERNAL_STORAGE on older devices
- BottomNavBar hidden on: workspace, selectPhoto, historyDetail, settings_* screens
- SegmentedButton from Material3 for theme selection

## T15: Dark Theme Refinement
- Theme.kt MangaTranslatorTheme params: darkTheme, colorSchemeName, pureBlackDarkMode
- Color.kt has: BackgroundDark, SurfaceDark, PureBlackBackground (#000000), PureBlackSurface (#000000)
- MainActivity reads preferences from DataStore and passes to MangaTranslatorTheme
- Green Apple colors: Light (GreenApplePrimary #34C759) + Dark (GreenApplePrimaryDark #32D74B)
- Dynamic scheme: placeholder (would generate from wallpaper if available)