# P0: 阻塞性问题修复计划

## TL;DR

> **核心目标**: 修复5个P0阻塞性问题，使App从"架构完整但跑不通"变为"可跑通完整翻译流程"
> 
> **任务清单**: 5个独立修复任务（B1~B5），共涉及8个文件修改
> 
> **执行策略**: B3已完成 ✅ → B2+B4并行 → B1 → B5
> 
> **任务类型**: 每个任务都是**中型任务**（1-3个文件，清晰的范围）

---

## Context

### 原始需求
修复现有Kotlin/Android漫画翻译App的5个P0阻塞问题，使完整翻译管线可以从UI触发并完成一次完整的翻译流程。

### 发现问题
**B1 — 管线从未触发**: `WorkspaceScreen.kt` 从未调用 `viewModel.startTranslation(bitmap)`。选了图片后只展示图片，不开始翻译。
**B2 — 设置页无法访问**: `SettingsScreen.kt:57` 点击翻译设置弹Toast `"[test] 设置项暂不可修改"`，不会跳转到`SettingsTranslationScreen`。
**B3 — 模型URL全为占位符**: `ModelRegistry.kt` 三处URL都是 `https://github.com/placeholder/...`，SHA256全零。
**B4 — 配置不生效**: `TranslationModule.kt:98` 用 `TranslationConfig()` 硬编码默认值，`TranslationConfigMapper` 从未在生产代码中被调用。
**B5 — 无进度/取消/错误展示**: `TranslationProgress` 流到ViewModel但没展示；`cancelTranslation()` 存在但U I无触发；`TranslationResult.Error` 被静默吞掉。

### Scope Boundaries
- INCLUDE: WorkspaceScreen/ViewModel, SettingsScreen, ModelRegistry, TranslationModule/ConfigMapper, Pipeline
- EXCLUDE: DeepL/Baidu/Youdao翻译器实现（P2）、LaMa ONNX修复器（P2）、竖排渲染（P2）、CJK字体（P1）等
- EXCLUDE: 单元测试本身（但每个任务都包含agent QA）

---

## Work Objectives

### Core Objective
修复5个P0阻塞，实现从选图 → 翻译管线执行 → 展示结果的完整闭环。

### Concrete Deliverables
- WorkspaceScreen 实际操作流程：URI→Bitmap→startTranslation→结果展示
- SettingsScreen 翻译设置页可访问
- ModelRegistry 真实模型URL和SHA256
- TranslationConfig 从DataStore读取用户设置
- 进度条 + 取消按钮 + 错误展示 UI

### Must Have
- 点击"翻译设置"能跳转到设置页（不是弹Toast）
- 选图后自动开始翻译（不是只展示图片）
- 翻译过程中有进度条
- 用户可以取消进行中的翻译
- 翻译出错时有错误提示
- 模型可以真实下载（真实URL + 正确SHA256）
- 设置的翻译参数（目标语言、翻译器类型等）实际影响翻译结果

### Must NOT Have
- UI重大改动（保持现有布局）
- 重构DI架构（最小化修改）
- 添加新功能（只修复已存在但断裂的功能）
- 修改ModelDownloadManager（它已经是好的，只改ModelRegistry的数据）

---

## Verification Strategy

### Test Decision
- **Infrastructure exists**: YES (bun... actually Android + compose UI tests exist)
- **Automated tests**: None for these fixes — they're about wiring and UI interaction
- **Agent QA**: Each task includes Playwright-like (Compose UI test) or interactive_bash scenarios

### QA Policy
每个任务必须包括agent执行的QA场景。由于这是App，主要用：
- **交互式测试**: 在模拟器上验证UI行为（导航、进度条、取消按钮）
- **代码级验证**: 检查修改的文件正确性、编译通过、类型安全
- **pipeline测试**: 通过TranslationPipelineTest验证管线触发正常

---

## Execution Strategy

### Parallel Waves

```
Wave 0 (✅ 已完成):
└── Task B3: 模型URL + CTC ONNX导出 + 模型集成 → DONE

Wave 1 (可并行):
├── Task B2: Settings导航修复 [trivial, 1 file]
└── Task B4: Config从DataStore读取 [medium, 2 files]

Wave 2 (依赖Wave 1):
└── Task B1: 管线连接 + URI加载 + 结果展示 [medium, 2 files]

Wave 3 (依赖Wave 2):
└── Task B5: 进度条+取消按钮+错误展示 [medium, 2 files]
```

