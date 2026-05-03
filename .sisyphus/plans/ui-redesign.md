# UI Redesign: Design System → Kotlin Compose

## TL;DR

> **Quick Summary**: Rewrite ALL Jetpack Compose UI components and screens from scratch to pixel-match the comictrans-design-system React prototype. Preserve the existing data layer (Room, DataStore, Preferences, Models). Add navigation animations, integrate Android Photo Picker, and create custom design-specific components (capsule navbar, pill toggle, immersive home card, design-matching history list). No complex translation logic — UI shell only.
> 
> **Deliverables**:
> - 10 fully redesigned Compose screens matching the design prototype
> - 8+ custom reusable UI components
> - Updated theme tokens (Color.kt, Type.kt) matching design system
> - Animated navigation transitions (slide + fade)
> - Android Photo Picker integration for SelectPhotoScreen
> - ViewModel relocation and cleanup
> 
> **Estimated Effort**: Large
> **Parallel Execution**: YES - 3 waves + Final verification
> **Critical Path**: Task 1 (Theme) → Task 2 (Components) → Tasks 3-8 (Screens) → Task 9 (Integration) → Final Verification

---

## Context

### Original Request
User wants to convert the comictrans-design-system web UI prototype (React/Tailwind) into Kotlin Jetpack Compose. The current Android app has a functional data layer but the UI is "很丑" (ugly) — it uses generic Material3 components without the custom styling that makes the design system distinctive (warm colors, large rounded corners, capsule navigation, pill toggles, immersive cards).

### Interview Summary
**Key Discussions**:
- Can delete and rewrite all existing UI code: Confirmed
- Navigation animations: YES — slide + fade transitions
- Icons: Material Icons Extended (already in dependencies)
- SelectPhotoScreen: Real Android Photo Picker API integration
- No complex translation logic yet: UI shell only, mock data

