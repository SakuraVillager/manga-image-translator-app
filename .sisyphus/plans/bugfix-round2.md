# Bug Fixes & Log Export (Round 2)

## TL;DR

> **Quick Summary**: Fix 3 issues — (1) bottom nav bar highlight should only apply to icon not text, (2) build log export feature from scratch with preview/copy/share/clear, (3) fix History page crash caused by uninitialized DatabaseProvider.
> 
> **Deliverables**:
> - CapsuleNavBar with icon-only highlight background
> - AppLogger singleton with in-memory ring buffer (500 entries)
> - SettingsDebugViewModel + rewritten SettingsDebugScreen with log preview, copy, share, clear
> - FileProvider configuration for log file sharing
> - Logging calls added to key existing features
> - DatabaseProvider initialized in MainActivity.onCreate() + MockDataSeeder enabled
> 
> **Estimated Effort**: Medium
> **Parallel Execution**: YES - 3 waves
> **Critical Path**: Task 3 → Task 4 → Task 5

---

## Context

### Original Request
用户报告3个问题：
1. 底部导航栏选中后整个图标+文字背景都高亮，只需要图标背景高亮
2. 日志导出功能需要完善（当前只是占位符）
3. History页面点击闪退

### Interview Summary
**Key Discussions**:
- Issue 1: CapsuleNavBar.kt 的 `.clip()` + `.background()` 应用在整个 Column 上，需移到 Icon 的 Box 上
- Issue 2: 整个应用零日志基础设施，需从头构建。用户要求：日志预览、复制按钮在预览框右上角、系统分享导出、清空日志。已实现的关键功能需要日志记录
- Issue 3: `DatabaseProvider.dao` lateinit 从未初始化，HistoryViewModel 构造函数访问时崩溃
- MockDataSeeder: 用户确认启用，方便测试

**Research Findings**:
- CapsuleNavBar.kt: 90行，Column 包裹 Icon+Text，background 在 Column 上
- SettingsDebugScreen.kt: 占位符 Toast，无 ViewModel
- HistoryViewModel.kt: `dao = DatabaseProvider.dao` 构造函数默认参数立即访问未初始化 lateinit
- DatabaseProvider.kt: `lateinit var dao`，只在 `getDatabase(context)` 中初始化
- MainActivity.kt: 只调用 PreferencesProvider.initialize，未调用 DatabaseProvider.getDatabase
- 无 FileProvider 配置，无 file_paths.xml
- 无 Application 子类
- Min SDK 28, Target SDK 35

### Metis Review
**Identified Gaps** (addressed):
- Ring buffer capacity: 默认500条，内存约75KB
- 日志持久化: 纯内存，重启丢失（用户未提及持久化需求）
- 线程安全: 使用 ConcurrentHashMap 或 synchronized
- FileProvider: 需添加 manifest 配置 + file_paths.xml
- "已实现关键功能"的范围: 明确列出需要添加日志的文件和函数
- MockDataSeeder: 用户确认启用

---

## Work Objectives

### Core Objective
修复导航栏样式、构建完整日志系统、修复History闪退，使应用UI和稳定性达到可用状态。

### Concrete Deliverables
- `CapsuleNavBar.kt` — 图标背景高亮，文字无背景
- `AppLogger.kt` + `LogEntry.kt` — 日志框架（内存环形缓冲区500条）
- `SettingsDebugViewModel.kt` — 日志状态管理
- `SettingsDebugScreen.kt` — 日志预览 + 复制/分享/清空
- `file_paths.xml` + manifest 更新 — FileProvider分享配置
- 现有关键功能文件添加 `AppLogger` 调用
- `MainActivity.kt` — DatabaseProvider 初始化 + MockDataSeeder 启用

### Definition of Done
- [x] `./gradlew assembleDebug` 构建成功（使用 JDK 21）
- [x] 底部导航栏只有图标有高亮背景
- [x] SettingsDebug 页面可预览、复制、分享、清空日志
- [x] History 页面可正常导航，不闪退
- [x] 已实现的关键功能有日志记录

### Must Have
- CapsuleNavBar 选中时只有图标有背景高亮
- AppLogger 支持 DEBUG/INFO/WARN/ERROR 四个级别
- 日志预览页面有滚动列表
- 预览框右上角有复制按钮
- 系统分享功能通过 ShareCompat
- 清空日志按钮
- DatabaseProvider 在 MainActivity.onCreate() 中初始化
- MockDataSeeder.seedIfNeeded() 被调用

### Must NOT Have (Guardrails)
- 不添加新的 Gradle 依赖（使用 android.util.Log + 自定义环形缓冲区）
- 不重构 ViewModel 构造函数或 DI 模式
- 不触及无关文件（SettingsTranslationScreen, SettingsAboutScreen 等）
- 不对所有43个kt文件添加日志，只对指定的关键功能
- 不添加自动测试框架
- 不实现日志持久化到文件（纯内存）
- 不修改 HistoryViewModel 的构造函数参数

---

## Verification Strategy

> **ZERO HUMAN INTERVENTION** - ALL verification is agent-executed. No exceptions.

### Test Decision
- **Infrastructure exists**: NO
- **Automated tests**: None (用户选择仅 QA 场景)
- **Framework**: N/A

### QA Policy
Every task MUST include agent-executed QA scenarios.
Evidence saved to `.sisyphus/evidence/task-{N}-{scenario-slug}.{ext}`.

