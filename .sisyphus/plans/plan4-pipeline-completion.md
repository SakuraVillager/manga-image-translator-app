# Plan 4+5: 管线收尾 — 修复 + 连接 + 三模块实现

## TL;DR

> **目标**: 修复 Plan 1-3 遗留的关键缺陷（DI 断开、死方法、资源泄漏），实现 MaskRefiner/Inpainter/TextRenderer 三个模块，把整个管线从"代码存在但不可运行"变成"端到端可执行"。
>
> **产出物**:
> - `MangaTranslatorApp` Application 子类 + `AndroidManifest.xml` 更新（修复 DI 引导）
> - `TranslationConfigMapper` 实现（连接 AppPreferences → TranslationConfig）
> - `OpenCVMaskRefiner` 实现（基于 OpenCV 的形态学膨胀）
> - `SimpleFillInpainter` 实现（白色/背景色填充遮罩区域）
> - `HorizontalTextRenderer` 实现（Canvas/Paint 横排文字渲染 + 透视变换）
> - `TranslationPipeline` 修复（所有模块的 prepare/release）
> - `TranslationModule` 更新（DI 注册新实现）
> - `WorkspaceViewModel` 连接 TranslationPipeline
> - `SettingsTranslationScreen` 连接 PreferencesRepository
> - `PreferencesRepository` 清理（移除4个死方法）
> - Noto Sans CJK 字体资产（20MB，用于文字渲染）
>
> **并行执行**: YES — 5 waves
> **关键路径**: T1 (DI修复) → T3-T5 (三模块) → T9-T12 (连接UI)

---

## Context

### 前置计划完成情况

| 计划 | 状态 | 关键产出 |
|------|------|----------|
| Plan 1 (基础骨架) | ✅ 完成 | 数据模型、7个接口、管线框架、Koin DI、NoOp存根 |
| Plan 2 (ONNX检测+OCR) | ✅ 完成 | CTD检测器、48px OCR、文本行合并、模型下载、OpenCV集成 |
| Plan 3 (云端翻译) | ✅ 完成 | GPT翻译器、词典系统、翻译校验、Ktor集成 |

### Metis 审查发现的缺陷

**🔴 严重 — Plan 4 必须修复**:
1. **DI 完全断开**: `KoinInitializer.start()` 从未被调用。没有 `Application` 子类。`AndroidManifest.xml` 缺少 `android:name`。运行时 Koin 会崩溃。
2. **PreferencesRepository 有4个死方法**: `updateTranslator()`/`updateTextDetector()`/`updateOcrEngine()`/`updateImageRepair()` 写入旧键，从未被 `getPreferences()` 读取。
3. **TranslationPipeline 只 prepare/release 2/7 模块**: 只有 detector/recognizer 有生命周期管理。其他5个模块永不准备也永不释放。

**🟡 中等 — 可能导致 bug**:
4. **TextBlock 存根属性**: `minRect` 返回 `RectF()`(零尺寸)，`center` 返回 `PointF()`(0,0)，`isHorizontal`/`isVertical` 硬编码。会破坏下游消费。
5. **OpenCV 未初始化**: `ensureOpenCVLoaded()` 存在但从未被调用。三个新模块都需要它。
6. **Bitmap 别名风险**: 如果 inpainter 是 NoOp，则 renderer 会直接修改输入 bitmap。
7. **无 CJK 字体**: 文字渲染需要字体资产，但项目中没有。
8. **inpaintingSize 被忽略**: NoOpInpainter 不使用 config 中的 `inpaintingSize`。

### 用户决策

| 决策 | 选项 |
|------|------|
| TextRenderer 范围 | 先横排，竖排CJK 留 P2 |
| ONNX 模型 | 未导出 — 文档化为前置条件 |
| MaskRefiner 技术 | OpenCV（项目已有依赖） |
| 模式 | TDD（先写测试，后实现） |

---

## Work Objectives

### 核心目标

把管线从"代码存在但不可运行"修复为"端到端可执行"，实现缺失的三个模块（MaskRefiner/Inpainter/TextRenderer），并通过正确连接 DI/ViewModel/Settings 打通 UI 到管线的完整调用链。

### 具体交付物

| # | 文件 | 类型 | 说明 |
|---|------|------|------|
| 1 | `MangaTranslatorApp.kt` | 新建 | Application 子类，调用 `KoinInitializer.start()` |
| 2 | `AndroidManifest.xml` | 修改 | 添加 `android:name=".MangaTranslatorApp"` |
| 3 | `translation/config/TranslationConfigMapper.kt` | 新建 | AppPreferences → TranslationConfig 映射 |
| 4 | `translation/mask/OpenCVMaskRefiner.kt` | 新建 | OpenCV 形态学膨胀遮罩优化 |
| 5 | `translation/inpaint/SimpleFillInpainter.kt` | 新建 | 白色/背景色填充修复 |
| 6 | `translation/render/HorizontalTextRenderer.kt` | 新建 | Canvas/Paint 横排文字渲染+透视变换 |
| 7 | `assets/fonts/` | 新建 | NotoSansCJK-Regular.ttf (20MB) |
| 8 | `translation/pipeline/TranslationPipeline.kt` | 修改 | 所有7模块的 prepare/release + bitmap防御性复制 |
| 9 | `translation/di/TranslationModule.kt` | 修改 | 注册新实现的工厂绑定 |
| 10 | `ui/viewmodel/WorkspaceViewModel.kt` | 修改 | 注入 TranslationPipeline，真实翻译调用 |
| 11 | `ui/screens/SettingsTranslationScreen.kt` | 修改 | 连接 PreferencesRepository |
| 12 | `data/preferences/PreferencesRepository.kt` | 修改 | 移除4个死方法 |
| 13 | 测试文件 (8个) | 新建 | JUnit + Android Instrumentation |