**Research Findings**:
- Design has 10 screens with highly specific visual identity
- Design tokens: warm off-white (#FAFCF8), green cards (#DCE7DD), forest green accent (#547C62), large rounded corners (28dp/24dp)
- Current Color.kt has `BackgroundLight = #FAFAF9` but design uses `#FAFCF8` — nub of the "ugly"
- Current BottomNavBar uses standard NavigationBar, needs capsule redesign
- Current ViewToggle uses SegmentedButton, needs pill toggle redesign
- Current HomeScreen uses small Card, needs immersive hero card with decorative background
- ComicTransApp.kt has the actual nav host; NavHost.kt has placeholder screens (redundant, should be removed)

### Metis Review
**Identified Gaps** (addressed):
- Photo Picker fallback for API 28-32 → Use `ActivityResultContracts.GetMultipleContents()` as fallback
- SettingsAppearance phone mockups too complex → Use themed rounded cards with accent color swatches instead
- Language selector dialog → Simple AlertDialog with preset language list
- SelectPhoto max selection → 10 images, show counter
- SettingsScreen top bar behavior → Settings as nav destination should NOT show back arrow
- Dark theme coverage → Each custom component needs explicit dark colors
- WorkspaceScreen multi-image handling → Keep single-image mock for now
- HistoryDetailScreen download button → Icon button (matching design), not text button

---

## Work Objectives

### Core Objective
Rewrite all Jetpack Compose UI code to pixel-match the comictrans-design-system React prototype, creating a warm, distinctive, content-first Android app UI.

### Concrete Deliverables
- `ui/theme/Color.kt` — Updated with all design tokens
- `ui/theme/Type.kt` — Refined typography matching design (22sp headers, etc.)
- `ui/theme/Theme.kt` — Updated color scheme mappings
- `ui/components/PillToggle.kt` — Custom pill-style Original/Translated toggle
- `ui/components/CapsuleNavBar.kt` — Bottom navigation with capsule active indicator (replaces BottomNavBar)
- `ui/components/HomeTranslationCard.kt` — Immersive hero card (replaces NewTranslationCard)
- `ui/components/LanguageSelectorCard.kt` — Redesigned with Languages icon + ChevronRight
- `ui/components/HistoryListItem.kt` — Row layout with 3:4 thumbnail, green dot status
- `ui/components/SelectPhotoButton.kt` — Bottom-aligned full-width pill button
- `ui/components/ThemePreviewCard.kt` — Color scheme preview card for Appearance settings
- `ui/components/SettingsListItem.kt` — Refined with proper spacing and icon styling
- `ui/components/TopAppBarWithBack.kt` — Refined styling
- `ui/screens/HomeScreen.kt` — With immersive home card
- `ui/screens/SelectPhotoScreen.kt` — Photo Picker + 3:4 grid + selection
- `ui/screens/WorkspaceScreen.kt` — Pill toggle + language selector + mock bubbles
- `ui/screens/HistoryScreen.kt` — Row-based list with thumbnails
- `ui/screens/HistoryDetailScreen.kt` — Download icon, proper layout
- `ui/screens/SettingsScreen.kt` — No back arrow (nav destination)
- `ui/screens/SettingsAppearanceScreen.kt` — Theme preview cards + color scheme selectors
- `ui/screens/SettingsTranslationScreen.kt` — Matching design list style
- `ui/screens/SettingsDebugScreen.kt` — Matching design list style
- `ui/screens/SettingsAboutScreen.kt` — Matching design list style
- `navigation/AnimatedNavHost.kt` — Nav transitions (slide + fade)
- `ComicTransApp.kt` — Updated with animated nav + correct bottom bar visibility

### Definition of Done
- [ ] `./gradlew assembleDebug` compiles successfully
- [ ] All 10 screens render correctly in both light and dark themes
- [ ] Bottom nav shows on Home/History/Settings; hides on all other screens
- [ ] Navigation transitions animate (slide + fade)
- [ ] SelectPhotoScreen opens Android Photo Picker
- [ ] Pill toggle switches between Original/Translated
- [ ] HomeScreen shows immersive green card with FolderOpen icon + text
- [ ] History list shows thumbnails with 3:4 aspect ratio
- [ ] Settings screens match design prototype layout

### Must Have
- All 10 screens rewritten to match design prototype
- Capsule bottom navigation bar with green active indicator
- Pill-style toggle for Original/Translated
- Immersive "New Translation" card on home screen
- 3:4 aspect ratio thumbnails in history list
- Photo Picker integration for SelectPhotoScreen
- Navigation animations (slide enter/exit + fade)
- Light and dark theme support for all custom components
- All existing data layer code preserved unchanged

### Must NOT Have (Guardrails)
- NO changes to `data/` directory files (Room, DataStore, Preferences, Models)
- NO new Gradle dependencies
- NO AndroidManifest.xml changes (unless required for Photo Picker)
- NO translation logic implementation (mock/placeholder only)
- NO complex phone mockup rendering for ThemePreviewCard — use simple colored cards
- NO shared element transitions or spring animations — simple slide+fade only
- NO SVG icon imports — use Material Icons Extended exclusively
- NO workspace multi-image pager/ViewPager — single image mock only
- NO AppRoutes route pattern changes

---

## Verification Strategy (MANDATORY)

> **ZERO HUMAN INTERVENTION** — ALL verification is agent-executed. No exceptions.

### Test Decision
- **Infrastructure exists**: YES (Android project with Gradle)
- **Automated tests**: NO (UI visual testing requires emulator/device — not practical in this context)
- **Framework**: N/A
- **Agent-Executed QA**: Build verification + code pattern matching

### QA Policy
Every task MUST include agent-executed QA scenarios.
Evidence saved to `.sisyphus/evidence/task-{N}-{scenario-slug}.{ext}`.

- **All tasks**: Use Bash (`./gradlew compileDebugKotlin`) to verify compilation
- **Pattern verification**: Use grep to verify design tokens are used (colors, shapes, spacing)
- **Build verification**: Use Bash to verify `./gradlew assembleDebug` succeeds at the end

---

## Execution Strategy

### Parallel Execution Waves

```
Wave 1 (Foundation — theme + core components):
├── Task 1: Update theme tokens (Color.kt, Type.kt, Theme.kt) [quick]
├── Task 2: Create custom PillToggle component [quick]
├── Task 3: Create CapsuleNavBar component [quick]
├── Task 4: Create HomeTranslationCard component [quick]
└── Task 5: Create supporting components (HistoryListItem, LanguageSelectorCard, SelectPhotoButton, ThemePreviewCard) [unspecified-high]

Wave 2 (Screen rewrites — MAX PARALLEL):
├── Task 6: Rewrite HomeScreen (depends: 1, 4) [quick]
├── Task 7: Rewrite SelectPhotoScreen with Photo Picker (depends: 5) [deep]
├── Task 8: Rewrite WorkspaceScreen (depends: 2, 5) [unspecified-high]
├── Task 9: Rewrite HistoryScreen + HistoryDetailScreen (depends: 5) [unspecified-high]
├── Task 10: Rewrite SettingsScreen + all sub-screens (depends: 5) [unspecified-high]
└── Task 11: Create AnimatedNavHost + update ComicTransApp navigation (depends: 3, 6-10) [unspecified-high]

Wave 3 (Cleanup + Integration):
├── Task 12: ViewModel cleanup + remove redundant NavHost.kt (depends: 11) [quick]
└── Task 13: Integration build verification (depends: 12) [quick]

Wave FINAL (Verification — 4 parallel reviews):
├── Task F1: Plan compliance audit (oracle)
├── Task F2: Code quality review (unspecified-high)
├── Task F3: Build + visual QA (unspecified-high)
└── Task F4: Scope fidelity check (deep)
-> Present results -> Get explicit user okay

Critical Path: Task 1 → Task 4 → Task 6 → Task 11 → Task 12 → Task 13 → F1-F4
Parallel Speedup: ~60% faster than sequential
Max Concurrent: 5 (Wave 1), 5 (Wave 2)
```

### Dependency Matrix

| Task | Depends On | Blocks |
|------|-----------|--------|
| 1 | - | 6 |
| 2 | - | 8 |
| 3 | - | 11 |
| 4 | 1 | 6 |
| 5 | - | 7, 8, 9, 10 |
| 6 | 1, 4 | 11 |
| 7 | 5 | 11 |
| 8 | 2, 5 | 11 |
| 9 | 5 | 11 |
| 10 | 5 | 11 |
| 11 | 3, 6-10 | 12 |
| 12 | 11 | 13 |
| 13 | 12 | F1-F4 |

### Agent Dispatch Summary

- **Wave 1**: 5 tasks — T1-quick, T2-quick, T3-quick, T4-quick, T5-unspecified-high
- **Wave 2**: 6 tasks — T6-quick, T7-deep, T8-unspecified-high, T9-unspecified-high, T10-unspecified-high, T11-unspecified-high
- **Wave 3**: 2 tasks — T12-quick, T13-quick
- **FINAL**: 4 tasks — F1-oracle, F2-unspecified-high, F3-unspecified-high, F4-deep

---

## TODOs

- [x] 1. **Update Theme Tokens (Color.kt, Type.kt, Theme.kt)**

  **What to do**:
  - In `Color.kt`: Fix `BackgroundLight` from `#FAFAF9` → `#FAFCF8` to match design. Add missing design tokens: `SurfaceGreenLight = Color(0xFFE1E5E1)` (for surface variants), `NavBackground = Color(0xFFFAFCF8)` with 90% alpha, `BorderLight = Color(0xFFE1E5E1)`.
  - In `Type.kt`: Verify `headlineLarge` (22sp, Normal weight) matches design's "Library" title. Ensure `bodySmall` (13.5sp) is present for subtitle text. Adjust `labelLarge` (14sp, Medium) for nav bar labels.
  - In `Theme.kt`: Reconcile color mapping — `primaryContainer` should map to `CardGreenBackground (#DCE7DD)` (already done). Verify `surfaceVariant` maps to `SurfaceGreenLight`. Ensure dark theme variants exist for all new tokens.

  **Must NOT do**:
  - Do NOT delete any existing color tokens (they may be referenced elsewhere)
  - Do NOT change the Theme.kt function signature
  - Do NOT add new dependencies

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with Tasks 2-5)
  - **Blocks**: Task 6
  - **Blocked By**: None (can start immediately)

  **References**:

  **Pattern References**:
  - `app/src/main/java/com/sakuravillager/manga_translator/ui/theme/Color.kt` — Current color tokens, add new ones alongside existing
  - `app/src/main/java/com/sakuravillager/manga_translator/ui/theme/Type.kt` — Current typography, verify matches design (22sp headers)
  - `app/src/main/java/com/sakuravillager/manga_translator/ui/theme/Theme.kt` — Current MaterialTheme color scheme mappings

  **API/Type References**:
  - Design Prototype `Prototype.tsx` lines with color classes: `bg-[#FAFCF8]`, `bg-[#DCE7DD]`, `text-[#1A1C19]`, `text-[#424944]`, `bg-[#547C62]`, `rounded-[28px]`, `rounded-[24px]`, `rounded-[14px]`

  **Why Each Reference Matters**:
  - Color.kt needs updating because `BackgroundLight` is `#FAFAF9` but design uses `#FAFCF8` — this subtle difference contributes to the "ugly" feeling
  - Theme.kt color mappings determine the overall Material3 palette; verifying surfaceVariant etc. maps correctly ensures components auto-theme

  **Acceptance Criteria**:

  **QA Scenarios (MANDATORY)**:

  ```
  Scenario: Theme tokens compile and match design
    Tool: Bash
    Preconditions: Project at E:\yhz\Projects\manga-image-translator-app
    Steps:
      1. Run `./gradlew compileDebugKotlin`
      2. Grep Color.kt for `FAFCF8` — verify BackgroundLight updated
      3. Grep Color.kt for `E1E5E1` — verify SurfaceGreenLight added
      4. Grep Color.kt for `DCE7DD` — verify CardGreenBackground still present
    Expected Result: BUILD SUCCESSFUL, all design tokens present
    Failure Indicators: Compilation error, missing token
    Evidence: .sisyphus/evidence/task-1-theme-tokens.txt

  Scenario: Dark theme color variants exist
    Tool: Bash
    Preconditions: Theme.kt and Color.kt modified
    Steps:
      1. Grep Color.kt for `Dark` — verify dark theme tokens exist for all new light tokens
      2. Grep Theme.kt for `surfaceVariant` — verify it maps correctly in both light and dark
    Expected Result: Dark variants exist for all new tokens, surfaceVariant mapped
    Failure Indicators: Missing dark variant, incorrect mapping
    Evidence: .sisyphus/evidence/task-1-dark-variants.txt
  ```

  **Commit**: YES (groups with Wave 1)
  - Message: `refactor(ui): update theme tokens to match design system`
  - Files: `ui/theme/Color.kt`, `ui/theme/Type.kt`, `ui/theme/Theme.kt`
  - Pre-commit: `./gradlew compileDebugKotlin`

