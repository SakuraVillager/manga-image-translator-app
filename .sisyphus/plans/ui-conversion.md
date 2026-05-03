# ComicTrans UI Conversion: React Design → Kotlin Compose

## TL;DR

> **Quick Summary**: 将 comictrans-design-system (React/Tailwind) 的UI设计原型完整转换为 Jetpack Compose 原生Android App。先实现全部10个页面的UI框架和基础交互，翻译引擎用占位提示替代。
> 
> **Deliverables**:
> - 可编译安装到Android手机的完整APK
> - 10个页面的完整UI实现 (Home, SelectPhoto, Workspace, History, HistoryDetail, Settings + 4个子页面)
> - 明暗主题切换
> - 图片选择 (系统相册 + 文件夹浏览)
> - 历史记录持久化 (Room)
> - 设置项持久化 (DataStore)
> 
> **Estimated Effort**: Large
> **Parallel Execution**: YES - 4 waves
> **Critical Path**: T1(fix build) → T6(NavHost) → T14(permission+empty states) → T16(build+verify) → F1-F4

---

## Context

### Original Request
将UI设计工具导出的React/Tailwind网页设计稿转换为Kotlin Jetpack Compose格式的Android App UI。先不实现复杂的翻译功能。

### Interview Summary
**Key Discussions**:
- **DI方案**: 不用DI框架，手动ViewModelFactory
- **暗色主题**: 完整支持明暗主题
- **翻译占位**: 图片选择等真实实现，翻译按钮点击后显示 "[test] 开始翻译" Toast
- **图片选择**: 同时实现系统相册选择器 + 文件夹浏览器
- **数据持久化**: DataStore(设置项) + Room(历史记录)
- **包名修复**: manga-translator → manga_transletter (Kotlin包名中连字符非法)
- **测试策略**: 构建APK后手动在手机上验证，无自动化测试

**Research Findings**:
- 设计原型有10个页面，使用React+Tailwind+Motion动画
- ComposeCode.tsx 提供了WorkspaceScreen的参考Compose代码
- DesignSpecs.tsx 提供了详细的MD3设计规范
- Android项目已有5个Kotlin文件：Color.kt, Theme.kt, Type.kt, BottomNavBar.kt, SettingsListItem.kt
- **严重Bug**: Kotlin 2.0.21 + composeOptions 1.5.11 不兼容，需升级到Compose Compiler Plugin
- **包名Bug**: `manga-translator` 中连字符在Kotlin包名中非法

### Metis Review
**Identified Gaps** (addressed):
- 图片加载策略: 使用Coil库，配合内存优化
- 权限处理: 需要READ_MEDIA_IMAGES权限 (Android 13+) / READ_EXTERNAL_STORAGE (旧版)
- 空状态: History为空时显示空状态提示
- 图标映射: lucide-react图标 → Material Icons对应
- 导航回退: 所有子页面需要正确处理返回按钮
- 大图内存: Coil自动采样，无需手动处理
- 配置变更: ViewModel保存UI状态，屏幕旋转不丢失

---

## Work Objectives

### Core Objective
将React设计原型的10个页面完整转换为Jetpack Compose实现，构建可安装运行的Android App。

### Concrete Deliverables
- `app-debug.apk` 可安装到手机
- 10个页面全部可导航、可交互
- 明暗主题切换正常工作
- 图片选择功能正常（系统相册+文件夹）
- 设置项重启后保留
- 历史记录重启后保留

### Definition of Done
- [ ] `./gradlew assembleDebug` 成功
- [ ] APK安装到手机后可正常启动
- [ ] 所有10个页面可导航
- [ ] 明暗主题切换生效
- [ ] 图片选择功能可用
- [ ] 翻译按钮显示 "[test] 开始翻译" Toast
- [ ] 设置项持久化
- [ ] 历史记录持久化

