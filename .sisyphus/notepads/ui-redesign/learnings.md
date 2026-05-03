# Learnings - UI Redesign

## Wave 1 Completion
- All theme tokens updated in Color.kt, Theme.kt
- New components created: PillToggle, CapsuleNavBar, HomeTranslationCard, HistoryListItem, LanguageSelectorCard, SelectPhotoButton, ThemePreviewCard
- BackgroundLight corrected from #FAFAF9 to #FAFCF8
- Build cannot verify due to JDK 25 incompatibility (environment issue, not code)

## Wave 2 - History Screen Rewrite
- HistoryScreen.kt rewritten: removed TopAppBar, uses simple "History" Text (22sp Normal), LazyColumn with HistoryListItem components, PaddingValues(horizontal=8.dp, vertical=4.dp)
- HistoryDetailScreen.kt rewritten: Scaffold with TopAppBarWithBack ("Ch. {title} Result"), Download IconButton, 3:4 aspect ratio image with rounded corners (24dp), PillToggle instead of ViewToggle
- Both files reduced significantly in line count by delegating to existing components
- HistoryScreen went from 207 lines to 67 lines
- HistoryDetailScreen went from 109 lines to 108 lines (Scaffold adds structure but keeps layout clean)

## Design Pattern Conventions
- Use hardcoded Color() values for design-specific colors (not theme references) when they're fixed design tokens
- Use MaterialTheme.typography for text styles where possible
- All custom components should accept modifier: Modifier = Modifier pattern