- [x] 2. **Create Custom PillToggle Component**

  **What to do**:
  - Create `ui/components/PillToggle.kt` replacing `ViewToggle.kt`
  - Implement a pill-style segmented toggle matching the design: rounded-full container (`#E1E5E1` background), two pill buttons ("Original" / "Translated"), active state = white background with shadow, inactive = transparent text `#424944`
  - The toggle should use `Row` with `Modifier.clip(RoundedCornerShape(50%))` for the container, and individual `Button`s with `RoundedCornerShape(50%)` for each pill
  - Accept `currentState: ViewState`, `onStateChange: (ViewState) -> Unit`, `modifier: Modifier`
  - Support both light and dark themes via `MaterialTheme.colorScheme`

  **Must NOT do**:
  - Do NOT use `SingleChoiceSegmentedButtonRow` — it's the old design
  - Do NOT delete `ViewToggle.kt` yet (other screens import it — cleanup in Task 12)
  - Do NOT add animation libraries — use Compose built-in `animateColorAsState`

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1
  - **Blocks**: Task 8 (WorkspaceScreen uses PillToggle)
  - **Blocked By**: None

  **References**:

  **Pattern References**:
  - `app/src/main/java/com/sakuravillager/manga_translator/ui/components/ViewToggle.kt` — Current SegmentedButton implementation to replace
  - Design Prototype `Prototype.tsx` lines 144-156 — Pill toggle HTML: `bg-[#E1E5E1]/50 p-1 rounded-full`, active=`bg-white shadow-sm`, inactive=`text-[#424944]`

  **API/Type References**:
  - `app/src/main/java/com/sakuravillager/manga_translator/data/model/ViewState.kt` — `enum class ViewState { SOURCE, TRANSLATED }`

  **Why Each Reference Matters**:
  - ViewToggle.kt shows the current API surface the new PillToggle must match
  - ViewState enum is used as state parameter — PillToggle must accept the same types
  - The design prototype shows exact visual specs: rounded-full container, white active pills, subtle shadow

  **Acceptance Criteria**:

  **QA Scenarios**:

  ```
  Scenario: PillToggle compiles and matches design API
    Tool: Bash
    Preconditions: Component file created
    Steps:
      1. Run `./gradlew compileDebugKotlin`
      2. Grep PillToggle.kt for `RoundedCornerShape` — verify 50% pill shape
      3. Grep PillToggle.kt for `ViewState` — verify accepts ViewState enum
      4. Grep PillToggle.kt for `SegmentedButton` — verify NOT present (old pattern)
    Expected Result: BUILD SUCCESSFUL, pill shape present, ViewState accepted, no SegmentedButton
    Failure Indicators: Compilation error, SegmentedButton found, missing ViewState
    Evidence: .sisyphus/evidence/task-2-pill-toggle.txt
  ```

  **Commit**: YES (groups with Wave 1)
  - Message: `refactor(ui): create PillToggle component matching design prototype`
  - Files: `ui/components/PillToggle.kt`
  - Pre-commit: `./gradlew compileDebugKotlin`

- [x] 3. **Create Custom CapsuleNavBar Component**

  **What to do**:
  - Create `ui/components/CapsuleNavBar.kt` replacing `BottomNavBar.kt`
  - Implement a capsule-style bottom navigation matching the design: `#FAFCF8` background with blur effect, 3 tabs (Home, History, Settings), active tab shows a pill-shaped background in `#DCE7DD` containing icon + label, inactive tabs show just icon + label in `#424944`
  - Each tab: `Column` with `Icon` + `Text` wrapped in `Box` with `RoundedCornerShape(50%)` background when active
  - Use `Modifier.navigationBarsPadding()` for safe area
  - Support light and dark themes

  **Must NOT do**:
  - Do NOT use `NavigationBar` / `NavigationBarItem` — that's the old Material3 component
  - Do NOT delete `BottomNavBar.kt` yet (cleanup in Task 12)
  - Do NOT add blur library — use semi-transparent background color as approximation

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1
  - **Blocks**: Task 11 (ComicTransApp navigation)
  - **Blocked By**: None

  **References**:

  **Pattern References**:
  - `app/src/main/java/com/sakuravillager/manga_translator/ui/components/BottomNavBar.kt` — Current NavigationBar implementation to replace
  - Design Prototype `Prototype.tsx` lines 441-470 — Bottom nav HTML structure: 3 tabs, active has `bg-[#DCE7DD] text-[#1A1C19]` pill, inactive `text-[#424944]`

  **API/Type References**:
  - `app/src/main/java/com/sakuravillager/manga_translator/navigation/AppRoutes.kt` — Route definitions and `bottomNavItems` list

  **Why Each Reference Matters**:
  - BottomNavBar.kt shows the current API (`currentRoute`, `onNavigate`) that CapsuleNavBar must maintain
  - AppRoutes.kt defines the 3 bottom-nav destinations (Home, History, Settings) with their icons
  - Design prototype shows the pill-style active indicator with rounded-full background

  **Acceptance Criteria**:

  **QA Scenarios**:

  ```
  Scenario: CapsuleNavBar compiles and matches design
    Tool: Bash
    Preconditions: Component file created
    Steps:
      1. Run `./gradlew compileDebugKotlin`
      2. Grep CapsuleNavBar.kt for `NavigationBarItem` — verify NOT present
      3. Grep CapsuleNavBar.kt for `RoundedCornerShape` — verify pill shape
      4. Grep CapsuleNavBar.kt for `CardGreenBackground` or `DCE7DD` — verify active indicator color
      5. Grep CapsuleNavBar.kt for `currentRoute` — verify accepts route string
    Expected Result: BUILD SUCCESSFUL, no NavigationBarItem, pill shapes present, color matches
    Failure Indicators: NavigationBarItem found, wrong color, compilation error
    Evidence: .sisyphus/evidence/task-3-capsule-nav.txt
  ```

  **Commit**: YES (groups with Wave 1)
  - Message: `refactor(ui): create CapsuleNavBar component matching design prototype`
  - Files: `ui/components/CapsuleNavBar.kt`
  - Pre-commit: `./gradlew compileDebugKotlin`

- [x] 4. **Create HomeTranslationCard (Immersive Hero Card)**

  **What to do**:
  - Create `ui/components/HomeTranslationCard.kt` replacing `NewTranslationCard.kt`
  - Implement an immersive hero card matching the design: `RoundedCornerShape(28.dp)`, `CardGreenBackground (#DCE7DD)` background, large `FolderOpen` icon (26dp, `#547C62`) in a rounded-2xl icon container, "New Translation" title (20sp, Medium weight), "Import folder or images to start" subtitle (14sp, `#424944`), and a subtle large `Image` icon watermark at bottom-right (120dp, 5% opacity)
  - The card should fill most of the screen width (max-width 95% with horizontal padding)
  - Add `clickable` modifier with scale animation (optional: `animateFloatAsState` for press effect)

  **Must NOT do**:
  - Do NOT delete `NewTranslationCard.kt` yet (cleanup in Task 12)
  - Do NOT make the card fixed height — let content determine height
  - Do NOT add image loading for the watermark icon — use `Icons.Default.Image` at low alpha

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1
  - **Blocks**: Task 6 (HomeScreen uses HomeTranslationCard)
  - **Blocked By**: None

  **References**:

  **Pattern References**:
  - `app/src/main/java/com/sakuravillager/manga_translator/ui/components/NewTranslationCard.kt` — Current small card implementation to replace
  - Design Prototype `Prototype.tsx` lines 68-81 — Hero card HTML: `bg-[#DCE7DD] rounded-[28px] p-6`, `FolderOpen` icon 26px, `ImageIcon` watermark 120px at 5% opacity, "New Translation" 20px Medium, "Import folder or images to start" 14px `#424944`

  **Why Each Reference Matters**:
  - NewTranslationCard shows the current API (`onClick`, `modifier`) that HomeTranslationCard must match
  - The design prototype specifies exact dimensions: 28dp corners, 24dp padding, 26dp icon, 120dp watermark, 20sp title, 14sp subtitle

  **Acceptance Criteria**:

  **QA Scenarios**:

  ```
  Scenario: HomeTranslationCard compiles and matches design
    Tool: Bash
    Preconditions: Component file created
    Steps:
      1. Run `./gradlew compileDebugKotlin`
      2. Grep HomeTranslationCard.kt for `28.dp` — verify rounded corner radius
      3. Grep HomeTranslationCard.kt for `FolderOpen` — verify icon used
      4. Grep HomeTranslationCard.kt for `Image` — verify watermark icon
      5. Grep HomeTranslationCard.kt for `5` — verify low alpha (5% = 0.05f or similar)
    Expected Result: BUILD SUCCESSFUL, 28dp corners, FolderOpen icon, Image watermark, alpha present
    Failure Indicators: Compilation error, missing icon, wrong corner radius
    Evidence: .sisyphus/evidence/task-4-home-card.txt
  ```

  **Commit**: YES (groups with Wave 1)
  - Message: `refactor(ui): create immersive HomeTranslationCard matching design`
  - Files: `ui/components/HomeTranslationCard.kt`
  - Pre-commit: `./gradlew compileDebugKotlin`