### 完成标准

- [ ] `./gradlew :app:assembleDebug` 编译通过，0 error
- [ ] `./gradlew :app:testDebugUnitTest` 全部通过
- [ ] `./gradlew :app:connectedAndroidTest` 插桩测试全部通过
- [ ] Koin DI 启动不崩溃（通过 `TranslationModuleTest` 验证）
- [ ] MaskRefiner 对已知遮罩膨胀产生预期大小的像素扩展
- [ ] Inpainter 在遮罩区域填充白色，非遮罩区域不变
- [ ] TextRenderer 在 bitmap 上绘制可见文字
- [ ] WorkspaceViewModel 通过 Koin 成功注入 TranslationPipeline
- [ ] SettingsTranslationScreen 写入 PreferencesRepository 后 StateFlow 更新

### 必须包含

- MangaTranslatorApp + manifest 修复
- TranslationConfigMapper 实现
- OpenCVMaskRefiner 实现
- SimpleFillInpainter 实现
- HorizontalTextRenderer 实现（仅横排）
- TranslationPipeline prepare/release 修复
- Koin DI 注册新实现
- ViewModel 管线连接
- Settings 首选项连接
- PreferencesRepository 清理
- 所有模块的 TDD 测试

### 必须不包含

- ❌ 竖排 CJK 文字渲染（P2 后续迭代）
- ❌ ONNX 模型导出/URL 更新（前置条件，非代码任务）
- ❌ Beam search OCR（P2 优化）
- ❌ 新 Gradle 依赖（OpenCV/Ktor/Koin 已存在）
- ❌ DeepL/Baidu/Youdao 翻译器
- ❌ LaMa ONNX 修复器
- ❌ 修改现有模块接口（MaskRefiner/Inpainter/TextRenderer 签名不变）
- ❌ AI slop: 过度注释、不必要的工具类、未使用的抽象

---

## Verification Strategy

> **TDD 工作流**: RED（先写失败测试）→ GREEN（最小实现）→ REFACTOR
> **测试基础设施**: JUnit 4 + AndroidJUnit4 + coroutines-test 已就绪

### 测试决策
- **Infrastructure exists**: YES (JUnit 4, AndroidJUnit4, OpenCV 安卓插桩)
- **Automated tests**: YES (TDD)
- **Framework**: JUnit 4 (JVM) + AndroidJUnit4 (instrumented)

### QA 策略
每个任务包含 Agent-Executed QA Scenarios — 精确到断言值的选择器/数据/预期结果。

---

## Execution Strategy

### Parallel Execution Waves

```
Wave 1 (必须最先 — DI 修复 + 配置映射 + 清理)
├── T1: MangaTranslatorApp + manifest 修复 [quick]
├── T2: TranslationConfigMapper 实现 [quick]
└── T3: PreferencesRepository 清理 [quick]

Wave 2 (并行 — 三个模块实现，TDD)
├── T4: OpenCVMaskRefiner + 测试 [deep]
├── T5: SimpleFillInpainter + 测试 [quick]
├── T6: HorizontalTextRenderer + 测试 [deep]
└── T7: 字体资产 [quick]

Wave 3 (依赖 Wave 1+2 — 管线修复 + DI 更新)
├── T8: TranslationPipeline prepare/release 修复 [quick]
└── T9: TranslationModule DI 更新 [quick]

Wave 4 (依赖 Wave 1-3 — UI 连接)
├── T10: WorkspaceViewModel 注入管线 [quick]
└── T11: SettingsTranslationScreen 连接首选项 [quick]

Wave 5 (验证)
├── T12: 插桩测试端到端管线 [unspecified-high]
└── T13: 构建验证 + lint [quick]
```

**关键路径**: T1 → T4/T5/T6 → T8/T9 → T10 → T12 → T13
**并行加速**: ~60% 比全顺序快
**最大并发**: 4 (Wave 2)

---

## TODOs