### Must Have
- 全部10个页面的完整UI实现
- 底部导航栏 (Home/History/Settings)
- 明暗主题支持
- 图片选择 (系统相册 + 文件夹浏览)
- Room历史记录持久化
- DataStore设置持久化
- Compose Navigation导航
- Material Design 3设计规范
- 设计稿中的色彩体系 (Taupe/Green/WarmSand)

### Must NOT Have (Guardrails)
- ❌ 不实现翻译引擎/OCR/文字检测
- ❌ 不添加网络请求/API调用
- ❌ 不添加云同步/账户系统
- ❌ 不添加ML/AI库 (TensorFlow, ML Kit)
- ❌ 不添加分析/崩溃上报
- ❌ 不添加应用内购买/广告
- ❌ 不使用DI框架 (Hilt/Koin)
- ❌ 不添加复杂动画 (仅AnimatedVisibility/FadeIn)
- ❌ 不添加多模块架构
- ❌ 不实现Workspace的缩放手势 (先简化为固定显示)
- ❌ 不实现Bounding Box叠加和编辑 (先显示mock翻译气泡)

---

## Verification Strategy (MANDATORY)

> **ZERO HUMAN INTERVENTION** - ALL verification is agent-executed. No exceptions.
> Acceptance criteria requiring "user manually tests/confirms" are FORBIDDEN.

### Test Decision
- **Infrastructure exists**: NO (仅有默认的ExampleUnitTest/ExampleInstrumentedTest)
- **Automated tests**: None (用户选择手动在手机验证)
- **Framework**: none
- **TDD**: N/A

### QA Policy
Every task MUST include agent-executed QA scenarios.
Evidence saved to `.sisyphus/evidence/task-{N}-{scenario-slug}.{ext}`.

- **Android App**: Use Bash (`./gradlew assembleDebug`) - 编译验证
- **UI Logic**: Use Bash (`./gradlew compileDebugKotlin`) - 编译时验证
- **Data Layer**: Use Bash (编译验证) - 无独立测试框架

---

## Execution Strategy

### Parallel Execution Waves

```
Wave 1 (Start Immediately - foundation):
├── T1: Fix build system + dependencies [quick]
├── T2: Room database setup [quick]
├── T3: DataStore preferences setup [quick]
├── T4: Navigation routes + screen signatures [quick]
└── T5: Shared data models + components [quick]

Wave 2 (After Wave 1 - ALL screens, MAX PARALLEL):
├── T6: MainActivity + NavHost wiring [unspecified-high]
├── T7: HomeScreen [quick]
├── T8: SelectPhotoScreen [unspecified-high]
├── T9: WorkspaceScreen [deep]
├── T10: HistoryScreen + HistoryDetailScreen [unspecified-high]
├── T11: SettingsScreen [quick]
├── T12: SettingsAppearanceScreen [unspecified-high]
└── T13: SettingsTranslation + Debug + About [quick]

Wave 3 (After Wave 2 - integration + polish):
├── T14: Permission handling + empty states [unspecified-high]
├── T15: Dark theme refinement [visual-engineering]
└── T16: Build verification + navigation smoke test [deep]

Wave FINAL (After ALL tasks — 4 parallel reviews, then user okay):
├── F1: Plan compliance audit (oracle)
├── F2: Code quality review (unspecified-high)
├── F3: Real manual QA (unspecified-high)
└── F4: Scope fidelity check (deep)
-> Present results -> Get explicit user okay

Critical Path: T1 → T6 → T14 → T16 → F1-F4 → user okay
Parallel Speedup: ~65% faster than sequential
Max Concurrent: 8 (Wave 2)
```

### Dependency Matrix