- [x] 5. **Create Supporting Components (HistoryListItem, LanguageSelectorCard, SelectPhotoButton, ThemePreviewCard)**

  **What to do**:
  - **HistoryListItem** (`ui/components/HistoryListItem.kt`): Row with 3:4 aspect ratio thumbnail (`RoundedCornerShape(14.dp)`), chapter title (17sp), time ago text (13.5sp, `#424944`), green dot + "Finished" status (12sp Medium, `#547C62`). Handle null `coverImageUri` with placeholder background. Clickable row with 12dp vertical padding and 20dp horizontal gap.
  - **LanguageSelectorCard** (update `ui/components/LanguageSelectorCard.kt`): Redesign to match design — `Languages` icon (24dp, `#547C62`), "Target Language" subtitle (14sp, `#424944`), selected language text (16sp, Medium weight), `ChevronRight` icon (20dp, `#424944`), `RoundedCornerShape(16.dp)` surface with `#E1E5E1`/50% alpha background.
  - **SelectPhotoButton** (`ui/components/SelectPhotoButton.kt`): Full-width pill button that slides up from bottom when images are selected. `RoundedCornerShape(50%)`, `CardGreenBackground` color, "Translate Selected" text (16sp Medium), matching design's bottom action button.
  - **ThemePreviewCard** (`ui/components/ThemePreviewCard.kt`): A simple card showing theme name + primary color swatch + accent color swatch. When selected, show a green check overlay. NOT a full phone mockup — just a rounded card (16dp corners) with color chips and theme name below.

  **Must NOT do**:
  - Do NOT create full phone UI mockups for ThemePreviewCard — simple colored cards suffice
  - Do NOT import SVG icons — use Material Icons Extended
  - Do NOT add pagination/infinite scroll to HistoryListItem — parent LazyColumn handles that

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1
  - **Blocks**: Tasks 7, 8, 9, 10
  - **Blocked By**: None

  **References**:

  **Pattern References**:
  - `app/src/main/java/com/sakuravillager/manga_translator/ui/components/LanguageSelectorCard.kt` — Current card to redesign
  - `app/src/main/java/com/sakuravillager/manga_translator/ui/components/SettingsListItem.kt` — Current settings item (keep structure, revise spacing)
  - Design Prototype `Prototype.tsx` lines 113-122 — Language selector: `Languages` icon, label+value, `ChevronRight`
  - Design Prototype `Prototype.tsx` lines 183-198 — History list item: 3:4 thumbnail, chapter name, time ago, green dot status
  - Design Prototype `Prototype.tsx` lines 509-520 — Select photo button: full-width pill, `#DCE7DD` bg, "Translate Selected"

  **API/Type References**:
  - `app/src/main/java/com/sakuravillager/manga_translator/data/model/TranslationHistory.kt` — `TranslationHistory` data class with `coverImageUri: String?` (nullable — handle in HistoryListItem)
  - `app/src/main/java/com/sakuravillager/manga_translator/data/model/TranslationStatus.kt` — `COMPLETED`, `IN_PROGRESS`, etc.

  **Why Each Reference Matters**:
  - LanguageSelectorCard current API must be preserved (onClick, language string)
  - TranslationHistory data model determines which fields HistoryListItem can display
  - TranslationStatus enum determines the green dot / "Finished" text display
  - Design specifies exact styling: 3:4 thumbnails, 14dp rounded corners, green dot indicators

  **Acceptance Criteria**:

  **QA Scenarios**:

  ```
  Scenario: All 4 components compile and match design patterns
    Tool: Bash
    Preconditions: All 4 component files created/updated
    Steps:
      1. Run `./gradlew compileDebugKotlin`
      2. Grep HistoryListItem.kt for `3/4` or `aspectRatio` — verify 3:4 ratio
      3. Grep HistoryListItem.kt for `547C62` or `SuccessGreen` — verify green dot color
      4. Grep LanguageSelectorCard.kt for `Languages` and `ChevronRight` — verify icons
      5. Grep SelectPhotoButton.kt for `RoundedCornerShape` and `50` — verify pill shape
      6. Grep ThemePreviewCard.kt for `Check` — verify selection indicator
    Expected Result: BUILD SUCCESSFUL, all patterns verified
    Failure Indicators: Compilation error, missing aspect ratio, wrong icons, no pill shape
    Evidence: .sisyphus/evidence/task-5-supporting-components.txt

  Scenario: HistoryListItem handles null coverImageUri
    Tool: Bash
    Preconditions: HistoryListItem.kt created
    Steps:
      1. Grep HistoryListItem.kt for `coverImageUri` — verify null check or fallback UI
    Expected Result: Null case handled (placeholder icon or background color shown)
    Failure Indicators: No null handling — crash risk
    Evidence: .sisyphus/evidence/task-5-null-handling.txt
  ```

  **Commit**: YES (groups with Wave 1)
  - Message: `refactor(ui): create supporting components matching design prototype`
  - Files: `ui/components/HistoryListItem.kt`, `ui/components/LanguageSelectorCard.kt`, `ui/components/SelectPhotoButton.kt`, `ui/components/ThemePreviewCard.kt`
  - Pre-commit: `./gradlew compileDebugKotlin`

- [x] 6. **Rewrite HomeScreen**

  **What to do**:
  - Rewrite `ui/screens/HomeScreen.kt` to match the design prototype
  - Remove the Scaffold/TopAppBar — design has a simple "Library" text header (22sp, Normal weight) with no navigation icons
  - Replace `NewTranslationCard` with `HomeTranslationCard` (from Task 4)
  - Layout: Column with "Library" title at top (padding 24dp start, 16dp top), then immersive card filling remaining space with 16dp horizontal padding and 32dp bottom padding for nav bar clearance
  - Background: `MaterialTheme.colorScheme.background` (maps to `#FAFCF8`)
  - NO Scaffold, NO TopAppBar — just a scrollable Column

  **Must NOT do**:
  - Do NOT add a TopAppBar — the design has a plain text header
  - Do NOT add TabNav or search functionality — not in the design
  - Do NOT change the `onNavigate` callback parameter signature

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: NO (depends on Task 4)
  - **Parallel Group**: Wave 2 (with Tasks 7-10)
  - **Blocks**: Task 11
  - **Blocked By**: Tasks 1, 4

  **References**:

  **Pattern References**:
  - `app/src/main/java/com/sakuravillager/manga_translator/ui/screens/HomeScreen.kt` — Current implementation with Scaffold + TopAppBar + small NewTranslationCard
  - Design Prototype `Prototype.tsx` lines 53-86 — HomeScreen: `Library` title 22sp normal, no nav icons, large green hero card with FolderOpen + ImageIcon watermark

  **Why Each Reference Matters**:
  - Current HomeScreen imports Scaffold/TopAppBar which should be removed
  - Design shows a minimal layout with just title text and the immersive card

  **Acceptance Criteria**:

  **QA Scenarios**:

  ```
  Scenario: HomeScreen compiles with new card and no TopAppBar
    Tool: Bash
    Preconditions: HomeScreen.kt rewritten, HomeTranslationCard.kt exists
    Steps:
      1. Run `./gradlew compileDebugKotlin`
      2. Grep HomeScreen.kt for `HomeTranslationCard` — verify usage
      3. Grep HomeScreen.kt for `TopAppBar` — verify NOT present
      4. Grep HomeScreen.kt for `Scaffold` — verify NOT present
      5. Grep HomeScreen.kt for `Library` — verify title text
    Expected Result: BUILD SUCCESSFUL, HomeTranslationCard used, no TopAppBar/Scaffold, Library title present
    Failure Indicators: TopAppBar found, Scaffold found, compilation error
    Evidence: .sisyphus/evidence/task-6-home-screen.txt
  ```

  **Commit**: YES (groups with Wave 2)
  - Message: `refactor(ui): rewrite HomeScreen to match design prototype`
  - Files: `ui/screens/HomeScreen.kt`
  - Pre-commit: `./gradlew compileDebugKotlin`