- [x] T1. **创建 MangaTranslatorApp + 修复 AndroidManifest**

  **What to do**:
  - 新建 `MangaTranslatorApp.kt`，继承 `Application()`，在 `onCreate()` 中调用 `KoinInitializer.start(applicationContext)`
  - 修改 `KoinInitializer.kt`：`start()` 方法需要接收 `androidContext()` 参数（`ModelDownloadManager` 需要）
  - 更新 `AndroidManifest.xml`：`<application android:name=".MangaTranslatorApp" ...>`
  - 编写 `KoinBootstrapTest`：验证 `startKoin` 后 `get<TranslationPipeline>()` 不抛异常

  **Must NOT do**:
  - 不修改 MainActivity 逻辑
  - 不添加新的 Application 级功能（仅 DI 初始化）

  **Recommended Agent Profile**:
  - **Category**: `quick` — 标准 Android/Koin 样板代码，单文件修改
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES (Wave 1, 与 T2/T3 并行)
  - **Parallel Group**: Wave 1 (with T2, T3)
  - **Blocks**: T4-T13（所有需要 DI 的任务）
  - **Blocked By**: 无

  **References**:
  - `translation/di/KoinInitializer.kt:1-21` — 已存在的 `start()` 方法，需改为接收 androidContext 参数
  - `translation/di/TranslationModule.kt:1-109` — DI 模块定义，了解注册了哪些 bean
  - `AndroidManifest.xml:8-17` — 当前 `<application>` 无 `android:name`

  **Acceptance Criteria** (TDD):
  - [ ] 测试文件: `app/src/test/java/.../translation/di/KoinBootstrapTest.kt`
  - [ ] `./gradlew :app:testDebugUnitTest --tests "*KoinBootstrapTest"` → PASS
  - [ ] `./gradlew :app:assembleDebug` 编译通过

  **QA Scenarios (TDD)**:
  ```
  Scenario: Koin starts without exception
    Tool: JUnit 4 (KoinTest)
    Preconditions: mock Application context
    Steps:
      1. 调用 KoinInitializer.start(mockContext)
      2. getKoin().get<TranslationPipeline>()
      3. 断言 TranslationPipeline != null
    Expected Result: No NoBeanDefFoundException
    Evidence: .sisyphus/evidence/plan4-t1-koin-bootstrap.txt

  Scenario: AndroidManifest has application name
    Tool: Bash (grep)
    Steps:
      1. grep 'android:name=".MangaTranslatorApp"' app/src/main/AndroidManifest.xml
    Expected Result: 匹配找到
    Evidence: .sisyphus/evidence/plan4-t1-manifest.txt
  ```

  **Commit**: YES
  - Message: `feat(di): create MangaTranslatorApp to bootstrap Koin DI`
  - Files: `MangaTranslatorApp.kt`, `KoinInitializer.kt`, `AndroidManifest.xml`, `KoinBootstrapTest.kt`

- [x] T2. **实现 TranslationConfigMapper**

  **What to do**:
  - 新建 `translation/config/TranslationConfigMapper.kt`
  - 实现 `fun map(prefs: AppPreferences): TranslationConfig` — 字符串→枚举映射
  - 映射规则：`detectorType`→`DetectorType`, `ocrEngineType`→`OcrEngineType`, `translatorType`→`TranslatorType`, `inpainterType`→`InpainterType`, `rendererType`→`RendererType`
  - 未知字符串回退到默认枚举值 + Log.w
  - 更新现有的 `TranslationConfigMapperTest.kt`（合约测试）为完整实现测试

  **Must NOT do**:
  - 不修改 TranslationConfig / AppPreferences 的字段签名
  - 不添加 @Serializable 注解

  **Recommended Agent Profile**:
  - **Category**: `quick` — 纯数据映射，单文件
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES (Wave 1)
  - **Blocks**: T10（ViewModel 需要 TranslationConfig）
  - **Blocked By**: T1（需要 Koin 就绪以进行集成测试）

  **References**:
  - `data/preferences/AppPreferences.kt:1-27` — 源：12 个 preference 字段含默认值
  - `translation/data/config/TranslationConfig.kt` — 目标：嵌套 config
  - `translation/data/config/DetectorType.kt` 等 — 目标枚举类型
  - `app/src/test/.../TranslationConfigMapperTest.kt` — 已有合约测试骨架

  **Acceptance Criteria** (TDD):
  - [ ] `./gradlew :app:testDebugUnitTest --tests "*TranslationConfigMapperTest"` → PASS
  - [ ] 8+ 测试用例覆盖所有枚举映射 + 回退行为

  **QA Scenarios (TDD)**:
  ```
  Scenario: Default prefs map to expected enums
    Tool: JUnit 4
    Steps:
      1. val prefs = AppPreferences()
      2. val config = TranslationConfigMapper.map(prefs)
      3. assertEquals(DetectorType.CTD, config.detector.detector)
      4. assertEquals(OcrEngineType.MODEL_48PX, config.ocr.ocr)
      5. assertEquals(TranslatorType.GPT_COMPATIBLE, config.translator.translator)
      6. assertEquals(InpainterType.SIMPLE_FILL, config.inpainter.inpainter)
      7. assertEquals(RendererType.DEFAULT, config.renderer.renderer)
    Expected Result: 所有枚举值匹配默认 AppPreferences 字符串
    Evidence: .sisyphus/evidence/plan4-t2-mapper.txt

  Scenario: Unknown string gracefully falls back
    Tool: JUnit 4
    Steps:
      1. val prefs = AppPreferences(detectorType = "unknown_xyz")
      2. val config = TranslationConfigMapper.map(prefs)
      3. assertEquals(DetectorType.CTD, config.detector.detector)
    Expected Result: 未知枚举回退默认值，不抛异常
    Evidence: .sisyphus/evidence/plan4-t2-fallback.txt
  ```

  **Commit**: YES
  - Message: `feat(config): implement TranslationConfigMapper bridging preferences to pipeline config`
  - Files: `TranslationConfigMapper.kt`, `TranslationConfigMapperTest.kt`