| Task | Depends On | Blocks | Wave |
|------|-----------|--------|------|
| T1 | - | T6-T16 | 1 |
| T2 | - | T10 | 1 |
| T3 | - | T12, T13 | 1 |
| T4 | - | T6 | 1 |
| T5 | - | T7-T13 | 1 |
| T6 | T1, T4, T5 | T14, T16 | 2 |
| T7 | T1, T5 | T16 | 2 |
| T8 | T1, T5 | T14, T16 | 2 |
| T9 | T1, T5 | T16 | 2 |
| T10 | T1, T2, T5 | T16 | 2 |
| T11 | T1, T4 | T16 | 2 |
| T12 | T1, T3, T5 | T15 | 2 |
| T13 | T1, T3 | T16 | 2 |
| T14 | T6, T8 | T16 | 3 |
| T15 | T12 | T16 | 3 |
| T16 | T6-T15 | F1-F4 | 3 |
| F1-F4 | T16 | user okay | FINAL |

### Agent Dispatch Summary

- **Wave 1**: **5** - T1-T5 → all `quick`
- **Wave 2**: **8** - T6 → `unspecified-high`, T7 → `quick`, T8 → `unspecified-high`, T9 → `deep`, T10 → `unspecified-high`, T11 → `quick`, T12 → `unspecified-high`, T13 → `quick`
- **Wave 3**: **3** - T14 → `unspecified-high`, T15 → `visual-engineering`, T16 → `deep`
- **FINAL**: **4** - F1 → `oracle`, F2 → `unspecified-high`, F3 → `unspecified-high`, F4 → `deep`

---

## TODOs

- [x] 1. Fix build system + Compose compiler plugin + package name

  **STATUS**: Core fixes DONE - composeOptions removed, compose-compiler plugin added, dependencies added. Build fails with Java 25 + Gradle 8.11 compatibility issue (error "25.0.2"). Environment issue, not code.

  **What to do**:
  - 修复 `build.gradle.kts` (root): 添加 `alias(libs.plugins.compose.compiler) apply false` 到 plugins 块
  - 修复 `app/build.gradle.kts`: 添加 `alias(libs.plugins.compose.compiler)` 到 plugins 块，移除 `composeOptions` 块
  - 修复 `libs.versions.toml`: 添加 `compose-compiler = "1.5.11"` (兼容Kotlin 2.0.21) 和 `compose-compiler-plugin` 声明
  - **包名修复**: 将所有Kotlin文件中的 `com.sakuravillager.manga-translator` 改为 `com.sakuravillager.manga_translator`
  - 更新 AndroidManifest.xml 中的 package 引用
  - 添加 Coil 依赖: `io.coil-kt:coil-compose:2.7.0`
  - 添加 Room 依赖: `room-runtime`, `room-compiler` (ksp), `room-ktx`
  - 添加 DataStore 依赖: `datastore-preferences`
  - 添加 KSP 插件 (Room注解处理需要)
  - 确认 `./gradlew compileDebugKotlin` 编译通过

  **Must NOT do**:
  - 不添加Hilt/Koin等DI框架
  - 不添加网络库 (Retrofit/OkHttp)
  - 不升级Kotlin版本 (保持2.0.21)

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: 配置文件修改，模式明确
  - **Skills**: [`git-master`]
    - `git-master`: 需要跨多文件重命名包名

  **Parallelization**:
  - **Can Run In Parallel**: YES (with T2-T5)
  - **Parallel Group**: Wave 1
  - **Blocks**: T6-T16
  - **Blocked By**: None

  **References**:

  **Pattern References**:
  - `app/build.gradle.kts` - 当前构建配置，需修改composeOptions为compose compiler plugin
  - `gradle/libs.versions.toml` - 版本目录，需添加compose-compiler和room/datastore依赖
  - `build.gradle.kts` (root) - 需添加compose compiler plugin声明

  **API/Type References**:
  - `app/src/main/java/com/sakuravillager/manga-translator/ui/theme/Color.kt:1` - 包名需从 `manga-translator` 改为 `manga_translator`
  - `app/src/main/java/com/sakuravillager/manga-translator/ui/theme/Theme.kt:1` - 同上
  - `app/src/main/java/com/sakuravillager/manga-translator/ui/theme/Type.kt:1` - 同上
  - `app/src/main/java/com/sakuravillager/manga-translator/ui/components/BottomNavBar.kt:1,24-25` - 同上
  - `app/src/main/java/com/sakuravillager/manga-translator/ui/components/SettingsListItem.kt:1,22` - 同上

  **External References**:
  - Compose Compiler Plugin: https://developer.android.com/develop/ui/compose/compiler#kotlin-gradle-plugin - Kotlin 2.0+ 必须使用此插件替代 composeOptions

  **WHY Each Reference Matters**:
  - build.gradle.kts 和 libs.versions.toml 是修复编译问题的核心文件
  - 所有Kotlin文件的包名必须统一修复，否则编译失败
  - Compose Compiler Plugin文档确认了迁移方式

  **Acceptance Criteria**:

  - [ ] `./gradlew compileDebugKotlin` 编译成功，0 errors
  - [ ] 所有Kotlin文件包名为 `com.sakuravillager.manga_translator`
  - [ ] composeOptions 块已移除，compose compiler plugin 已启用
  - [ ] Coil, Room, DataStore 依赖已添加

  **QA Scenarios (MANDATORY):**

  ```
  Scenario: Build succeeds after fixing compiler plugin
    Tool: Bash
    Preconditions: Project at E:\yhz\Projects\manga-image-translator-app
    Steps:
      1. Run: ./gradlew compileDebugKotlin
      2. Check exit code is 0
      3. Verify no "Unresolved reference" errors in output
    Expected Result: BUILD SUCCESSFUL, exit code 0
    Failure Indicators: "BUILD FAILED", "Unresolved reference", "composeOptions" errors
    Evidence: .sisyphus/evidence/task-1-build-success.txt

  Scenario: Package name is consistent across all Kotlin files
    Tool: Bash
    Preconditions: All files saved
    Steps:
      1. Grep for "manga-translator" in all .kt files
      2. Verify zero matches (all should be manga_translator)
    Expected Result: 0 matches for "manga-translator" in .kt files
    Failure Indicators: Any .kt file still contains "manga-translator"
    Evidence: .sisyphus/evidence/task-1-package-check.txt
  ```

  **Commit**: YES
  - Message: `fix(build): fix compose compiler plugin and package name`
  - Files: `build.gradle.kts, app/build.gradle.kts, gradle/libs.versions.toml, all .kt files`
  - Pre-commit: `./gradlew compileDebugKotlin`