- **UI验证**: Use Playwright (playwright skill) - screenshot comparison
- **构建验证**: Use Bash - `./gradlew compileDebugKotlin` / `assembleDebug`
- **代码验证**: Use Grep - 验证关键代码存在

---

## Execution Strategy

### Parallel Execution Waves

```
Wave 1 (Start Immediately - independent fixes):
├── Task 1: Fix CapsuleNavBar icon-only highlight [quick]
├── Task 2: Initialize DatabaseProvider + enable MockDataSeeder [quick]
└── Task 3: Create AppLogger + LogEntry [quick]

Wave 2 (After Wave 1 - depends on Task 3):
├── Task 4: Create SettingsDebugViewModel (depends: 3) [quick]
└── Task 5: Add FileProvider config for log sharing (no deps) [quick]

Wave 3 (After Wave 2 - integration):
├── Task 6: Rewrite SettingsDebugScreen (depends: 4, 5) [unspecified-high]
└── Task 7: Add logging to key features (depends: 3) [unspecified-high]

Wave FINAL (After ALL tasks — 4 parallel reviews):
├── Task F1: Plan compliance audit (oracle)
├── Task F2: Code quality review (unspecified-high)
├── Task F3: Real manual QA (unspecified-high)
└── Task F4: Scope fidelity check (deep)
-> Present results -> Get explicit user okay

Critical Path: Task 3 → Task 4 → Task 6
Parallel Speedup: ~50% faster than sequential
Max Concurrent: 3 (Wave 1)
```

### Dependency Matrix

| Task | Depends On | Blocks | Wave |
|------|-----------|--------|------|
| 1 | - | - | 1 |
| 2 | - | - | 1 |
| 3 | - | 4, 7 | 1 |
| 4 | 3 | 6 | 2 |
| 5 | - | 6 | 2 |
| 6 | 4, 5 | - | 3 |
| 7 | 3 | - | 3 |

### Agent Dispatch Summary

- **Wave 1**: 3 tasks - T1 → `quick`, T2 → `quick`, T3 → `quick`
- **Wave 2**: 2 tasks - T4 → `quick`, T5 → `quick`
- **Wave 3**: 2 tasks - T6 → `unspecified-high`, T7 → `unspecified-high`
- **FINAL**: 4 tasks - F1 → `oracle`, F2 → `unspecified-high`, F3 → `unspecified-high`, F4 → `deep`

---

## TODOs

- [x] 1. Fix CapsuleNavBar Icon-Only Highlight

  **What to do**:
  - In `CapsuleNavBar.kt`, move `.clip(RoundedCornerShape(percent = 50))` and `.background(Color(0xFFDCE7DD))` from the outer `Column` to a `Box` that wraps only the `Icon`
  - Keep `.clickable` on the outer `Column` so clicking text area still navigates
  - Adjust padding: remove `horizontal = 20.dp` from Column padding, add appropriate padding on the icon Box (e.g., `padding(8.dp)`) so the capsule background is nicely sized around the icon
  - Keep icon tint and text color unchanged (active: `Color(0xFF1A1C19)`, inactive: `Color(0xFF424944)`)
  - Add `Box` import if not present

  **Must NOT do**:
  - Do NOT change the icon tint or text color logic
  - Do NOT add any new dependencies
  - Do NOT change the nav items list or routes
  - Do NOT remove the `clickable` from the Column

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: Single file, small targeted UI change
  - **Skills**: []
  - **Skills Evaluated but Omitted**:
    - `playwright`: Not needed for code change, only for QA verification

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with Tasks 2, 3)
  - **Blocks**: None
  - **Blocked By**: None (can start immediately)

  **References**:

  **Pattern References** (existing code to follow):
  - `app/src/main/java/com/sakuravillager/manga_translator/ui/components/CapsuleNavBar.kt:63-86` - Current Column structure with Icon + Text, `.clip()` and `.background()` on Column. This is the EXACT code to modify.
  - `app/src/main/java/com/sakuravillager/manga_translator/ui/components/PillToggle.kt` - Example of Box + background pattern in the same project. Shows how to apply background to a smaller container.

  **API/Type References**:
  - `Modifier.clip(RoundedCornerShape(percent = 50))` - Creates pill/capsule shape
  - `Modifier.background(Color(0xFFDCE7DD))` - The highlight color (light green)
  - `Modifier.clickable { }` - Must remain on the outer Column for tap-on-text navigation

  **External References**:
  - Compose Modifier order: `clip` must come before `background` for proper shape clipping

  **WHY Each Reference Matters**:
  - CapsuleNavBar.kt:63-86 contains the EXACT lines that need restructuring — the Column modifier chain and Icon/Text composables
  - PillToggle.kt shows the project's existing pattern of Box + background for small UI elements

  **Acceptance Criteria**:

  **QA Scenarios (MANDATORY):**

  ```
  Scenario: Active nav item shows icon-only background
    Tool: Bash (grep)
    Preconditions: Code changes applied
    Steps:
      1. grep for `Modifier.background(Color(0xFFDCE7DD))` in CapsuleNavBar.kt
      2. Verify it is NOT inside the Column modifier chain
      3. Verify it IS inside a Box that wraps the Icon
    Expected Result: Background modifier found only in Icon's Box, not in Column
    Failure Indicators: Background modifier found in Column modifier chain
    Evidence: .sisyphus/evidence/task-1-icon-only-bg.txt

  Scenario: Clickable remains on Column for text tap navigation
    Tool: Bash (grep)
    Preconditions: Code changes applied
    Steps:
      1. grep for `.clickable` in CapsuleNavBar.kt
      2. Verify `.clickable` is on the Column modifier, not on the inner Box
    Expected Result: `.clickable` found on Column modifier chain
    Failure Indicators: `.clickable` moved to inner Box only
    Evidence: .sisyphus/evidence/task-1-clickable-on-column.txt
  ```

  **Commit**: YES
  - Message: `fix(ui): nav bar icon-only highlight`
  - Files: `app/src/main/java/com/sakuravillager/manga_translator/ui/components/CapsuleNavBar.kt`
  - Pre-commit: `./gradlew compileDebugKotlin` (with JAVA_HOME set to Android Studio JBR)