- [x] 7. **Rewrite SelectPhotoScreen with Photo Picker**

  **What to do**:
  - Rewrite `ui/screens/SelectPhotoScreen.kt` to match the design prototype
  - Replace placeholder grid with Android Photo Picker integration:
    - On API 33+: Use `ActivityResultContracts.PickMultipleVisualMedia()` with max 10 items
    - On API 28-32: Use `ActivityResultContracts.GetMultipleContents()` as fallback
  - 3-column grid with 3:4 aspect ratio image cards, `RoundedCornerShape(14.dp)`, using Coil `AsyncImage`
  - Selected images show green border (`2.dp, SuccessGreen`) + green checkmark overlay in center
  - Bottom "Translate Selected" button (SelectPhotoButton) slides up when selection is not empty, with count badge
  - TopAppBarWithBack with "Select Image" title
  - Use `PermissionRequest` component already available in the project for permission handling

  **Must NOT do**:
  - Do NOT implement actual translation logic — navigate to workspace with mock
  - Do NOT add ViewPager/paging for multi-image in workspace yet
  - Do NOT add photo capture (camera) — only gallery selection
  - Do NOT exceed 10 image selection limit

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES (after Task 5)
  - **Parallel Group**: Wave 2
  - **Blocks**: Task 11
  - **Blocked By**: Task 5 (SelectPhotoButton component)

  **References**:

  **Pattern References**:
  - `app/src/main/java/com/sakuravillager/manga_translator/ui/screens/SelectPhotoScreen.kt` — Current implementation with placeholder grid and FloatingActionButton
  - `app/src/main/java/com/sakuravillager/manga_translator/ui/viewmodel/SelectPhotoViewModel.kt` — Current ViewModel with addImage/removeImage/clearSelection
  - `app/src/main/java/com/sakuravillager/manga_translator/ui/components/PermissionRequest.kt` — Existing permission handling component
  - Design Prototype `Prototype.tsx` lines 472-526 — SelectPhoto: 3-column grid, 3:4 aspect ratio, green checkmark on selected, bottom pill button "Translate Selected"

  **External References**:
  - Android Photo Picker: `PickVisualMedia` and `PickMultipleVisualMedia` API, available from Activity Results API

  **Why Each Reference Matters**:
  - SelectPhotoViewModel already has addImage/removeImage — reuse its API
  - PermissionRequest handles storage permissions — integrate for API < 33
  - Design specifies exact grid: 3 columns, 3:4 ratio, 14dp corners, green 2dp border + checkmark on selection

  **Acceptance Criteria**:

  **QA Scenarios**:

  ```
  Scenario: SelectPhotoScreen compiles with Photo Picker integration
    Tool: Bash
    Preconditions: SelectPhotoScreen.kt rewritten
    Steps:
      1. Run `./gradlew compileDebugKotlin`
      2. Grep SelectPhotoScreen.kt for `PickMultipleVisualMedia` or `GetMultipleContents` — verify picker integration
      3. Grep SelectPhotoScreen.kt for `aspectRatio` — verify 3:4 ratio
      4. Grep SelectPhotoScreen.kt for `SelectPhotoButton` — verify bottom button component used
      5. Grep SelectPhotoScreen.kt for `FloatingActionButton` — verify NOT present (old pattern)
    Expected Result: BUILD SUCCESSFUL, picker integrated, 3:4 ratio, SelectPhotoButton used, no FAB
    Failure Indicators: Compilation error, FAB found, no picker integration
    Evidence: .sisyphus/evidence/task-7-select-photo.txt

  Scenario: Selection limit of 10 enforced
    Tool: Bash
    Preconditions: SelectPhotoScreen.kt rewritten
    Steps:
      1. Grep SelectPhotoScreen.kt for `10` — verify max selection limit
    Expected Result: 10-image limit constant found
    Failure Indicators: No limit enforced
    Evidence: .sisyphus/evidence/task-7-selection-limit.txt
  ```

  **Commit**: YES (groups with Wave 2)
  - Message: `refactor(ui): rewrite SelectPhotoScreen with Photo Picker and design matching`
  - Files: `ui/screens/SelectPhotoScreen.kt`
  - Pre-commit: `./gradlew compileDebugKotlin`

- [x] 8. **Rewrite WorkspaceScreen**

  **What to do**:
  - Rewrite `ui/screens/WorkspaceScreen.kt` to match the design prototype
  - TopAppBarWithBack with "Translation" title + green "Save" pill button (13sp font, `CardGreenBackground` bg, `RoundedCornerShape(50%)`)
  - Language selector card (from Task 5): `Languages` icon + "Target Language" + "Simplified Chinese" + `ChevronRight`
  - Large manga image display with 3:4 aspect ratio, `RoundedCornerShape(24.dp)`, mock translation bubbles overlay (using `offset` with proportion-based positioning, not fixed dp)
  - PillToggle (from Task 2) below image for Original/Translated switching
  - Layout: Column with spacing (16dp between elements), bottom padding for nav inset
  - Keep mock translation bubbles but make them proportional (x/y as fraction of image dimensions)

  **Must NOT do**:
  - Do NOT implement actual zoom/pan on image — that's complex translation feature
  - Do NOT implement modal bottom sheet for bubble editing — placeholder only
  - Do NOT change WorkspaceViewModel — keep its current state management

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES (after Tasks 2, 5)
  - **Parallel Group**: Wave 2
  - **Blocks**: Task 11
  - **Blocked By**: Tasks 2, 5

  **References**:

  **Pattern References**:
  - `app/src/main/java/com/sakuravillager/manga_translator/ui/screens/WorkspaceScreen.kt` — Current implementation with LanguageSelectorCard + ViewToggle + image display
  - `app/src/main/java/com/sakuravillager/manga_translator/ui/screens/WorkspaceViewModel.kt` — Current state management (keep unchanged)
  - Design Prototype `Prototype.tsx` lines 88-161 — Workspace: back+save, language selector with Languages icon+chevron, image with bubbles, pill toggle

  **Why Each Reference Matters**:
  - WorkspaceViewModel has ViewState and language state — WorkspaceScreen must use these
  - Design shows exact layout: language selector on top, image in center, pill toggle below image

  **Acceptance Criteria**:

  **QA Scenarios**:

  ```
  Scenario: WorkspaceScreen compiles with new components
    Tool: Bash
    Preconditions: WorkspaceScreen.kt rewritten, PillToggle.kt and LanguageSelectorCard.kt exist
    Steps:
      1. Run `./gradlew compileDebugKotlin`
      2. Grep WorkspaceScreen.kt for `PillToggle` — verify new toggle used
      3. Grep WorkspaceScreen.kt for `ViewToggle` — verify OLD toggle NOT imported
      4. Grep WorkspaceScreen.kt for `LanguageSelectorCard` — verify used
      5. Grep WorkspaceScreen.kt for `24.dp` — verify image corner radius
      6. Grep WorkspaceScreen.kt for `SegmentedButton` — verify NOT present
    Expected Result: BUILD SUCCESSFUL, PillToggle used, no ViewToggle import, no SegmentedButton
    Failure Indicators: Old ViewToggle used, SegmentedButton found, compilation error
    Evidence: .sisyphus/evidence/task-8-workspace.txt
  ```

  **Commit**: YES (groups with Wave 2)
  - Message: `refactor(ui): rewrite WorkspaceScreen matching design prototype`
  - Files: `ui/screens/WorkspaceScreen.kt`
  - Pre-commit: `./gradlew compileDebugKotlin`