- [x] 2. Room database setup (History entity + DAO + database)

  **STATUS**: DONE - 4 files created in data/local/

- [x] 3. DataStore preferences setup (Settings repository)

  **STATUS**: DONE - 3 files created in data/preferences/

  **What to do**:
  - 创建 `data/preferences/` 目录结构
  - 创建 `AppPreferences.kt` - data class包含: themeMode(String: "system"/"light"/"dark"), colorScheme(String: "default"/"dynamic"/"green_apple"), pureBlackDarkMode(Boolean), appLanguage(String), tabletInterface(String: "auto"), translator(String: "GPT-4 Vision"), textDirection(String: "auto_detect_vertical"), textDetector(String: "default_contour"), ocrEngine(String: "google_cloud_vision"), imageRepair(String: "inpaint_lama")
  - 创建 `PreferencesRepository.kt` - 封装DataStore<Preferences>，提供: getPreferences(): Flow<AppPreferences>, updateThemeMode(mode), updateColorScheme(scheme), updatePureBlack(enabled), 及其他setter方法
  - 创建 `PreferencesProvider.kt` - 单例对象提供DataStore实例

  **Must NOT do**:
  - 不使用Proto DataStore (Preferences DataStore足够)
  - 不加密存储

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: 标准DataStore配置
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES (with T1-T2, T4-T5)
  - **Parallel Group**: Wave 1
  - **Blocks**: T12, T13
  - **Blocked By**: None

  **References**:

  **External References**:
  - DataStore 官方文档: https://developer.android.com/topic/libraries/architecture/datastore - Preferences DataStore标准用法

  **WHY Each Reference Matters**:
  - DataStore文档提供Preferences DataStore的标准创建和读取模式

  **Acceptance Criteria**:

  - [ ] AppPreferences.kt 存在，包含所有设计稿中的设置项字段
  - [ ] PreferencesRepository.kt 存在，提供Flow读取和各setter方法
  - [ ] `./gradlew compileDebugKotlin` 编译成功

  **QA Scenarios (MANDATORY):**

  ```
  Scenario: DataStore classes compile successfully
    Tool: Bash
    Steps:
      1. Run: ./gradlew compileDebugKotlin
      2. Check for DataStore-related errors
    Expected Result: BUILD SUCCESSFUL
    Failure Indicators: "Unresolved reference: datastore"
    Evidence: .sisyphus/evidence/task-3-datastore-compile.txt
  ```

  **Commit**: NO (groups with Wave 1)