- [x] T3. **清理 PreferencesRepository 死方法**

  **What to do**:
  - 移除 `PreferencesRepository.kt` 中 4 个死方法：`updateTranslator()`, `updateTextDetector()`, `updateOcrEngine()`, `updateImageRepair()`
  - 搜索全项目：`grep -r "updateTranslator\|updateTextDetector\|updateOcrEngine\|updateImageRepair"` 确认无其他调用
  - 移除后编译验证

  **Must NOT do**:
  - 不删除任何新键或 `getPreferences()` 逻辑
  - 不修改其他文件的 public API

  **Recommended Agent Profile**:
  - **Category**: `quick` — 纯代码清理
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES (Wave 1)
  - **Blocks**: T11（Settings 连接依赖干净的 repo）
  - **Blocked By**: 无

  **References**:
  - `data/preferences/PreferencesRepository.kt` — 定位并移除 dead methods
  - `data/preferences/AppPreferences.kt` — 新字段定义

  **Acceptance Criteria**:
  - [ ] `./gradlew :app:compileDebugKotlin` 编译通过，0 error
  - [ ] `grep` 搜索死方法名返回空（除测试外）

  **QA Scenarios**:
  ```
  Scenario: Dead methods removed, compile clean
    Tool: Bash (gradle)
    Steps:
      1. ./gradlew :app:compileDebugKotlin 2>&1
      2. 断言 output contains "BUILD SUCCESSFUL"
    Expected Result: 编译通过，无未解析引用
    Evidence: .sisyphus/evidence/plan4-t3-compile.txt
  ```

  **Commit**: YES
  - Message: `refactor(prefs): remove dead update methods writing to orphaned keys`
  - Files: `PreferencesRepository.kt`

---

## Final Verification Wave