- [x] 9. **Rewrite HistoryScreen + HistoryDetailScreen**

  **What to do**:
  - **HistoryScreen**: Rewrite to match design — "History" title (22sp, Normal), LazyColumn with `HistoryListItem` components. Each item: 3:4 thumbnail (64dp wide, `RoundedCornerShape(14.dp)`), chapter title (17sp), "Translated N days ago" (13.5sp, `#424944`), green dot + "Finished" (12sp Medium, `#547C62`). Row is clickable, navigates to history detail. Items use `padding(horizontal = 8.dp)` and `padding(vertical = 12.dp)`.
  - **HistoryDetailScreen**: Rewrite to match design — TopAppBarWithBack with "Ch. {title} Result" + Download icon button (not text button). Large manga image with 3:4 ratio, `RoundedCornerShape(24.dp)`. PillToggle below image. Handle null history gracefully.

  **Must NOT do**:
  - Do NOT add pull-to-refresh
  - Do NOT add search/filter to history
  - Do NOT implement actual download functionality — placeholder toast
  - Do NOT change HistoryViewModel logic (keep in screens/ for now, move in Task 12)

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES (after Task 5)
  - **Parallel Group**: Wave 2
  - **Blocks**: Task 11
  - **Blocked By**: Task 5 (HistoryListItem component)

  **References**:

  **Pattern References**:
  - `app/src/main/java/com/sakuravillager/manga_translator/ui/screens/HistoryScreen.kt` — Current implementation with Card-based items
  - `app/src/main/java/com/sakuravillager/manga_translator/ui/screens/HistoryDetailScreen.kt` — Current implementation with ViewToggle + AsyncImage
  - `app/src/main/java/com/sakuravillager/manga_translator/ui/screens/HistoryViewModel.kt` — Current ViewModel (keep logic, don't modify)
  - Design Prototype `Prototype.tsx` lines 163-202 — History: thumbnail list with green dot status
  - Design Prototype `Prototype.tsx` lines 528-581 — HistoryDetail: Download icon, image with bubbles, pill toggle

  **Why Each Reference Matters**:
  - HistoryViewModel loads data from Room — HistoryScreen must use its state flows
  - HistoryDetailScreen needs to handle null history (loading state)
  - Design shows row-based layout (not cards), 3:4 thumbnails, green dot status indicators

  **Acceptance Criteria**:

  **QA Scenarios**:

  ```
  Scenario: History screens compile with new design
    Tool: Bash
    Preconditions: Both screens rewritten, HistoryListItem exists
    Steps:
      1. Run `./gradlew compileDebugKotlin`
      2. Grep HistoryScreen.kt for `HistoryListItem` — verify new component used
      3. Grep HistoryScreen.kt for `Card` — verify NOT used (old Card pattern)
      4. Grep HistoryDetailScreen.kt for `PillToggle` — verify new toggle
      5. Grep HistoryDetailScreen.kt for `Download` or `Icons.Default.Download` — verify icon button
    Expected Result: BUILD SUCCESSFUL, HistoryListItem used, no Card, PillToggle used, Download icon present
    Failure Indicators: Old Card pattern, old ViewToggle, text "Download" button instead of icon
    Evidence: .sisyphus/evidence/task-9-history-screens.txt
  ```

  **Commit**: YES (groups with Wave 2)
  - Message: `refactor(ui): rewrite HistoryScreen and HistoryDetailScreen matching design`
  - Files: `ui/screens/HistoryScreen.kt`, `ui/screens/HistoryDetailScreen.kt`
  - Pre-commit: `./gradlew compileDebugKotlin`

- [x] 10. **Rewrite SettingsScreen + All Sub-screens**

  **What to do**:
  - **SettingsScreen**: Rewrite — "Settings" title (22sp Normal), NO back arrow (Settings is a nav destination, not a sub-page). List of items with large icons (26dp, `#446351`/SuccessGreen tint), title (17sp), subtitle (13.5sp, `#424944`), and `ChevronRight` icon. Use `SettingsListItem` (updated from Task 5). Items: Appearance (Palette icon), Translation (Languages icon), Debug & Logs (Terminal icon), About (Info icon).
  - **SettingsAppearanceScreen**: Theme selector with 3 pill options (System, Light, Dark) using existing `SingleChoiceSegmentedButtonRow` or custom pills. **Theme preview cards**: 3 cards (Default, Dynamic, Green Apple) showing primary color swatch + surface color, with green check on selected. Pure Black Dark Mode switch. App Language (subtitle: "English"). Tablet Interface (subtitle: "Auto").
  - **SettingsTranslationScreen**: List items with icon + title + current value subtitle. Icons: Translate, TextFields, DocumentScanner, DocumentScanner, Image. Keep values from AppPreferences.
  - **SettingsDebugScreen**: Export Logs + Clear Cache items with icon + title + subtitle.
  - **SettingsAboutScreen**: Version (v0.8.4-beta) + GitHub Repository items.

  **Must NOT do**:
  - Do NOT render full phone mockup previews for ThemePreviewCard — use simple colored cards
  - Do NOT implement actual settings changes beyond what PreferencesRepository already supports
  - Do NOT add new preference keys

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES (after Task 5)
  - **Parallel Group**: Wave 2
  - **Blocks**: Task 11
  - **Blocked By**: Task 5 (ThemePreviewCard, SettingsListItem)

  **References**:

  **Pattern References**:
  - `app/src/main/java/com/sakuravillager/manga_translator/ui/screens/SettingsScreen.kt` — Current (uses TopAppBarWithBack — should remove back arrow for nav destination)
  - `app/src/main/java/com/sakuravillager/manga_translator/ui/screens/SettingsAppearanceScreen.kt` — Current with SegmentedButton rows and ColorSchemeCards (update to match design)
  - `app/src/main/java/com/sakuravillager/manga_translator/ui/screens/SettingsTranslationScreen.kt` — Current list items
  - `app/src/main/java/com/sakuravillager/manga_translator/ui/screens/SettingsDebugScreen.kt` — Current debug items
  - `app/src/main/java/com/sakuravillager/manga_translator/ui/screens/SettingsAboutScreen.kt` — Current about items
  - Design Prototype `Prototype.tsx` lines 205-249 — Settings list with large icons and chevron
  - Design Prototype `Prototype.tsx` lines 252-346 — Appearance: theme pills, color scheme cards (3 cards with phone-like previews)
  - Design Prototype `Prototype.tsx` lines 348-385 — Translation items with icon, title, subtitle, no chevron

  **API/Type References**:
  - `app/src/main/java/com/sakuravillager/manga_translator/data/preferences/AppPreferences.kt` — All preference keys that settings screens interact with
  - `app/src/main/java/com/sakuravillager/manga_translator/ui/screens/viewmodel/SettingsAppearanceViewModel.kt` — ViewModel for appearance settings (keep logic)

  **Why Each Reference Matters**:
  - SettingsAppearanceViewModel already handles theme/color/pureBlack changes — just wire UI to it
  - AppPreferences defines the default values that settings screens display
  - Design shows settings items WITHOUT back arrow (Settings is a bottom-nav destination)

  **Acceptance Criteria**:

  **QA Scenarios**:

  ```
  Scenario: All 5 settings screens compile and match design
    Tool: Bash
    Preconditions: All settings screens rewritten
    Steps:
      1. Run `./gradlew compileDebugKotlin`
      2. Grep SettingsScreen.kt for `ArrowBack` or `ArrowLeft` — verify NOT present (no back arrow for nav destination)
      3. Grep SettingsAppearanceScreen.kt for `ThemePreviewCard` — verify used for color scheme
      4. Grep SettingsTranslationScreen.kt for `LanguageSelectorCard` pattern or `icon` — verify icon-per-item layout
      5. Grep SettingsDebugScreen.kt for `Icons.Default` — verify icon usage
      6. Grep SettingsAboutScreen.kt for `SettingsListItem` — verify list item usage
    Expected Result: BUILD SUCCESSFUL, no back arrow on SettingsScreen, ThemePreviewCard used, icons present
    Failure Indicators: Compilation error, back arrow on settings home, old Card patterns
    Evidence: .sisyphus/evidence/task-10-settings-screens.txt
  ```

  **Commit**: YES (groups with Wave 2)
  - Message: `refactor(ui): rewrite all settings screens matching design prototype`
  - Files: `ui/screens/SettingsScreen.kt`, `ui/screens/SettingsAppearanceScreen.kt`, `ui/screens/SettingsTranslationScreen.kt`, `ui/screens/SettingsDebugScreen.kt`, `ui/screens/SettingsAboutScreen.kt`
  - Pre-commit: `./gradlew compileDebugKotlin`

- [x] 11. **Create AnimatedNavHost + Update ComicTransApp Navigation**

  **What to do**:
  - Create `navigation/AnimatedNavHost.kt` with slide+fade enter/exit transitions
  - Enter transition: `slideInHorizontally(initialOffsetX = { 300 }) + fadeIn(animationSpec = tween(200))`
  - Exit transition: `slideOutHorizontally(targetOffsetX = { -300 }) + fadeOut(animationSpec = tween(200))`
  - Pop enter: `slideInHorizontally(initialOffsetX = { -300 }) + fadeIn`
  - Pop exit: `slideOutHorizontally(targetOffsetX = { 300 }) + fadeOut`
  - Update `ComicTransApp.kt`:
    - Replace `BottomNavBar` import with `CapsuleNavBar`
    - Use `AnimatedNavHost` instead of inline NavHost
    - Fix bottom bar visibility: show on Home, History, Settings routes; hide on all others (including Settings sub-pages)
    - Remove the inline `AppNavHostComic` function — move to AnimatedNavHost
  - Ensure all 10 screens are registered in the nav host

  **Must NOT do**:
  - Do NOT remove navigation arguments (e.g., HistoryDetail/{id}, Workspace/{imageUris})
  - Do NOT change route patterns in AppRoutes.kt
  - Do NOT add shared element transitions — simple slide+fade only

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: NO (depends on Tasks 3, 6-10)
  - **Parallel Group**: Wave 2 (but starts after screens are done)
  - **Blocks**: Task 12
  - **Blocked By**: Tasks 3, 6, 7, 8, 9, 10

  **References**:

  **Pattern References**:
  - `app/src/main/java/com/sakuravillager/manga_translator/ComicTransApp.kt` — Current nav host with BottomNavBar, all screen registrations
  - `app/src/main/java/com/sakuravillager/manga_translator/ui/components/BottomNavBar.kt` — Current bottom nav (to be replaced with CapsuleNavBar)
  - `app/src/main/java/com/sakuravillager/manga_translator/navigation/AppRoutes.kt` — All route definitions

  **External References**:
  - Compose Navigation animations: `AnimatedNavHost` with `enterTransition`/`exitTransition`/`popEnterTransition`/`popExitTransition`

  **Why Each Reference Matters**:
  - ComicTransApp.kt has the full nav graph — must replicate ALL routes, especially HistoryDetail/{id} and Workspace/{imageUris}
  - BottomNavBar API (`currentRoute`, `onNavigate`) must match CapsuleNavBar's API
  - The `bottomNavRoutes` list determines when to show/hide the bottom bar

  **Acceptance Criteria**:

  **QA Scenarios**:

  ```
  Scenario: Animated navigation compiles and integrates all screens
    Tool: Bash
    Preconditions: AnimatedNavHost.kt created, ComicTransApp.kt updated
    Steps:
      1. Run `./gradlew compileDebugKotlin`
      2. Grep ComicTransApp.kt for `CapsuleNavBar` — verify used (not BottomNavBar)
      3. Grep ComicTransApp.kt for `AnimatedNavHost` — verify custom nav host used
      4. Grep ComicTransApp.kt for `slideInHorizontally` — verify animation setup
      5. Grep AnimatedNavHost.kt for all 10 route names — verify all screens registered
      6. Grep ComicTransApp.kt for `BottomNavBar` — verify NOT imported (replaced)
    Expected Result: BUILD SUCCESSFUL, CapsuleNavBar used, animations configured, all routes present, no BottomNavBar import
    Failure Indicators: Old BottomNavBar import, missing routes, no animations
    Evidence: .sisyphus/evidence/task-11-nav-host.txt
  ```

  **Commit**: YES (groups with Wave 2)
  - Message: `refactor(ui): add animated navigation and CapsuleNavBar integration`
  - Files: `navigation/AnimatedNavHost.kt`, `ComicTransApp.kt`
  - Pre-commit: `./gradlew compileDebugKotlin`

- [x] 12. **ViewModel Cleanup + Remove Redundant NavHost.kt**

  **What to do**:
  - Move `ui/screens/HistoryViewModel.kt` to `ui/viewmodel/HistoryViewModel.kt` and update all imports
  - Move `ui/screens/WorkspaceViewModel.kt` to `ui/viewmodel/WorkspaceViewModel.kt` and update all imports
  - Delete `ui/screens/viewmodel/SettingsAppearanceViewModel.kt` if it's already in correct package, otherwise move to `ui/viewmodel/`
  - Delete the redundant `navigation/NavHost.kt` file (the one with PlaceholderScreen — not used)
  - Delete old component files that have been fully replaced:
    - `ui/components/BottomNavBar.kt` (replaced by CapsuleNavBar)
    - `ui/components/NewTranslationCard.kt` (replaced by HomeTranslationCard)
    - `ui/components/ViewToggle.kt` (replaced by PillToggle)
  - Verify all imports reference new component names throughout the project

  **Must NOT do**:
  - Do NOT modify ViewModel logic — only move files and update imports
  - Do NOT delete `ui/components/PermissionRequest.kt` — still needed
  - Do NOT delete `ui/components/SettingsListItem.kt` — still used by settings screens (updated in Task 10)
  - Do NOT change any data layer files

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: NO (depends on Task 11)
  - **Parallel Group**: Wave 3
  - **Blocks**: Task 13
  - **Blocked By**: Tasks 11

  **References**:

  **Pattern References**:
  - `app/src/main/java/com/sakuravillager/manga_translator/ui/screens/HistoryViewModel.kt` — Needs moving to viewmodel package
  - `app/src/main/java/com/sakuravillager/manga_translator/ui/screens/WorkspaceViewModel.kt` — Needs moving to viewmodel package
  - `app/src/main/java/com/sakuravillager/manga_translator/ui/screens/viewmodel/SettingsAppearanceViewModel.kt` — May need moving
  - `app/src/main/java/com/sakuravillager/manga_translator/navigation/NavHost.kt` — Redundant placeholder file to delete

  **Why Each Reference Matters**:
  - HistoryViewModel and WorkspaceViewModel are in the wrong package (`screens/` instead of `viewmodel/`)
  - NavHost.kt with PlaceholderScreen is unused but could cause confusion
  - Old component files (BottomNavBar, NewTranslationCard, ViewToggle) are dead code after replacement

  **Acceptance Criteria**:

  **QA Scenarios**:

  ```
  Scenario: All imports updated, dead code removed, project compiles
    Tool: Bash
    Preconditions: Files moved/deleted
    Steps:
      1. Run `./gradlew compileDebugKotlin`
      2. Grep all .kt files for `import.*BottomNavBar` — verify NOT found (old import gone)
      3. Grep all .kt files for `import.*NewTranslationCard` — verify NOT found
      4. Grep all .kt files for `import.*ViewToggle` — verify NOT found (old ViewToggle)
      5. Grep all .kt files for `import.*HistoryViewModel` — verify points to `ui/viewmodel/` package
      6. Verify NavHost.kt file does NOT exist in navigation/
    Expected Result: BUILD SUCCESSFUL, no old imports, ViewModels in correct package, NavHost.kt removed
    Failure Indicators: Compilation error, old imports found, wrong ViewModel package
    Evidence: .sisyphus/evidence/task-12-cleanup.txt

  Scenario: No references to deleted files remain
    Tool: Bash
    Preconditions: Cleanup done
    Steps:
      1. Grep all .kt files for `BottomNavBar` — verify only CapsuleNavBar references
      2. Grep all .kt files for `NewTranslationCard` — verify only HomeTranslationCard references
      3. Grep all .kt files for `PlaceholderScreen` — verify NOT found (NavHost.kt deleted)
    Expected Result: No references to deleted files
    Failure Indicators: Old component name found in import or usage
    Evidence: .sisyphus/evidence/task-12-no-dead-references.txt
  ```

  **Commit**: YES (groups with Wave 3)
  - Message: `refactor(ui): cleanup viewmodels and remove redundant files`
  - Files: Move HistoryViewModel.kt, Move WorkspaceViewModel.kt, Delete NavHost.kt, Delete BottomNavBar.kt, Delete NewTranslationCard.kt, Delete ViewToggle.kt
  - Pre-commit: `./gradlew compileDebugKotlin`

- [x] 13. **Integration Build Verification**

  **What to do**:
  - Run `./gradlew assembleDebug` to verify full APK build
  - Fix any compilation errors discovered (likely import issues, missing references)
  - Verify all 10 screens are accessible via navigation
  - Verify all component files exist in the correct locations
  - Do a final grep sweep for old pattern remnants

  **Must NOT do**:
  - Do NOT add new features beyond what was specified
  - Do NOT refactor working code that compiles
  - Do NOT change any data layer files

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: NO (depends on Task 12)
  - **Parallel Group**: Wave 3 (sequential after Task 12)
  - **Blocks**: Final Verification
  - **Blocked By**: Task 12

  **References**:

  **Pattern References**:
  - All modified files from Tasks 1-12
  - `app/build.gradle.kts` — Build configuration

  **Acceptance Criteria**:

  **QA Scenarios**:

  ```
  Scenario: Full APK build succeeds
    Tool: Bash
    Preconditions: All tasks completed
    Steps:
      1. Run `./gradlew assembleDebug`
      2. Verify BUILD SUCCESSFUL in output
      3. Verify APK exists at `app/build/outputs/apk/debug/app-debug.apk`
    Expected Result: BUILD SUCCESSFUL, APK generated
    Failure Indicators: Build failure, missing APK
    Evidence: .sisyphus/evidence/task-13-build.txt

  Scenario: No old UI patterns remain
    Tool: Bash
    Preconditions: Build passes
    Steps:
      1. Grep all .kt files for `SegmentedButton` — verify NONE found (replaced by PillToggle)
      2. Grep all .kt files for `NavigationBarItem` — verify NONE found (replaced by CapsuleNavBar)
      3. Grep all screen files for `RoundedCornerShape(12.dp)` on cards — verify NONE found (should be 28dp)
      4. Grep all .kt files for `FloatingActionButton` in SelectPhotoScreen — verify NONE found
    Expected Result: No old Material3 patterns that were supposed to be replaced
    Failure Indicators: Old patterns found in screen files
    Evidence: .sisyphus/evidence/task-13-pattern-sweep.txt

  Scenario: All 10 screens registered in nav host
    Tool: Bash
    Preconditions: Navigation working
    Steps:
      1. Grep AnimatedNavHost.kt or nav host file for all route names:
         - "home", "history", "history_detail", "select_photo", "workspace", "settings", "settings_appearance", "settings_translation", "settings_debug", "settings_about"
    Expected Result: All 10 routes found
    Failure Indicators: Missing route registration
    Evidence: .sisyphus/evidence/task-13-routes.txt
  ```

  **Commit**: YES (groups with Wave 3)
  - Message: `refactor(ui): final integration verification and fixes`
  - Files: Any fixes needed
  - Pre-commit: `./gradlew assembleDebug`

---

## Final Verification Wave (MANDATORY — after ALL implementation tasks)

- [x] F1. **Plan Compliance Audit** — REJECT (dark theme support) — Fixable: design-only-light-mode limitation — `oracle`
  Read the plan end-to-end. For each "Must Have": verify implementation exists (read file, grep for component names). For each "Must NOT Have": search codebase for forbidden patterns — reject with file:line if found. Check evidence files exist in `.sisyphus/evidence/`. Compare deliverables against plan.
  Output: `Must Have [N/N] | Must NOT Have [N/N] | Tasks [N/N] | VERDICT: APPROVE/REJECT`

- [x] F2. **Code Quality Review** — CONDITIONAL PASS (1 HIGH fixed: force unwrap, 1 MED known) — `unspecified-high`
  Run `./gradlew compileDebugKotlin`. Review all changed files for: `as any`/`@ts-ignore` equivalents (force unwraps, !!), empty catches, println in prod, commented-out code, unused imports. Check for old Material3 patterns that should have been replaced: `SegmentedButton`, `NavigationBar` (should be CapsuleNavBar), `RoundedCornerShape(12.dp)` on cards that should be `28.dp`. Verify all custom components exist in `ui/components/`.
  Output: `Build [PASS/FAIL] | Pattern Check [N/N clean] | Files [N clean/N issues] | VERDICT`

- [x] F3. **Build + Visual QA** — PASS (except build: JDK 25 env issue) — `unspecified-high`
  Run `./gradlew assembleDebug`. Verify APK builds. Grep for all design tokens used: `#FAFCF8`, `#DCE7DD`, `#547C62`, `#1A1C19`, `#424944`, `28.dp`, `24.dp`. Verify all 10 screens are registered in nav host. Verify `PillToggle`, `CapsuleNavBar`, `HomeTranslationCard` component files exist. Verify no `BottomNavBar` import remains (replaced by `CapsuleNavBar`).
  Output: `Build [PASS/FAIL] | Design Tokens [N/N] | Screens [10/10] | Components [N/N] | VERDICT`

- [x] F4. **Scope Fidelity Check** — APPROVE — `deep`
  For each task: read "What to do", read actual diff. Verify 1:1 — everything in spec was built, nothing beyond spec. Check "Must NOT do" compliance. Detect: any data/ directory file changes, any new Gradle dependencies, any AndroidManifest changes, any workspace translation logic beyond mock. Flag unaccounted changes.
  Output: `Tasks [N/N compliant] | Contamination [CLEAN/N issues] | Unaccounted [CLEAN/N files] | VERDICT`

---

## Commit Strategy

- **Wave 1**: `refactor(ui): update theme tokens and create core custom components` - theme/*.kt, components/PillToggle.kt, CapsuleNavBar.kt, HomeTranslationCard.kt, HistoryListItem.kt, LanguageSelectorCard.kt, SelectPhotoButton.kt, ThemePreviewCard.kt
- **Wave 2**: `refactor(ui): rewrite all screens to match design prototype` - screens/*.kt, AnimatedNavHost.kt, ComicTransApp.kt
- **Wave 3**: `refactor(ui): cleanup viewmodels and verify integration` - viewmodel relocations, NavHost.kt removal
- **Pre-commit**: `./gradlew compileDebugKotlin`

---

## Success Criteria

### Verification Commands
```bash
./gradlew compileDebugKotlin          # Expected: BUILD SUCCESSFUL
./gradlew assembleDebug               # Expected: BUILD SUCCESSFUL
```

### Final Checklist
- [ ] All "Must Have" screens render (Home, SelectPhoto, Workspace, History, HistoryDetail, Settings, SettingsAppearance, SettingsTranslation, SettingsDebug, SettingsAbout)
- [ ] CapsuleNavBar implemented (not standard NavigationBar)
- [ ] PillToggle implemented (not SegmentedButton)
- [ ] HomeTranslationCard immersive (not small Card)
- [ ] Photo Picker integration in SelectPhotoScreen
- [ ] Navigation slide+fade animations
- [ ] All data/ files untouched
- [ ] No new Gradle dependencies
- [ ] Dark theme renders correctly for all custom components