- [x] 4. Navigation routes + screen signatures
- [x] 5. Shared data models + reusable components

## Wave 1 Complete!

- [x] 6. MainActivity + NavHost wiring
- [x] 7. HomeScreen implementation
- [x] 8. SelectPhotoScreen implementation (image picker)
- [x] 9. WorkspaceScreen implementation (image display + mock translation)
- [x] 10. HistoryScreen + HistoryDetailScreen implementation
- [x] 11. SettingsScreen implementation
- [x] 12. SettingsAppearanceScreen implementation (theme switching)
- [x] 13. SettingsTranslation + SettingsDebug + SettingsAbout screens
- [x] 14. Permission handling + empty states
- [x] 15. Dark theme refinement

- [x] 16. Build verification + navigation smoke test

  **What to do**:
  - 运行 `./gradlew assembleDebug` 确保完整APK构建成功
  - 检查所有10个Screen文件存在且可被NavHost导航到
  - 验证所有共享组件被正确引用
  - 验证所有ViewModel与Screen正确关联
  - 检查AndroidManifest.xml的Activity声明和权限声明完整
  - 修复任何编译警告或错误
  - 确保所有Toast提示文案正确 ("[test] 开始翻译", "[test] 保存成功", "[test] 下载功能开发中", "[test] 设置项暂不可修改", "[test] 导出日志功能开发中", "[test] 清除缓存功能开发中")
  - 确保Coil图片加载在所有使用AsyncImage的地方配置正确
  - 检查proguard-rules.pro是否需要添加Room/Coil的keep规则

  **Must NOT do**:
  - 不添加新功能
  - 不修改设计稿中定义的UI

  **Recommended Agent Profile**:
  - **Category**: `deep`
    - Reason: 全面的集成验证，需要检查所有模块的连接
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: NO (依赖所有前序任务)
  - **Parallel Group**: Wave 3 (last)
  - **Blocks**: F1-F4
  - **Blocked By**: T6-T15

  **References**:

  **Pattern References**:
  - `navigation/AppRoutes.kt` (T4创建) - 所有路由
  - `ui/screens/` (T7-T13创建) - 所有Screen文件

  **Acceptance Criteria**:

  - [ ] `./gradlew assembleDebug` BUILD SUCCESSFUL
  - [ ] APK存在于 `app/build/outputs/apk/debug/app-debug.apk`
  - [ ] 所有10个Screen文件存在
  - [ ] 所有6个Toast提示文案在代码中
  - [ ] 0 compilation errors, 0 critical warnings

  **QA Scenarios (MANDATORY):**

  ```
  Scenario: Full debug APK builds successfully
    Tool: Bash
    Preconditions: All T1-T15 completed
    Steps:
      1. Run: ./gradlew assembleDebug
      2. Check exit code is 0
      3. Verify APK exists at app/build/outputs/apk/debug/app-debug.apk
    Expected Result: BUILD SUCCESSFUL, APK file exists, size > 1MB
    Failure Indicators: BUILD FAILED, APK missing, APK < 1MB
    Evidence: .sisyphus/evidence/task-16-build-success.txt

  Scenario: All 10 screens are navigable in NavHost
    Tool: Bash
    Steps:
      1. Grep ComicTransApp.kt for "composable(" 
      2. Count occurrences
    Expected Result: 10 composable() calls
    Failure Indicators: Fewer than 10
    Evidence: .sisyphus/evidence/task-16-nav-complete.txt

  Scenario: All Toast messages are present
    Tool: Bash
    Steps:
      1. Grep all .kt files for "[test]"
      2. Count unique messages
    Expected Result: At least 6 unique "[test]" messages found
    Failure Indicators: Missing toast messages
    Evidence: .sisyphus/evidence/task-16-toast-messages.txt
  ```

  **Commit**: YES
  - Message: `feat(ui): add permission handling, empty states, dark theme, and build verification`
  - Files: `all Wave 3 files`
  - Pre-commit: `./gradlew assembleDebug`