---

## TODOs

- [x] B2. **设置页导航修复** — 1 file (SettingsScreen.kt)

  **What to do**:
  - `SettingsScreen.kt:57` 将 `Toast.makeText(...)` 替换为 `onNavigateToTranslation()` 调用
  - 导航已在 `AnimatedNavHost.kt:114` 正确配置：`onNavigateToTranslation = { navController.navigate(AppRoutes.SettingsTranslation.route) }`
  - 所以只需在 `onClick` 块中调用 `onNavigateToTranslation` 而非弹Toast

  **Must NOT do**:
  - 不要改动其他设置项的逻辑
  - 不要删除Toast import（其他地方可能还在用）

  **Files to modify**:
  - `app/src/main/java/.../ui/screens/SettingsScreen.kt` (line 57)

  **QA Scenarios**:
  ```
  Scenario: 翻译设置页可访问
    Tool: Compose UI test / 模拟器
    Steps:
      1. 打开App → 导航到底部Tab "Settings"
      2. 点击 "Translation" 设置项
      3. 验证：跳转到了 SettingsTranslationScreen（显示翻译设置内容）
    Expected Result: 不显示Toast "[test] 设置项暂不可修改"，直接显示翻译设置页
    Evidence: 截图或logcat输出

  Scenario: 其他设置项正常
    Tool: 模拟器
    Steps:
      1. 打开App → Settings
      2. 点击 "Appearance", "Debug & Logs", "About"
    Expected Result: 各设置页正常跳转
    Evidence: 截图
  ```

  **Commit**: YES
  - Message: `fix(ui): replace Toast placeholder with navigation to translation settings`
  - Files: `SettingsScreen.kt`
  - Pre-commit: `./gradlew :app:compileDebugKotlin`


- [x] B3. **模型URL替换为真实地址** — ✅ 已完成（CTC方案）

  **已完成的工作**:
  - `ModelRegistry.kt` 已更新为官方GitHub Release的真实URL和SHA256
  - CTD检测模型: 指向 `comictextdetector.pt.onnx`（ONNX格式，~25MB）
  - CTC OCR模型: **已打包在 assets/models/ocr_ctc_48px.onnx** (~0.8MB)，无需下载
  - alphabet字典: **已打包在 assets/models/alphabet-all-v5.txt**，无需下载
  - `scripts/export_ctc_onnx.py` 已编写，可将Python PyTorch模型导出为ONNX

  **CTC vs 原48px方案的优势**:
  - CTC模型只需一次前向传播（无自回归循环），推理更快
  - ONNX模型仅~0.8MB，可打包在APK中
  - 无需运行时下载OCR模型
  - 输入: `(N, 3, 48, W)` → 输出: `logits (N, T, vocab)` + `colors (N, T, 6)`
  - 使用CTC greedy decode（argmax → collapse → blank removal）

  **仍需要的工作**（此任务视为已完成）:
  无需额外改动。`ModelRegistry` 已是最终状态。

  **Files to modify**:
  - `app/src/main/java/.../translation/model/ModelRegistry.kt`

  **QA Scenarios**:
  ```
  Scenario: 模型可以下载
    Tool: 模拟器（adb + logcat）
    Preconditions:
      1. ModelRegistry已填入真实URL和SHA256
      2. 模拟器有网络连接
    Steps:
      1. 触发翻译（选择图片后自动开始）
      2. 观察 logcat 中 ModelDownloadManager 的下载输出
    Expected Result: 模型下载成功（SHA256验证通过）
    Evidence: logcat 输出 "download complete" 和 "sha256 verified"

  Scenario: SHA256验证失败的处理
    Tool: 修改一个字节让SHA256不匹配
    Steps:
      1. 修改某个模型文件的一个字节
      2. 重新触发翻译
    Expected Result: ModelDownloadManager报SHA256 mismatch错误，用户看到错误提示
    Evidence: logcat 输出 "SHA256 mismatch"
  ```

  **Commit**: YES
  - Message: `fix(models): replace placeholder model URLs with real download links`
  - Files: `ModelRegistry.kt`
  - Pre-commit: `./gradlew :app:compileDebugKotlin`