- [x] 2. Initialize DatabaseProvider + Enable MockDataSeeder

  **What to do**:
  - In `MainActivity.kt`, add `DatabaseProvider.getDatabase(this)` after `PreferencesProvider.initialize(this)` (around line 25)
  - Add `MockDataSeeder.seedIfNeeded(applicationContext)` after database initialization
  - Import `com.sakuravillager.manga_translator.data.local.DatabaseProvider` and `com.sakuravillager.manga_translator.data.local.MockDataSeeder`
  - The `seedIfNeeded` is a `suspend` function, so launch it in a coroutine: `lifecycleScope.launch { MockDataSeeder.seedIfNeeded(applicationContext) }`
  - Add `import androidx.lifecycle.lifecycleScope` and `import kotlinx.coroutines.launch`

  **Must NOT do**:
  - Do NOT refactor HistoryViewModel's constructor
  - Do NOT change the DatabaseProvider singleton pattern
  - Do NOT remove or modify MockDataSeeder.kt
  - Do NOT change the database schema or migration logic

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: 2-line addition to existing file
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with Tasks 1, 3)
  - **Blocks**: None
  - **Blocked By**: None (can start immediately)

  **References**:

  **Pattern References**:
  - `app/src/main/java/com/sakuravillager/manga_translator/MainActivity.kt:25` - Current `PreferencesProvider.initialize(this)` call — add `DatabaseProvider.getDatabase(this)` right after this line
  - `app/src/main/java/com/sakuravillager/manga_translator/data/local/DatabaseProvider.kt:8-20` - The `getDatabase(context)` method that initializes `dao`. Shows the singleton pattern.

  **API/Type References**:
  - `app/src/main/java/com/sakuravillager/manga_translator/data/local/MockDataSeeder.kt` - `seedIfNeeded(context)` is a `suspend fun` that checks if mock data has been seeded and seeds 3 entries if not.

  **WHY Each Reference Matters**:
  - MainActivity.kt:25 shows WHERE to add the database init call (right after preferences init)
  - DatabaseProvider.kt shows that `getDatabase()` must be called before any code accesses `dao`
  - MockDataSeeder.kt shows the function signature — it's a suspend function needing a coroutine scope

  **Acceptance Criteria**:

  **QA Scenarios (MANDATORY):**

  ```
  Scenario: DatabaseProvider is initialized on app start
    Tool: Bash (grep)
    Preconditions: Code changes applied
    Steps:
      1. grep for `DatabaseProvider.getDatabase` in MainActivity.kt
      2. Verify it's called inside `onCreate()`
      3. Verify it's called AFTER `PreferencesProvider.initialize`
    Expected Result: Found `DatabaseProvider.getDatabase(this)` after `PreferencesProvider.initialize(this)`
    Failure Indicators: Not found, or found before PreferencesProvider.initialize
    Evidence: .sisyphus/evidence/task-2-db-init.txt

  Scenario: MockDataSeeder is called on app start
    Tool: Bash (grep)
    Preconditions: Code changes applied
    Steps:
      1. grep for `MockDataSeeder.seedIfNeeded` in MainActivity.kt
      2. Verify it's called in a coroutine scope
    Expected Result: Found `MockDataSeeder.seedIfNeeded(applicationContext)` inside lifecycleScope.launch
    Failure Indicators: Not found, or called without coroutine scope
    Evidence: .sisyphus/evidence/task-2-seeder.txt
  ```

  **Commit**: YES
  - Message: `fix(data): initialize DatabaseProvider + enable MockDataSeeder`
  - Files: `app/src/main/java/com/sakuravillager/manga_translator/MainActivity.kt`
  - Pre-commit: `./gradlew compileDebugKotlin`

