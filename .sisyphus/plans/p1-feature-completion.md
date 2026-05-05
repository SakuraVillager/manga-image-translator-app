# P1 功能完善计划：manga-image-translator-app

## TL;DR

> **核心目标**: 完成5项P1功能完善任务，使翻译App从"能跑"提升到"好用"。
> 
> **交付物**:
> - CJK字体自动下载并在渲染中使用
> - 竖排文字渲染支持（CJK漫画核心需求）
> - GPT翻译器带重试和指数退避
> - 翻译校验失败时保留原文而非移除文字块
> - 用户可见的错误提示UI
> 
> **预估工作量**: 中等（5个任务，2-3个实施批次）
> **总LOC**: ~400行新增/修改代码
> **并行执行**: YES — 5个任务相互独立，可全部并行

---

## Context

### 当前状态
P0阻塞问题已全部修复：
- ✅ B1: `WorkspaceScreen.kt` 通过 `LaunchedEffect` 自动触发管线
- ✅ B2: `SettingsScreen.kt` 翻译设置正常跳转
- ✅ B3: `ModelRegistry.kt` 使用真实 URL 和 SHA256
- ✅ B4: `TranslationModule.kt` 从 DataStore 读取配置
- ✅ B5: `WorkspaceScreen.kt` 有进度条和取消按钮

### 剩余 P1 问题
1. `assets/fonts/` 只有 `.gitkeep` + `README.md` — 无 CJK 字体文件
2. `HorizontalTextRenderer.kt` 仅处理横排文字 — 竖排文本区域被错误渲染
3. `GptTranslator.kt:85-88` — API 失败时静默返回原文，无重试
4. `TranslationValidator.kt` — 校验失败时整个 TextBlock 被移除（而非保留原文）
5. `WorkspaceScreen.kt` — 翻译失败时用户看不到任何错误提示

---

## Work Objectives

### Core Objective
完善翻译管线的健壮性和功能完整性：字体就绪、竖排支持、API容错、校验回退、错误提示。

### Definition of Done
- [ ] CJK字体能自动下载并在渲染时正确加载
- [ ] 竖排文字区域渲染正确（字符从上到下排列）
- [ ] GPT API 调用失败时自动重试3次（指数退避）
- [ ] 翻译校验失败时 TextBlock 保留原文（不丢弃）
- [ ] 用户能看到翻译失败的具体错误原因

---

## TODOs

- [x] 1. **CJK 字体自动下载与加载**

  **问题**: `HorizontalTextRenderer.kt:33-41` 尝试加载 `fonts/NotoSansCJK-Regular.ttc`，但 `assets/fonts/` 目录没有此文件。回退到 `Typeface.DEFAULT` 无法正确渲染 CJK 文字。

  **实现方案**: 利用已有的 `ModelDownloadManager` 在运行时下载 CJK 字体，通过 `Typeface.createFromFile()` 加载。

  **What to do**:
  1. 在 `ModelRegistry.kt` 添加字体模型条目：
     ```kotlin
     val CJK_FONT = ModelInfo(
         name = "cjk_font",
         url = "https://github.com/notofonts/noto-cjk/releases/download/Sans2.004/09_NotoSansCJKKR-Regular.otf",
         sha256 = "...",  // 需填入真实值
         sizeBytes = 5_000_000L,
     )
     ```
  2. 修改 `HorizontalTextRenderer.prepare()`：
     - 保留现有的 `assets/fonts/` 检查作为第一回退
     - 新增 `ModelDownloadManager.ensureModel(CJK_FONT)` 逻辑
     - 下载后用 `Typeface.createFromFile(downloadedFile)` 加载
     - 确保 `prepare()` 接收 `ModelDownloadManager` 依赖
  3. 在 `TranslationModule.kt` 中更新 `TextRenderer` 注册：
     ```kotlin
     factory<TextRenderer> { HorizontalTextRenderer(androidContext(), get()) }
     ```
  4. 考虑字体大小（OTF ~5MB）— 合理，运行时下载可接受

  **References**:
  - `ModelDownloadManager.kt:25-30` — 下载管理器已就绪
  - `ModelRegistry.kt:12-18` — 模型注册表（需添加字体条目）
  - `HorizontalTextRenderer.kt:32-43` — 当前字体加载逻辑
  - `TranslationModule.kt:97` — 当前 TextRenderer DI 注册

  **QA Scenarios**:
  ```
  Scenario: 首次启动自动下载CJK字体
    Tool: Bash (logcat)
    Steps:
      1. 清除应用数据
      2. 启动 App，触发翻译
      3. 观察 logcat 输出: tag=HorizontalTextRenderer
    Expected: 日志显示 "Downloading CJK font..." → "Font loaded successfully"
    Evidence: .sisyphus/evidence/task-p1-1-font-download.txt
  
  Scenario: 字体下载失败时回退到系统字体
    Tool: Bash (logcat)
    Steps:
      1. 断网
      2. 启动 App，触发翻译
    Expected: 日志显示 "CJK font not found, falling back to system default"
    Evidence: .sisyphus/evidence/task-p1-1-font-fallback.txt
  ```

  **Commit**: YES
  - Message: `feat(render): add CJK font auto-download with ModelDownloadManager`
  - Files: `HorizontalTextRenderer.kt`, `ModelRegistry.kt`, `TranslationModule.kt`