- [ ] T12. **插桩测试：端到端管线**

  **What to do**:
  - 在 `app/src/androidTest/` 中编写 `TranslationPipelineE2ETest.kt`
  - 创建一个简单的测试 bitmap（200x200 白色背景 + 黑色文字区域）
  - 使用真实的模块（NoOp 除外 — 翻译器用 OriginalTranslator 或 NoOp 以保持测试可运行且无网络依赖）：
    - TextDetector: NoOp（返回固定检测结果，避免依赖 ONNX 模型）
    - TextRecognizer: NoOp（返回固定 OCR 文本）
    - TextlineMerger: `DefaultTextlineMerger`（真实）
    - Translator: `OriginalTranslator`（原样返回，无网络依赖）
    - MaskRefiner: `OpenCVMaskRefiner`（真实）
    - Inpainter: `SimpleFillInpainter`（真实）
    - TextRenderer: `HorizontalTextRenderer`（真实）
  - 验证：管线完整运行，不崩溃，返回 `TranslationResult.Success`
  - 验证：`progress` StateFlow 发出 `Idle → Loading → Processing* → Done` 序列

  **Must NOT do**:
  - 不依赖 ONNX 模型文件（检测/OCR 用 NoOp/存根数据）
  - 不依赖网络（翻译器用 OriginalTranslator）

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high` — 端到端集成测试，需要多个模块协作
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES (Wave 5, 与 T13 并行)
  - **Parallel Group**: Wave 5
  - **Blocks**: 无（最终验证）
  - **Blocked By**: T8, T9, T10

  **References**:
  - `app/src/androidTest/.../TranslationPipelineTest.kt` — 已有的管线测试（NoOp 模块）
  - `translation/di/TranslationModule.kt` — DI 绑定（需为测试创建专用模块）

  **Acceptance Criteria**:
  - [ ] `./gradlew :app:connectedAndroidTest --tests "*TranslationPipelineE2ETest"` → PASS
  - [ ] 管线 8 个步骤全部执行，不崩溃
  - [ ] progress StateFlow 发出预期的状态序列

  **QA Scenarios**:
  ```
  Scenario: Full pipeline runs end-to-end with real mask/inpaint/render modules
    Tool: AndroidJUnit4
    Steps:
      1. 创建测试 TranslationPipeline（检测/OCR 用 NoOp 存根，其余真实）
      2. 调用 pipeline.translate(testBitmap)
      3. 断言 result is TranslationResult.Success
      4. 断言 result.bitmap != null
      5. 断言 result.textRegions.isNotEmpty()
    Expected Result: 管线端到端完成，返回成功结果
    Failure Indicators: Exception 被抛出，result is TranslationResult.Error
    Evidence: .sisyphus/evidence/plan4-t12-e2e.txt
  ```

  **Commit**: YES
  - Message: `test(e2e): add instrumented end-to-end pipeline test with real modules`
  - Files: `TranslationPipelineE2ETest.kt`

- [ ] T13. **构建验证 + Lint**

  **What to do**:
  - 运行 `./gradlew :app:assembleDebug` 确认所有新文件编译通过
  - 运行 `./gradlew :app:testDebugUnitTest` 确认所有 JVM 测试通过
  - 运行 `./gradlew :app:connectedAndroidTest` 确认所有插桩测试通过（期望：T4/T5/T6/T12 测试全部 PASS）
  - 运行 `./gradlew :app:lintDebug` 确认零新增 lint 错误
  - 汇总测试结果写入 `build_output.txt`

  **Must NOT do**:
  - 不跳过任何失败测试

  **Recommended Agent Profile**:
  - **Category**: `quick` — 构建验证
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: NO（依赖所有前置任务）
  - **Parallel Group**: Wave 5
  - **Blocks**: 无（最后一步）
  - **Blocked By**: T12

  **Acceptance Criteria**:
  - [ ] `assembleDebug` → BUILD SUCCESSFUL, 0 error
  - [ ] `testDebugUnitTest` → ALL TESTS PASSED
  - [ ] `connectedAndroidTest` → ALL TESTS PASSED
  - [ ] `lintDebug` → 0 new errors

  **QA Scenarios**:
  ```
  Scenario: All tests pass, build succeeds
    Tool: Bash (gradle)
    Steps:
      1. ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
      2. 断言 exit code == 0
      3. 断言 output does not contain "FAILED"
      4. 断言 output does not contain "ERROR"
    Expected Result: 编译+测试+lint 全部通过
    Evidence: build_output.txt
  ```

  **Commit**: NO（验证步骤，不产生代码变更）

---

## Final Verification Wave

> **4 个审查代理并行运行。ALL 必须 APPROVE。向用户展示综合结果并获得明确的"okay"后才标记完成。**

- [ ] F1. **Plan Compliance Audit** — `oracle`
  阅读计划端到端。对每个"必须包含"项：验证实现存在（读文件、运行测试）。对每个"必须不包含"项：搜索代码库中的禁止模式（如发现则拒绝并附 file:line）。验证 evidence 文件存在于 `.sisyphus/evidence/`。将交付物与计划对比。
  输出: `必须包含 [N/N] | 必须不包含 [N/N] | 任务 [N/N] | VERDICT: APPROVE/REJECT`

- [ ] F2. **Code Quality Review** — `unspecified-high`
  运行 `./gradlew :app:lintDebug`。审查所有变更文件：`!!` 非空断言、未释放的 Mat 对象、硬编码字符串、Magic numbers、重复代码。检查 AI slop：过多注释、过度抽象、未使用的参数。
  输出: `Lint [PASS/FAIL] | Tests [N pass/N fail] | Files [N clean/N issues] | VERDICT`

- [ ] F3. **Real Manual QA** — `unspecified-high`
  从干净状态开始。执行每个任务的 QA Scenario — 按照确切的步骤，捕获 evidence。测试跨任务集成（MaskRefiner→Inpainter→TextRenderer 管道）。测试边缘情况：空 mask、空 textRegions、空 translation。
  输出: `Scenarios [N/N pass] | Integration [N/N] | Edge Cases [N tested] | VERDICT`

- [ ] F4. **Scope Fidelity Check** — `deep`
  对每个任务：阅读"需要做的事情"，阅读实际 diff（git log/diff）。验证1:1 — 规范中的所有内容都已构建（无遗漏），规范之外的内容未构建（无蔓延）。检查"必须不包含"的合规性。检测跨任务污染。标记未记录的变更。
  输出: `Tasks [N/N compliant] | Contamination [CLEAN/N issues] | Unaccounted [CLEAN/N files] | VERDICT`

---

## Commit Strategy

- **T1**: `feat(di): create MangaTranslatorApp to bootstrap Koin DI` — MangaTranslatorApp.kt, KoinInitializer.kt, AndroidManifest.xml
- **T2**: `feat(config): implement TranslationConfigMapper bridging preferences to pipeline config` — TranslationConfigMapper.kt
- **T3**: `refactor(prefs): remove dead update methods writing to orphaned keys` — PreferencesRepository.kt
- **T4**: `feat(mask): implement OpenCVMaskRefiner with morphological dilation` — OpenCVMaskRefiner.kt, test
- **T5**: `feat(inpaint): implement SimpleFillInpainter filling masked regions with white` — SimpleFillInpainter.kt, test
- **T6**: `feat(render): implement HorizontalTextRenderer with Canvas/Paint text drawing` — HorizontalTextRenderer.kt, test
- **T7**: `feat(assets): add Noto Sans CJK font for text rendering` — assets/fonts/
- **T8**: `fix(pipeline): prepare/release all 7 modules, add defensive bitmap copy, fix TextBlock stubs` — TranslationPipeline.kt, DefaultTextlineMerger.kt
- **T9**: `feat(di): register OpenCVMaskRefiner, SimpleFillInpainter, HorizontalTextRenderer in Koin` — TranslationModule.kt
- **T10**: `feat(ui): inject TranslationPipeline into WorkspaceViewModel, replace mock data` — WorkspaceViewModel.kt
- **T11**: `feat(settings): wire SettingsTranslationScreen to PreferencesRepository, remove toast stubs` — SettingsTranslationScreen.kt
- **T12**: `test(e2e): add instrumented end-to-end pipeline test with real modules` — TranslationPipelineE2ETest.kt
- **T13**: 不提交（验证步骤）

---

## Success Criteria

### 验证命令
```bash
./gradlew :app:assembleDebug          # 编译通过，0 error
./gradlew :app:testDebugUnitTest      # 所有 JVM 测试通过
./gradlew :app:connectedAndroidTest   # 所有插桩测试通过
./gradlew :app:lintDebug              # 0 新增 lint 错误
```

### 最终检查清单
- [ ] Koin DI 启动不崩溃
- [ ] TranslationPipeline 可被 Koin 注入
- [ ] WorkspaceViewModel 通过 Koin 获取 TranslationPipeline 实例
- [ ] MaskRefiner 对已知 mask 正确膨胀
- [ ] Inpainter 正确填充遮罩区域
- [ ] TextRenderer 正确绘制横排文字
- [ ] 管线端到端运行不崩溃
- [ ] Settings 设置持久化到 PreferencesRepository
- [ ] 所有 "必须不包含" 项未出现

- [x] T8. **修复 TranslationPipeline prepare/release + Bitmap 防御**

  **What to do**:
  - 在 `translate()` 方法开头，对所有 7 个模块调用 `prepare()`（不只是 detector/recognizer）
  - 在 `finally` 块中，对所有 7 个模块调用 `release()`：
    `detector.release(); recognizer.release(); merger.release(); translator.release(); maskRefiner.release(); inpainter.release(); renderer.release()`
  - 在 `try` 块的第 6 步（inpainting 之后）添加防御性 bitmap 复制：
    `val inpaintedCopy = ctx.imgInpainted!!.copy(Bitmap.Config.ARGB_8888, false)`
    然后将副本传给 `renderer.render(inpaintedCopy, ...)`
  - 修复 TextBlock 存根属性：`isHorizontal` / `isVertical` / `minRect` / `center`。
    - 在 `DefaultTextlineMerger.buildTextBlock()` 中计算实际的 `minRect`（merged bounding rect）和 `center`（从 minRect 推导）
    - 从 `textlines` 的 `direction` 字段多数投票决定 `isHorizontal` / `isVertical`
  - 编写/更新 `TranslationPipelineTest` 验证 prepare/release 顺序

  **Must NOT do**:
  - 不修改 TranslationPipeline 的 public API 签名
  - 不修改模块接口

  **Recommended Agent Profile**:
  - **Category**: `quick` — 管线修复，小改动
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: NO（依赖 T4-T7 完成后才能正确调用 prepare/release）
  - **Parallel Group**: Wave 3 (sequential after Wave 2)
  - **Blocks**: T12
  - **Blocked By**: T4, T5, T6（三个模块的 prepare/release 实现）

  **References**:
  - `translation/pipeline/TranslationPipeline.kt:35-105` — 当前的 prepare/release 逻辑（仅 detector/recognizer）
  - `translation/merge/DefaultTextlineMerger.kt` — `buildTextBlock()` 方法，需在此计算 minRect/center
  - `translation/data/TextBlock.kt:20-23` — 当前存根属性需改为真实计算

  **Acceptance Criteria**:
  - [ ] `./gradlew :app:compileDebugKotlin` 编译通过
  - [ ] 所有 7 个模块的 `prepare()` 在翻译开始时被调用
  - [ ] 所有 7 个模块的 `release()` 在 finally 中被调用（含幂等保护）
  - [ ] inpainting 后创建防御性 bitmap 副本

  **QA Scenarios (TDD)**:
  ```
  Scenario: All 7 modules prepared before pipeline steps
    Tool: JUnit 4 (unit, with mock modules)
    Preconditions: Mock 7 modules injecting into pipeline
    Steps:
      1. 创建带 mock 模块的 TranslationPipeline
      2. 调用 pipeline.translate(testBitmap)
      3. 验证每个 mock 的 prepare() 被调用恰好 1 次
      4. 验证每个 mock 的 release() 被调用恰好 1 次
    Expected Result: prepare 调用计数 = 7, release 调用计数 = 7
    Evidence: .sisyphus/evidence/plan4-t8-lifecycle.txt

  Scenario: TextBlock has valid minRect after merge
    Tool: JUnit 4
    Steps:
      1. val merged = merger.merge(quadrilaterals, 800, 600)
      2. merged.forEach { assertFalse(it.minRect.isEmpty) }
      3. merged.forEach { assertNotEquals(PointF(), it.center) }
    Expected Result: 所有 TextBlock 有非零 minRect 和非零 center
    Evidence: .sisyphus/evidence/plan4-t8-textblock.txt
  ```

  **Commit**: YES
  - Message: `fix(pipeline): prepare/release all 7 modules, add defensive bitmap copy, fix TextBlock stubs`
  - Files: `TranslationPipeline.kt`, `DefaultTextlineMerger.kt`, `TextBlock.kt`

- [x] T9. **更新 TranslationModule DI 注册**

  **What to do**:
  - 在 `TranslationModule.kt` 中添加新实现的工厂绑定：
    - `factory<MaskRefiner> { OpenCVMaskRefiner() }` — 替换 NoOpMaskRefiner 绑定
    - `factory<Inpainter> { SimpleFillInpainter() }` — 替换 NoOpInpainter 绑定
    - `factory<TextRenderer> { HorizontalTextRenderer(androidContext()) }` — 替换 NoOpTextRenderer 绑定
  - 移除旧的 NoOp 绑定（或注释保留供未来条件选择）
  - 确保 Constructor injection 正确（`TranslationPipeline` 的构造函数需要 7 个参数全部可得）
  - 编写 `TranslationModuleTest` 验证新的注入：
    - `get<MaskRefiner>()` 返回 `OpenCVMaskRefiner` 实例
    - `get<Inpainter>()` 返回 `SimpleFillInpainter` 实例
    - `get<TextRenderer>()` 返回 `HorizontalTextRenderer` 实例

  **Must NOT do**:
  - 不删除 TranslationModule 中 NoOp 文件的 import（保留引用）
  - 不修改 KoinInitializer

  **Recommended Agent Profile**:
  - **Category**: `quick` — DI 配置更新
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: NO（依赖 T4/T5/T6 完成）
  - **Parallel Group**: Wave 3
  - **Blocks**: T10, T12
  - **Blocked By**: T4, T5, T6

  **References**:
  - `translation/di/TranslationModule.kt:1-109` — 当前 DI 模块，需修改 factory 绑定
  - `translation/mask/OpenCVMaskRefiner.kt` — T4 产出
  - `translation/inpaint/SimpleFillInpainter.kt` — T5 产出
  - `translation/render/HorizontalTextRenderer.kt` — T6 产出

  **Acceptance Criteria**:
  - [ ] `./gradlew :app:testDebugUnitTest --tests "*TranslationModuleTest"` → PASS
  - [ ] `get<MaskRefiner>()` instance is `OpenCVMaskRefiner`
  - [ ] `get<Inpainter>()` instance is `SimpleFillInpainter`
  - [ ] `get<TextRenderer>()` instance is `HorizontalTextRenderer`
  - [ ] `get<TranslationPipeline>()` 构造函数注入成功（7 个参数全部 resolved）

  **QA Scenarios (TDD)**:
  ```
  Scenario: Koin resolves real implementations
    Tool: JUnit 4 (KoinTest)
    Steps:
      1. startKoin { modules(translationModule) }
      2. val maskRefiner = get<MaskRefiner>()
      3. assertTrue(maskRefiner is OpenCVMaskRefiner)
      4. val inpainter = get<Inpainter>()
      5. assertTrue(inpainter is SimpleFillInpainter)
      6. val renderer = get<TextRenderer>()
      7. assertTrue(renderer is HorizontalTextRenderer)
      8. val pipeline = get<TranslationPipeline>()
      9. assertNotNull(pipeline)
    Expected Result: 所有 7 个依赖正确解析，无 NoBeanDefFoundException
    Evidence: .sisyphus/evidence/plan4-t9-di.txt
  ```

  **Commit**: YES
  - Message: `feat(di): register OpenCVMaskRefiner, SimpleFillInpainter, HorizontalTextRenderer in Koin`
  - Files: `TranslationModule.kt`, `TranslationModuleTest.kt`

- [x] T10. **WorkspaceViewModel 注入 TranslationPipeline**

  **What to do**:
  - 修改 `WorkspaceViewModel.kt`：移除 mock 数据，添加 `private val pipeline: TranslationPipeline` 构造函数参数
  - 添加 `fun startTranslation(bitmap: Bitmap, config: TranslationConfig)` 方法：
    - 用 `viewModelScope.launch` 启动协程
    - 调用 `pipeline.translate(bitmap)` (pipeline 内部已持有 config)
    - 收集 `pipeline.progress` StateFlow 更新 `_uiState`
    - 处理 `TranslationResult.Success/NoText/Cancelled/Error`
  - 更新 `WorkspaceUiState`：用 `TranslationProgress` sealed class 替换旧的 `ViewState` enum
  - 确保 Koin 能注入 `TranslationPipeline`（通过 `WorkspaceViewModel(pipeline = get())` 或构造函数注入）

  **Must NOT do**:
  - 不修改 WorkspaceScreen UI（UI 已有 Source/Translated toggle，用 mock 数据。先只改 ViewModel 数据源）
  - 不处理图片 URI → Bitmap 转换（在 SelectPhotoViewModel 或 Screen 层处理）

  **Recommended Agent Profile**:
  - **Category**: `quick` — ViewModel 重构，替换 mock 数据为真实管线调用
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: NO（依赖 T9 DI 更新）
  - **Parallel Group**: Wave 4
  - **Blocks**: T12
  - **Blocked By**: T9

  **References**:
  - `ui/viewmodel/WorkspaceViewModel.kt:1-54` — 当前 mock 数据 ViewModel
  - `translation/pipeline/TranslationPipeline.kt` — 管线入口
  - `translation/pipeline/TranslationProgress.kt` — 进度 sealed class
  - `translation/pipeline/TranslationResult.kt` — 结果 sealed class

  **Acceptance Criteria**:
  - [ ] `./gradlew :app:testDebugUnitTest --tests "*WorkspaceViewModel*"` → PASS
  - [ ] `WorkspaceViewModel` 不包含任何 mock 数据初始化
  - [ ] `pipeline.progress` 的 StateFlow 被正确收集到 `_uiState`

  **QA Scenarios (TDD)**:
  ```
  Scenario: ViewModel calls pipeline on startTranslation
    Tool: JUnit 4 (coroutines-test)
    Steps:
      1. 创建 mock TranslationPipeline，mock translate() 返回 Success(mockBitmap, emptyList())
      2. 创建 WorkspaceViewModel(mockPipeline)
      3. 调用 viewModel.startTranslation(testBitmap, testConfig)
      4. advanceUntilIdle()
      5. 验证 mockPipeline.translate() 被调用恰好 1 次
      6. 断言 uiState.value 是 Done 状态
    Expected Result: 管线被调用，UI 状态更新为 Done
    Evidence: .sisyphus/evidence/plan4-t10-viewmodel.txt
  ```

  **Commit**: YES
  - Message: `feat(ui): inject TranslationPipeline into WorkspaceViewModel, replace mock data`
  - Files: `WorkspaceViewModel.kt`

- [x] T11. **SettingsTranslationScreen 连接 PreferencesRepository**

  **What to do**:
  - 修改 `SettingsTranslationScreen.kt`：
    - 注入 `PreferencesRepository`（通过 `LocalContext.current` + `PreferencesProvider` 单例，或通过 Koin `get()`）
    - 读取当前 preferences：`preferencesRepository.getPreferences().collectAsState()`
    - 用实际 `preferencesRepository.updateApiKey/updateApiBase/updateModelName/...` 替换所有 `Toast("[test] 设置项暂不可修改")` 调用
    - 每个设置项的 onChange 调用对应的 update 方法
  - Settings 字段映射：
    - Translator → `updateTranslatorType()` (T3 清理后需要新建该方法或直接用新 API)
    - Text Direction → `updateTextDirection()`
    - Text Detector → `updateDetectorType()`
    - OCR Engine → `updateOcrEngineType()`
    - Image Repair → `updateInpainterType()`
  - 如有需要，在 `PreferencesRepository` 中补充缺失的更新方法

  **Must NOT do**:
  - 不新建 SettingsTranslationViewModel（直接在 Screen 中用 `collectAsState`）
  - 不修改 SettingsTranslationScreen 的 UI 布局（只改数据绑定）

  **Recommended Agent Profile**:
  - **Category**: `quick` — UI 数据绑定替换
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: NO（依赖 T3 清理 + T9 DI）
  - **Parallel Group**: Wave 4
  - **Blocks**: 无
  - **Blocked By**: T3, T9

  **References**:
  - `ui/screens/SettingsTranslationScreen.kt:1-83` — 当前 UI，所有 handler 是 Toast
  - `data/preferences/PreferencesRepository.kt` — 清理后的 repo
  - `data/preferences/AppPreferences.kt` — preference 字段定义

  **Acceptance Criteria**:
  - [ ] `./gradlew :app:assembleDebug` 编译通过
  - [ ] SettingsTranslationScreen 不再包含 `Toast.makeText(..., "[test] 设置项暂不可修改")`
  - [ ] 每个设置项的 onChange 调用 PreferencesRepository 的对应 update 方法

  **QA Scenarios**:
  ```
  Scenario: Changing translator setting persists via PreferencesRepository
    Tool: JUnit 4 (ViewModel 或 UI test)
    Steps:
      1. 注入 mock PreferencesRepository
      2. 用户选择 GPT 翻译器
      3. 验证 `mockRepo.updateTranslatorType("gpt_compatible")` 被调用
      4. 验证 `mockRepo.getPreferences()` flow 发出更新后的值
    Expected Result: 设置修改持久化到 PreferencesRepository
    Evidence: .sisyphus/evidence/plan4-t11-settings.txt
  ```

  **Commit**: YES
  - Message: `feat(settings): wire SettingsTranslationScreen to PreferencesRepository, remove toast stubs`
  - Files: `SettingsTranslationScreen.kt`, `PreferencesRepository.kt`

- [x] T4. **实现 OpenCVMaskRefiner（遮罩优化）**
- [x] T5. **实现 SimpleFillInpainter（图像修复）**
- [x] T6. **实现 HorizontalTextRenderer（横排文字渲染）**
- [x] T7. **添加 Noto Sans CJK 字体资产**

  **What to do**:
  - 下载 NotoSansCJK-Regular.ttf（约 20MB）到 `app/src/main/assets/fonts/`
  - 如果文件太大，可以使用 NotoSansSC-Regular.ttf（仅简体中文，约 5MB）或 NotoSansJP-Regular.otf（仅日文，约 5MB）
  - 验证 assets 目录存在，如不存在则创建
  - 确认 assets 路径为 `app/src/main/assets/fonts/NotoSansCJK-Regular.ttf`
  - 编写测试验证字体可加载：`Typeface.createFromAsset(context.assets, "fonts/NotoSansCJK-Regular.ttf")` 不抛异常

  **Must NOT do**:
  - 不修改 TextRenderer 接口或 HorizontalTextRenderer 实现（T6 负责）

  **Recommended Agent Profile**:
  - **Category**: `quick` — 文件下载 + 目录创建
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES (Wave 2，与 T4/T5 并行；T6 依赖此任务)
  - **Blocks**: T6
  - **Blocked By**: 无

  **References**:
  - 字体来源: `https://github.com/googlefonts/noto-cjk/raw/main/Sans/OTF/SimplifiedChinese/NotoSansCJKsc-Regular.otf` 或 Android 系统字体目录

  **Acceptance Criteria**:
  - [ ] `ls app/src/main/assets/fonts/` 返回至少一个 .ttf 或 .otf 文件
  - [ ] `Typeface.createFromAsset(context.assets, "fonts/[fontname]")` 测试通过

  **QA Scenarios**:
  ```
  Scenario: Font file loads without exception
    Tool: AndroidJUnit4
    Steps:
      1. val assets = InstrumentationRegistry.getInstrumentation().context.assets
      2. val typeface = Typeface.createFromAsset(assets, "fonts/NotoSansCJK-Regular.ttf")
      3. 断言 typeface != null
      4. 断言 typeface != Typeface.DEFAULT（非系统回退字体）
    Expected Result: 正确加载自定义字体
    Evidence: .sisyphus/evidence/plan4-t7-font.txt
  ```

  **Commit**: YES
  - Message: `feat(assets): add Noto Sans CJK font for text rendering`
  - Files: `app/src/main/assets/fonts/`