- [x] 3. Create AppLogger + LogEntry

  **What to do**:
  - Create `app/src/main/java/com/sakuravillager/manga_translator/data/logging/LogEntry.kt`:
    - `enum class LogLevel { DEBUG, INFO, WARN, ERROR }`
    - `data class LogEntry(val timestamp: Long, val level: LogLevel, val tag: String, val message: String)`
    - Add a `fun formatted(): String` method returning `"[{timestamp ISO}] {level}/{tag}: {message}"`
  - Create `app/src/main/java/com/sakuravillager/manga_translator/data/logging/AppLogger.kt`:
    - `object AppLogger` — singleton following same pattern as `DatabaseProvider`/`PreferencesProvider`
    - In-memory ring buffer using `ArrayDeque<LogEntry>` with max capacity 500
    - Thread-safe: use `@Synchronized` on all public methods or use `Collections.synchronizedList`
    - `fun d(tag: String, message: String)` — log DEBUG level
    - `fun i(tag: String, message: String)` — log INFO level
    - `fun w(tag: String, message: String)` — log WARN level
    - `fun e(tag: String, message: String, throwable: Throwable? = null)` — log ERROR level, include exception message
    - `fun getLogs(): List<LogEntry>` — return current buffer as immutable list
    - `fun clear()` — clear all entries
    - `fun exportAsText(): String` — return all logs as formatted text (each line = one `LogEntry.formatted()`)
    - Also call `android.util.Log.d/i/w/e` for Logcat output (dual output: ring buffer + Logcat)
  - Initialize AppLogger in `MainActivity.onCreate()` by calling `AppLogger.i("App", "Application started")` (no separate init method needed — it's an object)

  **Must NOT do**:
  - Do NOT add Timber or any external logging dependency
  - Do NOT persist logs to file (in-memory only)
  - Do NOT add log rotation or file management
  - Do NOT add logging calls to existing features yet (that's Task 7)

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: Two small new files, well-defined data structure
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with Tasks 1, 2)
  - **Blocks**: Tasks 4, 7
  - **Blocked By**: None (can start immediately)

  **References**:

  **Pattern References**:
  - `app/src/main/java/com/sakuravillager/manga_translator/data/local/DatabaseProvider.kt` - Singleton pattern with `object` declaration. Follow this pattern for AppLogger.
  - `app/src/main/java/com/sakuravillager/manga_translator/data/preferences/PreferencesProvider.kt` - Another singleton pattern example with `initialize(context)`.

  **API/Type References**:
  - `android.util.Log` - Built-in Android logging. Use `Log.d()`, `Log.i()`, `Log.w()`, `Log.e()` for Logcat output alongside ring buffer.
  - `java.util.ArrayDeque` - Efficient deque for ring buffer implementation.
  - `kotlin.jvm.Synchronized` - Annotation for thread-safe methods.

  **External References**:
  - Kotlin `object` singleton: https://kotlinlang.org/docs/object-declarations.html

  **WHY Each Reference Matters**:
  - DatabaseProvider/PreferencesProvider show the project's established singleton pattern — AppLogger should follow the same conventions
  - android.util.Log provides the dual-output mechanism (ring buffer for UI, Logcat for development)

  **Acceptance Criteria**:

  **QA Scenarios (MANDATORY):**

  ```
  Scenario: AppLogger singleton exists with ring buffer
    Tool: Bash (grep)
    Preconditions: Code changes applied
    Steps:
      1. grep for `object AppLogger` in the logging directory
      2. Verify it has d/i/w/e/clear/getLogs/exportAsText methods
    Expected Result: Found AppLogger object with all 7 public methods
    Failure Indicators: Missing methods or wrong structure
    Evidence: .sisyphus/evidence/task-3-applogger.txt

  Scenario: LogEntry data class has formatted method
    Tool: Bash (grep)
    Preconditions: Code changes applied
    Steps:
      1. grep for `fun formatted` in LogEntry.kt
      2. Verify LogEntry has timestamp, level, tag, message fields
    Expected Result: Found data class with all 4 fields + formatted() method
    Failure Indicators: Missing fields or method
    Evidence: .sisyphus/evidence/task-3-logentry.txt

  Scenario: Ring buffer has 500 capacity
    Tool: Bash (grep)
    Preconditions: Code changes applied
    Steps:
      1. grep for `500` in AppLogger.kt
      2. Verify it's used as max buffer capacity
    Expected Result: Found `500` as MAX_CAPACITY or similar constant
    Failure Indicators: Not found or different value
    Evidence: .sisyphus/evidence/task-3-capacity.txt
  ```

  **Commit**: YES
  - Message: `feat(logging): add AppLogger with ring buffer`
  - Files: `app/src/main/java/com/sakuravillager/manga_translator/data/logging/AppLogger.kt`, `app/src/main/java/com/sakuravillager/manga_translator/data/logging/LogEntry.kt`
  - Pre-commit: `./gradlew compileDebugKotlin`

- [x] 4. Create SettingsDebugViewModel

  **What to do**:
  - Create `app/src/main/java/com/sakuravillager/manga_translator/ui/viewmodel/SettingsDebugViewModel.kt`:
    - `class SettingsDebugViewModel : ViewModel()`
    - `private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())`
    - `val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()`
    - `init { startCollectingLogs() }` — start a coroutine that polls AppLogger.getLogs() periodically (every 500ms) or use a callback mechanism
    - Actually better: use a `Channel` or just call `refreshLogs()` when the screen enters composition. Simpler approach: expose a `refreshLogs()` function that updates `_logs` from `AppLogger.getLogs()`, and call it from the UI via `LaunchedEffect(Unit)`
    - `fun refreshLogs()` — updates `_logs.value = AppLogger.getLogs()`
    - `fun clearLogs()` — calls `AppLogger.clear()` then `refreshLogs()`
    - `fun copyLogsToClipboard(context: Context): Boolean` — copies `AppLogger.exportAsText()` to clipboard via `ClipboardManager`, returns success boolean
    - `fun shareLogs(context: Context): Intent` — creates a temp log file in `context.cacheDir`, writes `AppLogger.exportAsText()` to it, returns a `Intent.ACTION_SEND` with the file URI (via FileProvider)
    - For the share Intent: write log text to `File(context.cacheDir, "logs/manga-translator-logs.txt")`, get URI via `FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)`, create `Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }`, wrap with `Intent.createChooser()`
  - Follow the same ViewModel pattern as `SettingsAppearanceViewModel`

  **Must NOT do**:
  - Do NOT add Hilt/DI
  - Do NOT persist logs to database
  - Do NOT modify any screen files yet (that's Task 6)
  - Do NOT add a custom Application class

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: Single new file, straightforward ViewModel pattern
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES (with Task 5)
  - **Parallel Group**: Wave 2
  - **Blocks**: Task 6
  - **Blocked By**: Task 3 (needs AppLogger)

  **References**:

  **Pattern References**:
  - `app/src/main/java/com/sakuravillager/manga_translator/ui/viewmodel/SettingsAppearanceViewModel.kt` - Existing ViewModel pattern in the project. Follow this for class structure, StateFlow usage, and how it accesses the PreferencesRepository singleton.
  - `app/src/main/java/com/sakuravillager/manga_translator/ui/viewmodel/HistoryViewModel.kt` - Another ViewModel example with `MutableStateFlow` + `asStateFlow()` pattern.

  **API/Type References**:
  - `app/src/main/java/com/sakuravillager/manga_translator/data/logging/AppLogger.kt` (Task 3 output) - The `getLogs()`, `clear()`, `exportAsText()` methods to call from the ViewModel.
  - `android.content.Intent.ACTION_SEND` — Standard Android share intent action.
  - `androidx.core.content.FileProvider.getUriForFile()` — Get content URI for file sharing.
  - `android.content.ClipboardManager` — System clipboard for copy functionality.

  **External References**:
  - FileProvider sharing: https://developer.android.com/training/secure-file-sharing

  **WHY Each Reference Matters**:
  - SettingsAppearanceViewModel shows the EXACT pattern this project uses for ViewModels
  - AppLogger (from Task 3) provides the data layer the ViewModel needs to access
  - FileProvider/Intent docs show correct Android sharing API usage

  **Acceptance Criteria**:

  **QA Scenarios (MANDATORY):**

  ```
  Scenario: SettingsDebugViewModel exists with required methods
    Tool: Bash (grep)
    Preconditions: Task 3 completed, code changes applied
    Steps:
      1. grep for `class SettingsDebugViewModel` in viewmodel directory
      2. Verify it has refreshLogs, clearLogs, copyLogsToClipboard, shareLogs methods
    Expected Result: Found class with all 4 methods
    Failure Indicators: Missing class or methods
    Evidence: .sisyphus/evidence/task-4-viewmodel.txt

  Scenario: ViewModel uses StateFlow for logs
    Tool: Bash (grep)
    Preconditions: Code changes applied
    Steps:
      1. grep for `StateFlow` in SettingsDebugViewModel.kt
      2. Verify it exposes `logs: StateFlow<List<LogEntry>>`
    Expected Result: Found StateFlow<List<LogEntry>> property
    Failure Indicators: Using LiveData or other reactive type
    Evidence: .sisyphus/evidence/task-4-stateflow.txt
  ```

  **Commit**: YES (group with Task 5)
  - Message: `feat(logging): add SettingsDebugViewModel + FileProvider`
  - Files: `app/src/main/java/com/sakuravillager/manga_translator/ui/viewmodel/SettingsDebugViewModel.kt`
  - Pre-commit: `./gradlew compileDebugKotlin`

- [x] 5. Add FileProvider Config for Log Sharing

  **What to do**:
  - Create `app/src/main/res/xml/file_paths.xml`:
    ```xml
    <?xml version="1.0" encoding="utf-8"?>
    <paths>
        <cache-path name="logs" path="logs/" />
    </paths>
    ```
  - In `app/src/main/AndroidManifest.xml`, add inside `<application>` tag:
    ```xml
    <provider
        android:name="androidx.core.content.FileProvider"
        android:authorities="${applicationId}.fileprovider"
        android:exported="false"
        android:grantUriPermissions="true">
        <meta-data
            android:name="android.support.FILE_PROVIDER_PATHS"
            android:resource="@xml/file_paths" />
    </provider>
    ```
  - Place the provider element BEFORE the first `<activity>` element inside `<application>`

  **Must NOT do**:
  - Do NOT add `WRITE_EXTERNAL_STORAGE` permission
  - Do NOT change any existing manifest entries
  - Do NOT create a custom Application class

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: Two small config files
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES (with Task 4)
  - **Parallel Group**: Wave 2
  - **Blocks**: Task 6
  - **Blocked By**: None (can start immediately)

  **References**:

  **Pattern References**:
  - `app/src/main/AndroidManifest.xml` - Current manifest structure. Add the `<provider>` inside the existing `<application>` tag, before `<activity>`.

  **API/Type References**:
  - `androidx.core.content.FileProvider` — Available in project since `androidx.core:core-ktx` is a transitive dependency.
  - `${applicationId}.fileprovider` — Must match the authority used in `SettingsDebugViewModel.shareLogs()` (Task 4).

  **External References**:
  - FileProvider setup: https://developer.android.com/training/secure-file-sharing/setup-sharing

  **WHY Each Reference Matters**:
  - AndroidManifest.xml shows where to insert the provider element
  - The authority string must be consistent between manifest and ViewModel

  **Acceptance Criteria**:

  **QA Scenarios (MANDATORY):**

  ```
  Scenario: file_paths.xml exists with cache-path for logs
    Tool: Bash (grep)
    Preconditions: Code changes applied
    Steps:
      1. Check file exists at app/src/main/res/xml/file_paths.xml
      2. Verify it contains <cache-path name="logs" path="logs/" />
    Expected Result: File exists with correct cache-path element
    Failure Indicators: File missing or wrong path configuration
    Evidence: .sisyphus/evidence/task-5-filepaths.txt

  Scenario: FileProvider declared in AndroidManifest
    Tool: Bash (grep)
    Preconditions: Code changes applied
    Steps:
      1. grep for `FileProvider` in AndroidManifest.xml
      2. Verify authority is `${applicationId}.fileprovider`
      3. Verify `grantUriPermissions="true"`
    Expected Result: Found provider with correct authority and permissions
    Failure Indicators: Missing, wrong authority, or missing grant
    Evidence: .sisyphus/evidence/task-5-manifest.txt
  ```

  **Commit**: YES (group with Task 4)
  - Message: `feat(logging): add SettingsDebugViewModel + FileProvider`
  - Files: `app/src/main/res/xml/file_paths.xml`, `app/src/main/AndroidManifest.xml`
  - Pre-commit: `./gradlew compileDebugKotlin`

- [x] 6. Rewrite SettingsDebugScreen with Log Preview/Share/Clear

  **What to do**:
  - Rewrite `app/src/main/java/com/sakuravillager/manga_translator/ui/screens/SettingsDebugScreen.kt`:
    - Add `SettingsDebugViewModel` via `viewModel()`
    - Call `viewModel.refreshLogs()` in `LaunchedEffect(Unit)` to load initial logs
    - Layout structure:
      ```
      Scaffold(
        topBar = {
          TopAppBarWithBack(
            title = "Debug & Logs",
            onBack = onBack,
            actions = {
              // Copy button (top-right corner)
              IconButton(onClick = { viewModel.copyLogsToClipboard(context) }) {
                Icon(Icons.Default.ContentCopy, "Copy logs")
              }
            }
          )
        }
      ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
          // Action buttons row
          Row(horizontalArrangement = spacedBy(8.dp)) {
            OutlinedButton("Share") { /* launch share intent */ }
            OutlinedButton("Clear") { viewModel.clearLogs() }
          }
          
          // Log preview box
          Card(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            LazyColumn {
              items(logs) { entry ->
                LogEntryItem(entry) // timestamp + level badge + message
              }
            }
          }
        }
      }
      ```
    - For share: use `rememberLauncherForActivityResult` or `context.startActivity(viewModel.shareLogs(context))` with `Intent.createChooser()`
    - Create a small `@Composable fun LogEntryItem(entry: LogEntry)` that shows:
      - Timestamp (formatted as HH:mm:ss.SSS)
      - Level badge (colored: DEBUG=gray, INFO=blue, WARN=orange, ERROR=red)
      - Tag in monospace
      - Message text
    - Handle empty log state: show "No logs recorded" placeholder
    - Handle copy success: show Snackbar or Toast "Logs copied to clipboard"
    - Each log entry should have alternating background colors for readability

  **Must NOT do**:
  - Do NOT add the "Clear Cache" item back (it was a placeholder with fake data)
  - Do NOT modify the navigation to this screen
  - Do NOT change TopAppBarWithBack component
  - Do NOT add new dependencies

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
    - Reason: Full screen rewrite with multiple interactive features (preview, copy, share, clear)
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: NO (depends on Tasks 4, 5)
  - **Parallel Group**: Wave 3 (parallel with Task 7)
  - **Blocks**: None
  - **Blocked By**: Tasks 4, 5 (needs ViewModel + FileProvider)

  **References**:

  **Pattern References**:
  - `app/src/main/java/com/sakuravillager/manga_translator/ui/screens/SettingsAppearanceScreen.kt` - Shows the pattern for settings sub-screens: Scaffold + TopAppBarWithBack + ViewModel + content. Follow this layout structure.
  - `app/src/main/java/com/sakuravillager/manga_translator/ui/screens/HistoryScreen.kt` - Shows LazyColumn pattern with empty state handling. Follow for the log list.

  **API/Type References**:
  - `app/src/main/java/com/sakuravillager/manga_translator/ui/viewmodel/SettingsDebugViewModel.kt` (Task 4 output) - `logs: StateFlow<List<LogEntry>>`, `refreshLogs()`, `clearLogs()`, `copyLogsToClipboard(context)`, `shareLogs(context)`
  - `app/src/main/java/com/sakuravillager/manga_translator/data/logging/LogEntry.kt` (Task 3 output) - `LogEntry` data class with `timestamp`, `level`, `tag`, `message`, `formatted()`
  - `app/src/main/java/com/sakuravillager/manga_translator/ui/components/TopAppBarWithBack.kt` - Reusable top bar, has `actions` slot for copy button
  - `app/src/main/java/com/sakuravillager/manga_translator/ui/components/SettingsListItem.kt` - Only if needed for any remaining list items

  **External References**:
  - Android ShareCompat: https://developer.android.com/reference/androidx/core/app/ShareCompat

  **WHY Each Reference Matters**:
  - SettingsAppearanceScreen shows the exact layout pattern this project uses for sub-screens
  - HistoryScreen shows the LazyColumn + empty state pattern
  - SettingsDebugViewModel provides the data and actions the screen needs to bind to
  - TopAppBarWithBack has the `actions` slot for the copy button

  **Acceptance Criteria**:

  **QA Scenarios (MANDATORY):**

  ```
  Scenario: Debug screen shows log list from AppLogger
    Tool: Bash (grep)
    Preconditions: Tasks 3, 4, 5 completed, code changes applied
    Steps:
      1. grep for `SettingsDebugViewModel` in SettingsDebugScreen.kt
      2. grep for `LazyColumn` in SettingsDebugScreen.kt
      3. Verify logs are displayed via viewModel.logs
    Expected Result: Screen uses ViewModel + LazyColumn to display logs
    Failure Indicators: Missing ViewModel reference or LazyColumn
    Evidence: .sisyphus/evidence/task-6-log-list.txt

  Scenario: Copy button exists in top bar actions
    Tool: Bash (grep)
    Preconditions: Code changes applied
    Steps:
      1. grep for `ContentCopy` in SettingsDebugScreen.kt
      2. Verify it's inside TopAppBarWithBack's actions slot
    Expected Result: Found copy icon in top bar actions
    Failure Indicators: Missing copy button or in wrong location
    Evidence: .sisyphus/evidence/task-6-copy-btn.txt

  Scenario: Share and Clear buttons exist
    Tool: Bash (grep)
    Preconditions: Code changes applied
    Steps:
      1. grep for `clearLogs` in SettingsDebugScreen.kt
      2. grep for `shareLogs` or `ACTION_SEND` in SettingsDebugScreen.kt
    Expected Result: Found both share and clear functionality
    Failure Indicators: Missing either function
    Evidence: .sisyphus/evidence/task-6-share-clear.txt
  ```

  **Commit**: YES
  - Message: `feat(logging): debug screen with log preview/share/clear`
  - Files: `app/src/main/java/com/sakuravillager/manga_translator/ui/screens/SettingsDebugScreen.kt`
  - Pre-commit: `./gradlew compileDebugKotlin`

- [x] 7. Add Logging to Key Features

  **What to do**:
  - Add `AppLogger` calls to the following existing features (exact files, functions, and events):

  | File | Function/Event | Log Level | Tag | Message Template |
  |------|---------------|-----------|-----|-----------------|
  | `MainActivity.kt` | `onCreate()` | INFO | "App" | "Application started" |
  | `DatabaseProvider.kt` | `getDatabase()` | INFO | "Database" | "Database initialized" |
  | `HistoryViewModel.kt` | `init` → `loadHistoryList()` | INFO | "History" | "History list loaded: {count} items" |
  | `HistoryViewModel.kt` | `loadHistory(id)` | INFO | "History" | "Loading history detail: id={id}" |
  | `HistoryViewModel.kt` | `loadHistory(id)` catch | ERROR | "History" | "Failed to load history: {error}" |
  | `SelectPhotoScreen.kt` | Photo picker success | INFO | "SelectPhoto" | "Selected {count} images" |
  | `SelectPhotoScreen.kt` | Photo picker error | ERROR | "SelectPhoto" | "Photo selection failed: {error}" |
  | `WorkspaceViewModel.kt` | `setViewState()` | INFO | "Workspace" | "View state changed to {state}" |
  | `WorkspaceViewModel.kt` | `saveTranslation()` | INFO | "Workspace" | "Translation saved" |
  | `AnimatedNavHost.kt` | Each composable route | DEBUG | "Navigation" | "Navigated to {route}" |
  | `SettingsAppearanceViewModel.kt` | Theme change | INFO | "Settings" | "Theme changed to {theme}" |
  | `PreferencesProvider.kt` | `initialize()` | INFO | "Preferences" | "Preferences initialized" |

  - Import `com.sakuravillager.manga_translator.data.logging.AppLogger` in each file
  - For `AnimatedNavHost.kt`: Add `AppLogger.d("Navigation", "Navigated to {route}")` at the beginning of each composable block. Use the route constant from `AppRoutes`.
  - For error catches: Use `AppLogger.e(tag, message, throwable)` to include exception details
  - Do NOT add logging to placeholder/non-functional screens (SettingsTranslationScreen, SettingsAboutScreen)

  **Must NOT do**:
  - Do NOT add logging to ALL 43 files — only the ones listed above
  - Do NOT log inside tight loops or on every recomposition
  - Do NOT add logging to UI-only composables (no logging in CapsuleNavBar, PillToggle, etc.)
  - Do NOT change any business logic while adding logging
  - Do NOT log sensitive data (URIs are OK, no credentials)

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
    - Reason: Touches 8+ files across the project, needs careful insertion without breaking existing code
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES (with Task 6)
  - **Parallel Group**: Wave 3
  - **Blocks**: None
  - **Blocked By**: Task 3 (needs AppLogger)

  **References**:

  **Pattern References**:
  - `app/src/main/java/com/sakuravillager/manga_translator/data/logging/AppLogger.kt` (Task 3 output) - The `AppLogger.i(tag, message)`, `AppLogger.d(tag, message)`, `AppLogger.e(tag, message, throwable)` methods to call.

  **API/Type References**:
  - All files listed in the table above — these are the EXACT files to modify with specific insertion points.

  **WHY Each Reference Matters**:
  - Each file in the table has a specific event that needs logging — the table is the complete specification

  **Acceptance Criteria**:

  **QA Scenarios (MANDATORY):**

  ```
  Scenario: All specified files have AppLogger imports
    Tool: Bash (grep)
    Preconditions: Task 3 completed, code changes applied
    Steps:
      1. For each file in the table, grep for `import.*AppLogger`
      2. Count matches
    Expected Result: At least 8 files import AppLogger
    Failure Indicators: Missing imports in specified files
    Evidence: .sisyphus/evidence/task-7-imports.txt

  Scenario: Navigation logging exists in AnimatedNavHost
    Tool: Bash (grep)
    Preconditions: Code changes applied
    Steps:
      1. grep for `AppLogger.d.*Navigation` in AnimatedNavHost.kt
    Expected Result: Found at least 3 navigation log calls (home, history, settings)
    Failure Indicators: Missing navigation logging
    Evidence: .sisyphus/evidence/task-7-nav-logs.txt

  Scenario: Error logging exists in HistoryViewModel
    Tool: Bash (grep)
    Preconditions: Code changes applied
    Steps:
      1. grep for `AppLogger.e` in HistoryViewModel.kt
    Expected Result: Found at least 1 ERROR level log call
    Failure Indicators: Missing error logging
    Evidence: .sisyphus/evidence/task-7-error-logs.txt
  ```

  **Commit**: YES
  - Message: `feat(logging): instrument key features with AppLogger`
  - Files: `MainActivity.kt`, `DatabaseProvider.kt`, `HistoryViewModel.kt`, `SelectPhotoScreen.kt`, `WorkspaceViewModel.kt`, `AnimatedNavHost.kt`, `SettingsAppearanceViewModel.kt`, `PreferencesProvider.kt`
  - Pre-commit: `./gradlew compileDebugKotlin`

---

## Final Verification Wave

> 4 review agents run in PARALLEL. ALL must APPROVE. Present consolidated results to user and get explicit "okay" before completing.

- [x] F1. **Plan Compliance Audit** — `oracle`
  Read the plan end-to-end. For each "Must Have": verify implementation exists (read file, grep code). For each "Must NOT Have": search codebase for forbidden patterns — reject with file:line if found. Check evidence files exist in .sisyphus/evidence/. Compare deliverables against plan.
  Output: `Must Have [N/N] | Must NOT Have [N/N] | Tasks [N/N] | VERDICT: APPROVE/REJECT`

- [x] F2. **Code Quality Review** — `unspecified-high`
  Run `./gradlew compileDebugKotlin` (with JDK 21 from Android Studio: `JAVA_HOME='D:\Program Files\Android Studio\jbr'`). Review all changed files for: `as any`/`@ts-ignore` equivalents in Kotlin (`as`, `!!`), empty catches, println in prod, commented-out code, unused imports. Check AI slop: excessive comments, over-abstraction, generic names.
  Output: `Build [PASS/FAIL] | Files [N clean/N issues] | VERDICT`

- [x] F3. **Real Manual QA** — `unspecified-high` (+ `playwright` skill if UI)
  Start from clean state. Execute EVERY QA scenario from EVERY task — follow exact steps, capture evidence. Test cross-task integration. Test edge cases: empty log list, rapid navigation, history with mock data. Save to `.sisyphus/evidence/final-qa/`.
  Output: `Scenarios [N/N pass] | Integration [N/N] | Edge Cases [N tested] | VERDICT`

- [x] F4. **Scope Fidelity Check** — `deep`
  For each task: read "What to do", read actual diff. Verify 1:1 — everything in spec was built, nothing beyond spec. Check "Must NOT do" compliance. Detect cross-task contamination. Flag unaccounted changes.
  Output: `Tasks [N/N compliant] | Contamination [CLEAN/N issues] | Unaccounted [CLEAN/N files] | VERDICT`

---

## Commit Strategy

- **Commit 1**: `fix(ui): nav bar icon-only highlight` - CapsuleNavBar.kt
- **Commit 2**: `fix(data): initialize DatabaseProvider + enable MockDataSeeder` - MainActivity.kt
- **Commit 3**: `feat(logging): add AppLogger with ring buffer` - AppLogger.kt, LogEntry.kt
- **Commit 4**: `feat(logging): add SettingsDebugViewModel + FileProvider` - SettingsDebugViewModel.kt, file_paths.xml, AndroidManifest.xml
- **Commit 5**: `feat(logging): debug screen with log preview/share/clear` - SettingsDebugScreen.kt
- **Commit 6**: `feat(logging): instrument key features with AppLogger` - multiple files
- **Commit 7**: `chore: build verification` - verify assembleDebug

---

## Success Criteria

### Verification Commands
```bash
# 使用 Android Studio 自带的 JDK 21 构建
$env:JAVA_HOME = 'D:\Program Files\Android Studio\jbr'
./gradlew compileDebugKotlin  # Expected: BUILD SUCCESSFUL
./gradlew assembleDebug       # Expected: BUILD SUCCESSFUL, APK generated
```

### Final Checklist
- [x] All "Must Have" present
- [x] All "Must NOT Have" absent
- [x] Build passes with JDK 21
- [x] CapsuleNavBar only icon has highlight background
- [x] SettingsDebug shows log preview with copy/share/clear
- [x] History page navigates without crash
- [x] Key features have logging calls