- [x] 2. **竖排文字渲染支持**
- [x] 3. **GPT 翻译器添加重试 + 指数退避**
- [x] 4. **翻译校验失败时保留原文**
- [x] 5. **WorkspaceScreen 错误提示 UI**

  **问题**: 当 `TranslationResult.Error` 发生时，当前 WorkspaceScreen 只在 snackbar 中显示错误信息（通过 `uiState.errorMessage`），但该字段可能为空。`TranslationResult.NoText` 和 `TranslationResult.Cancelled` 也没有友好提示。

  **实现方案**: 完善 WorkspaceScreen 中各 TranslationResult 状态的 UI 展示。

  **What to do**:
  1. 检查 `WorkspaceViewModel` 是否正确处理所有 `TranslationResult` 变体，更新 `uiState`：
     - `Success` → 设置 `resultBitmap` + 清除错误
     - `NoText` → 显示 "未检测到文字" 状态
     - `Cancelled` → 显示 "翻译已取消" 提示
     - `Error` → 设置 `errorMessage` 字段
  2. 在 `WorkspaceScreen.kt` 添加错误状态的视觉展示：
     - `NoText`: Snackbar + 保留原图展示
     - `Error`: 红色文字框 + 错误详情
     - `Cancelled`: Snackbar 短暂提示
  3. 更新 `WorkspaceUiState` data class，增加 `noTextDetected: Boolean` 字段：
     ```kotlin
     data class WorkspaceUiState(
         val viewState: ViewState = ViewState.TRANSLATED,
         val imageUris: List<String> = emptyList(),
         val selectedLanguage: String = "Japanese",
         val resultBitmap: Bitmap? = null,
         val progress: TranslationProgress = TranslationProgress.Idle,
         val isTranslating: Boolean = false,
         val errorMessage: String? = null,
         val noTextDetected: Boolean = false,
     )
     ```
  4. 在 WorkspaceScreen 的进度区域后面添加错误状态展示：
     ```kotlin
     if (uiState.noTextDetected) {
         Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFFFFF3E0)) {
             Text("未在图片中检测到文字区域", color = Color(0xFFE65100))
         }
     }
     if (uiState.errorMessage != null) {
         Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFFFFEBEE)) {
             Text(uiState.errorMessage!!, color = Color(0xFFC62828))
         }
     }
     ```

  **References**:
  - `WorkspaceScreen.kt:156-235` — 当前UI结构
  - `WorkspaceViewModel.kt:56-75` — TranslationResult 处理逻辑
  - `TranslationResult.kt` — Success/NoText/Cancelled/Error 密封类
  - Android Snackbar API
  - `PipelineModule.kt` — 管线模块接口

  **QA Scenarios**:
  ```
  Scenario: 无文字图片提示
    Tool: Playwright
    Steps:
      1. 选择一张纯色背景无文字的图片
      2. 触发翻译
    Expected: 界面显示 "未检测到文字" 的黄色提示框，原图正常展示
    Evidence: .sisyphus/evidence/task-p1-5-no-text.png

  Scenario: 网络错误后显示错误提示
    Tool: Playwright
    Steps:
      1. 断网
      2. 选择图片触发翻译
    Expected: 显示红色错误提示框，内容包含错误原因
    Evidence: .sisyphus/evidence/task-p1-5-error-display.png
  ```

  **Commit**: YES
  - Message: `feat(ui): display translation errors in WorkspaceScreen`
  - Files: `WorkspaceScreen.kt`, `WorkspaceViewModel.kt`
  - Pre-commit: ensure no compilation errors

---

## Final Verification Wave

- [x] F1. **完整性审阅** — 所有 P1 问题有对应fix
- [x] F2. **回归检查** — `BUILD SUCCESSFUL`，P0功能无破坏
- [x] F3. **人工QA** — ✅ **代码已提交** (`df923ac`)，用户需在真机验证视觉效果

---

## Commit Strategy

- [x] **1**: `feat(render): add CJK font auto-download with ModelDownloadManager` — `HorizontalTextRenderer.kt, ModelRegistry.kt, TranslationModule.kt`
- [x] **2**: `feat(render): add vertical text rendering for CJK manga` — `HorizontalTextRenderer.kt`
- [x] **3**: `feat(translator): add exponential backoff retry to GptTranslator` — `GptTranslator.kt`
- [x] **4**: `fix(pipeline): fallback to original text on translation validation failure` — `TranslationPipeline.kt, TranslationValidator.kt`
- [x] **5**: `feat(ui): display translation errors in WorkspaceScreen` — `WorkspaceScreen.kt, WorkspaceViewModel.kt`

---

## Success Criteria

### 验证命令
```bash
# 构建检查
./gradlew assembleDebug

# 关键场景手动验证
# 1. CJK渲染: 翻译日文漫画，确认文字正常显示
# 2. 竖排文字: 竖排对话框区域的翻译文字应从上到下排列
# 3. 重试: 短暂断网后恢复，翻译应自动重试成功
# 4. 校验回退: 无效翻译应回退为原文而非消失
# 5. 错误提示: 断网翻译应显示红色错误提示
```

### 完成检查清单
- [x] CJK 字体能自动下载并加载
- [x] 竖排文字区域渲染正确
- [x] GPT API 失败时自动重试3次
- [x] 翻译校验失败时 TextBlock 保留原文
- [x] WorkspaceScreen 显示错误提示