- [x] B4. **TranslationConfig从DataStore读取** — 已在 TranslationModule.kt 中修改

  **What to do**:
  问题: `TranslationModule.kt:98` 用 `single { TranslationConfig() }` 硬编码默认值。
  `TranslationConfigMapper` 存在但从没被调用过。
  
  **⚠️ 架构陷阱**: 
  `TextDetector`, `TextRecognizer`, `Translator`, `TranslationPipeline` **都是 singleton**，它们在构造时一次性地从 Koin 解析 `TranslationConfig`。所以仅把 `TranslationConfig` 变成 factory 是不够的——组件拿到的还是旧引用。
  
  但好消息是: `GptTranslator.translate()` 在调用时接收 `TranslatorConfig` 作为参数，所以 `apiKey`, `apiBase`, `model`, `targetLanguage` 这些值**可以动态传递**。

  修复方案（推荐: P0最小修改法 — Metis选项A）:
  
  **核心改动**:
  1. `TranslationConfig` → 从 `single` 改为 `factory`，使用 `PreferencesProvider.repository` 读取 DataStore
  2. `TranslationPipeline` → 从 `single` 改为 `factory`，以便它每次都重新解析 `TranslationConfig`
  3. `TextDetector` / `TextRecognizer` / `Translator` → **保持 singleton**（它们的type选择是per-session的，不影响配置值动态传递）

  **具体步骤**:

  **文件1** — `TranslationModule.kt`:
  ```kotlin
  // 修改前:
  single { TranslationConfig() }
  single {
      TranslationPipeline(
          detector = get(), recognizer = get(), merger = get(),
          translator = get(), maskRefiner = get(), inpainter = get(),
          renderer = get(), config = get(),
      )
  }

  // 修改后:
  factory {
      val prefs = kotlinx.coroutines.runBlocking {
          com.sakuravillager.manga_translator.data.preferences.PreferencesProvider
              .repository.getPreferences().first()
      }
      com.sakuravillager.manga_translator.translation.config.TranslationConfigMapper.map(prefs)
  }
  factory {
      TranslationPipeline(
          detector = get(), recognizer = get(), merger = get(),
          translator = get(), maskRefiner = get(), inpainter = get(),
          renderer = get(), config = get(),  // 现在会重新解析
      )
  }
  ```

  **文件2** — 注意: CTC识别器已改为从assets加载模型（`Model48pxTextRecognizer(context)`），不再需要 `ModelDownloadManager` 和 `OnnxSessionManager`。但检测器 `CtdTextDetector` 仍需要它们（从GitHub下载ONNX）。`TranslationModule` 中这两个singleton保留不变。

  **文件2** — 工作流验证: 确认 `WorkspaceViewModel` 的构建不会因为 factory 变化而出问题。
  
  WorkspaceScreen 创建 ViewModel 的方式是：
  ```kotlin
  val pipeline = KoinJavaComponent.get<TranslationPipeline>(TranslationPipeline::class.java)
  return WorkspaceViewModel(pipeline)
  ```
  每次 ViewModel 构建时都会获取新的 Pipeline → Pipeline 获取新的 Config → Config 从 DataStore 读取。

  **⚠️ 注意**: `runBlocking { ... first() }` 在Koin factory中使用是P0可以接受的——DataStore首次加载后在内存缓存中，`.first()` 几乎是即时的。这会在今后重构为纯异步方案。

  **文件3** — 如果还不行，最小化备选：在 `WorkspaceViewModel` 中注入 `PreferencesRepository`，在 `startTranslation()` 中先收集配置再调用 pipeline（不需改DI）。

  **Must NOT do**:
  - 不要将 TextDetector/TextRecognizer/Translator 也改为 factory（P0范围外）
  - 不要增加事件总线或reactive配置传播机制
  - 配置只需在开始翻译时读取一次

  **Files to modify**:
  - `app/src/main/java/.../translation/di/TranslationModule.kt`（主要）
  - 可能需要改 `WorkspaceViewModel.kt`（如果没有备选方案）
  - 不需要改 `TranslationConfigMapper.kt`（它已经是正确的）

  **QA Scenarios**:
  ```
  Scenario: 设置页修改翻译配置 → 翻译使用新配置
    Tool: 模拟器
    Preconditions: 模拟器有网络（翻译要走API）
    Steps:
      1. Settings → Translation → 修改 目标语言 为 "English"
      2. 回到首页 → 选择一张图片
      3. 翻译完成后查看结果
    Expected Result: 翻译文字为英文（而不是默认的CHS）
    Evidence: 修改前翻译CHS，修改后翻译ENG，结果不同

  Scenario: 默认配置可用
    Tool: 模拟器
    Steps:
      1. 不清除设置（首次运行，所有设置为默认值）
      2. 直接选择图片开始翻译
    Expected Result: 翻译正常进行（使用默认GPT-4 Vision + CHS + CTD检测 + 48px OCR）
    Evidence: 翻译完成，无错误
  ```

  **Commit**: YES
  - Message: `fix(config): wire TranslationConfigMapper to read user preferences from DataStore`
  - Files: `TranslationModule.kt`, `TranslationPipeline.kt`, `WorkspaceViewModel.kt`
  - Pre-commit: `./gradlew :app:compileDebugKotlin`