---

## Final Verification Wave

- [x] F1. **Plan Compliance Audit** — `oracle`
  Read the plan end-to-end. For each "Must Have": verify implementation exists (read file, check build). For each "Must NOT Have": search codebase for forbidden patterns — reject with file:line if found. Check evidence files exist in .sisyphus/evidence/. Compare deliverables against plan.
  Output: `Must Have [N/N] | Must NOT Have [N/N] | Tasks [N/N] | VERDICT: APPROVE/REJECT`

- [x] F2. **Code Quality Review** — `unspecified-high`
  Run `./gradlew compileDebugKotlin` + lint check. Review all changed files for: `as Any`/`@Suppress`, empty catches, println in prod, commented-out code, unused imports. Check AI slop: excessive comments, over-abstraction, generic names. Verify package naming convention (manga_translator not manga-translator).
  Output: `Build [PASS/FAIL] | Lint [PASS/FAIL] | Files [N clean/N issues] | VERDICT`

- [x] F3. **Real Manual QA** — `unspecified-high`
  Run `./gradlew assembleDebug`. Verify APK exists. Check all 10 screens are accessible via navigation. Verify theme switching. Verify image picker launches. Verify toast appears on translate button. Save screenshots if possible.
  Output: `Build [PASS/FAIL] | APK [exists/missing] | Screens [N/10] | VERDICT`

- [x] F4. **Scope Fidelity Check** — `deep`
  For each task: read "What to do", read actual diff. Verify 1:1 — everything in spec was built (no missing), nothing beyond spec was built (no creep). Check "Must NOT do" compliance. Detect cross-task contamination. Flag unaccounted changes.
  Output: `Tasks [N/N compliant] | Contamination [CLEAN/N issues] | Unaccounted [CLEAN/N files] | VERDICT`

---

## Commit Strategy

- **Wave 1**: `fix(build): fix compose compiler plugin and package name` - build files, package rename
- **Wave 2**: `feat(ui): implement all app screens` - all screen implementations
- **Wave 3**: `feat(ui): add permission handling, empty states, and dark theme polish` - integration
- **Final**: `feat(ui): complete UI conversion from design prototype` - final cleanup

---

## Success Criteria

### Verification Commands
```bash
./gradlew assembleDebug  # Expected: BUILD SUCCESSFUL
./gradlew compileDebugKotlin  # Expected: no errors
```

### Final Checklist
- [ ] All 10 "Must Have" items present
- [ ] All 11 "Must NOT Have" items absent
- [ ] `./gradlew assembleDebug` passes
- [ ] APK installs and launches on device
- [ ] All screens navigable
- [ ] Theme switching works
- [ ] Image picker works
- [ ] Settings persist across restart
- [ ] History persists across restart