- [x] B1. **管线连接 + URI→Bitmap加载** — 已修改 WorkspaceViewModel + WorkspaceScreen

  **What to do**:
  问题: `WorkspaceScreen.kt` 选中图片后传到 WorkspaceScreen 作为 URI，但从未转换为 Bitmap 并触发管线。

  修复方案:
  1. **文件1** — `WorkspaceViewModel.kt`:
     - 添加 `startTranslationFromUri(context: Context, uriString: String)` 或类似方法
     - 或更好：在 ViewModel 中加载 Bitmap
  
  2. **文件2** — `WorkspaceScreen.kt`:
     - 在 `LaunchedEffect(imageUris)` 中，当 URI 可用时：
       a. 显示加载状态
       b. 将 URI 转换为 Bitmap（通过 content resolver）
       c. 调用 `viewModel.startTranslation(bitmap)`
       d. 将结果展示在 AsyncImage 中替代原图

  URI → Bitmap 转换方法（不需要Coil，直接使用 Android API）:
  ```kotlin
  fun uriToBitmap(context: Context, uri: Uri): Bitmap? {
      return try {
          context.contentResolver.openInputStream(uri)?.use { input ->
              BitmapFactory.decodeStream(input)
          }
      } catch (e: Exception) { null }
  }
  ```

  **关键设计决策**: 不需要一次性加载所有图片，先处理第一张（首页）。后续支持多页翻译时再加。

  **结果展示**: translation结果是一个 Bitmap，应该用 `AsyncImage` 或 `Image` 显示，取代当前硬编码的 `"https://picsum.photos/400/600"` 回退URL。

  **Must NOT do**:
  - 不要改动 `SelectPhotoScreen`（它已经正确传递URI）
  - 不要改动管线引擎本身（它是好的）
  - 不要实现批量处理（先只处理第一张图）

  **⚠️ 关键设计**: 翻译完成后，`TranslationResult.Success` 包含结果 Bitmap。
  ViewModel 当前只是 log 它但不展示。需要：
  - 在 `WorkspaceUiState` 中增加 `resultBitmap: Bitmap? = null` 字段
  - 在 `startTranslation()` 中 `TranslationResult.Success` 的处理内设置
  - Screen 端: `PillToggle` 切换时，SOURCE展示原图(`AsyncImage`)，TRANSLATED展示翻译后Bitmap(`Image`)

  **Files to modify**:
  - `app/src/main/java/.../ui/viewmodel/WorkspaceViewModel.kt`
  - `app/src/main/java/.../ui/screens/WorkspaceScreen.kt`

  **QA Scenarios**:
  ```
  Scenario: 选择图片后自动翻译
    Tool: 模拟器
    Preconditions:
      - 模拟器有测试图片
      - 模拟器有网络连接（翻译API）
    Steps:
      1. 打开 App → Home → 点击 "New Translation" 卡片
      2. 选择一张包含日文/中文的漫画图片
      3. 等待翻译完成
    Expected Result: 图片上的文字区域出现翻译后的文字（白色背景+黑色文字叠加在原图上）
    Evidence: 截图对比翻译前后的图片
    Timeout: 60秒（包含模型下载+检测+OCR+翻译+渲染）

  Scenario: 无文字图片
    Tool: 模拟器
    Steps:
      1. 选择一张没有文字的图片
    Expected Result: 显示 "No text found" 或原图不变
    Evidence: 截图

  Scenario: 翻译被取消
    Tool: 模拟器
    Steps:
      1. 开始翻译后立刻点击"取消"
    Expected Result: 翻译停止，回到初始状态
    Evidence: logcat 输出 "Translation cancelled"
  ```

  **Commit**: YES
  - Message: `fix(pipeline): wire WorkspaceScreen to trigger TranslationPipeline with selected image`
  - Files: `WorkspaceScreen.kt`, `WorkspaceViewModel.kt`
  - Pre-commit: `./gradlew :app:compileDebugKotlin`


- [x] B5. **进度条(百分比) + 取消按钮 + 错误展示** — 已在 WorkspaceScreen + WorkspaceViewModel 中添加

  **What to do**:
  在 `WorkspaceScreen.kt` 中添加翻译进度的完整UI反馈:

  **1. 百分比进度条 + 状态文字**:
  在图片上方或覆盖层显示:
  ```kotlin
  when (val progress = uiState.progress) {
      is TranslationProgress.Idle -> { /* 不显示 */ }
      is TranslationProgress.Loading -> {
          LinearProgressIndicator(modifier = Modifier.fillMaxWidth())  // indeterminate
          Text(progress.message)  // "Preparing models..."
      }
      is TranslationProgress.Processing -> {
          LinearProgressIndicator(
              progress = progress.progress,  // 0.1 ~ 0.85
              modifier = Modifier.fillMaxWidth()
          )
          Text("${(progress.progress * 100).toInt()}% - ${progress.message}")
      }
      is TranslationProgress.Done -> {
          LinearProgressIndicator(progress = 1f)
          Text("翻译完成 ✓")
      }
  }
  ```

  **2. 取消按钮**:
  - 当 `progress` 不是 Idle 和 Done 时显示 Cancel 按钮
  - 按钮位于进度条下方
  - 调用 `viewModel.cancelTranslation()`（需在ViewModel中添加此方法）

  **3. 错误展示**:
  - 在 `WorkspaceUiState` 中添加 `errorMessage: String? = null`
  - ViewModel 中 `TranslationResult.Error` 时设置
  - Screen 端用 Snackbar 展示错误

  **Must NOT do**:
  - 不要改变现有布局结构
  - 不要添加新页面或复杂的 UI 组件
  - 不要修改 TranslationPipeline（进度已经是 StateFlow 从它流出）

  **Files to modify**:
  - `app/src/main/java/.../ui/screens/WorkspaceScreen.kt`
  - `app/src/main/java/.../ui/viewmodel/WorkspaceViewModel.kt`

  **QA Scenarios**:
  ```
  Scenario: 翻译过程中显示进度条
    Tool: 模拟器
    Steps:
      1. 选择图片开始翻译
    Expected Result: 图片上方或覆盖层显示：
      - "Preparing models..." → LinearProgressIndicator(indeterminate)
      - "Detecting text..." → 进度 10%
      - "Recognizing text..." → 进度 25%  
      - "Merging text lines..." → 进度 35%
      - "Translating..." → 进度 50%
      - "Refining mask..." → 进度 60%
      - "Inpainting..." → 进度 70%
      - "Rendering text..." → 进度 85%
      - "翻译完成"
    Evidence: 录屏或截图序列

  Scenario: 取消按钮生效
    Tool: 模拟器
    Steps:
      1. 开始翻译
      2. 在进度出现后点击"取消"按钮
    Expected Result: 进度消失，回到 Idle 状态，logcat 输出 "Translation cancelled"
    Evidence: logcat 输出

  Scenario: 异常时显示错误
    Tool: 模拟器（断网）
    Steps:
      1. 关闭模拟器网络
      2. 选择图片开始翻译
    Expected Result: 显示错误消息（Snackbar或对话框），包含错误描述
    Evidence: 截图
  ```

  **Commit**: YES
  - Message: `feat(ui): add progress indicator, cancel button, and error display to workspace`
  - Files: `WorkspaceScreen.kt`, `WorkspaceViewModel.kt`
  - Pre-commit: `./gradlew :app:compileDebugKotlin`

---

## Success Criteria

### Verification Commands
```bash
./gradlew :app:compileDebugKotlin
# Expected: BUILD SUCCESSFUL (0 errors)
```

### Final Checklist
- [x] B2: Settings "Translation" 项点击后跳转到设置页
- [x] B3: ModelRegistry 有真实URL和SHA256
- [x] B4: 修改目标语言设置后翻译结果变化
- [x] B1: 选中图片后自动开始翻译，展示翻译结果
- [x] B5: 翻译过程中有进度条和取消按钮，出错时有错误提示
- [x] 编译通过，无新 warning