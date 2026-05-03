# Plan 3: 云端翻译 + 词典 + 配置映射

## TL;DR

> **目标**: 实现 GPT 兼容翻译器（覆盖 OpenAI/DeepSeek/Groq/自定义端点），集成纯文本正则词典系统（pre/post-dict），更新 AppPreferences 以匹配新 TranslationConfig。
>
> **产出物**:
> - `GptTranslator` — OpenAI 兼容翻译器（支持 4+ 服务端）
> - 词典系统 — `DictionaryLoader` + 管线集成
> - 翻译后校验 — 幻觉检测 + 重复文本过滤
> - AppPreferences 更新 — 迁移到新 enum/config 类型
> - Ktor Client + kotlinx.serialization 依赖
>
> **并行执行**: NO — 依赖顺序：基础设施 → 翻译器 → 管线集成 → 偏好更新
> **关键路径**: 添加依赖 → GptTranslator 实现 → 管线集成 → AppPreferences 更新
> **难度**: ⭐⭐⭐

---

## Context

### 已完成模块
- ✅ Plan 1: 数据模型、接口定义、管线骨架、Koin DI
- ✅ Plan 2: ONNX 集成、CTD 检测器、48px OCR、文本行合并、模型下载

### 待实现（本次）
- ❌ Translator — 当前是 `NoOpTranslator`（原样返回输入）
- ❌ 词典系统 — `applyPreDictionary`/`applyPostDictionary` 是空桩（第97-101行）
- ❌ 翻译后校验 — `filterInvalidTranslations` 是空桩
- ❌ HTTP 客户端 — 项目无 Ktor/OkHttp/Retrofit
- ❌ JSON 解析 — 无 kotlinx.serialization
- ❌ AppPreferences — 仍使用旧字符串默认值

### 现有接口（不变）

```kotlin
// translation/api/Translator.kt
interface Translator : PipelineModule {
    override val name: String
    val supportedSourceLanguages: Set<String>
    val supportedTargetLanguages: Set<String>
    suspend fun translate(
        texts: List<String>,
        fromLanguage: String,      // 管线传 "auto"
        toLanguage: String,        // 来自 config.translator.targetLanguage
        config: TranslatorConfig,
    ): List<String>
    fun supportsLanguagePair(from: String, to: String): Boolean
}
```

### 管线调用上下文

```kotlin
// TranslationPipeline.kt:57-65
val texts = ctx.textRegions.map { it.text }
val translations = translator.translate(
    texts, "auto", config.translator.targetLanguage, config.translator
)
ctx.textRegions = ctx.textRegions.zip(translations) { region, translation ->
    region.copy(translation = translation)
}
```

---

## Work Objectives

### Core Objective
用真实的 GPT 兼容翻译器替换 `NoOpTranslator`，加入词典系统和翻译后校验，更新配置持久化层。

### Concrete Deliverables
- `translation/translator/GptTranslator.kt` — OpenAI 兼容翻译器
- `translation/dict/DictionaryLoader.kt` — 词典加载和应用
- `translation/translator/TranslationValidator.kt` — 翻译后校验
- 更新 `TranslationPipeline.kt` — 集成词典+校验调用
- 更新 `TranslationModule.kt` — 条件绑定真实翻译器
- 更新 `AppPreferences.kt` — 迁移到新类型
- 更新 `libs.versions.toml` + `app/build.gradle.kts` — 添加 Ktor + serialization
- 更新 `translation/config/TranslationConfigMapper.kt` — 配置映射

### Must Have
- GptTranslator 支持 `apiBase`/`apiKey`/`model` 可配置
- 词典系统兼容 Python 版纯文本格式
- 翻译后重复文本检测
- Koin DI 根据 `TranslatorType` 选择真实翻译器

### Must NOT Do
- DeepL/Baidu/Youdao 翻译器（保留 NoOp 桩）
- UI 层面的翻译器选择器改动（SettingsTranslationScreen 只改 ViewModel 数据绑定）
- 上下文翻译（多页漫画上下文 — 后续计划）
- JSON 模式的翻译请求（GPT JSON mode — 后续计划）
- 翻译重试机制（MVP 先不做，后续加入）

---

## Verification Strategy

### 测试基础设施
- **框架**: JUnit 4 (现有 test/ + androidTest/)
- **测试策略**: 编写单元测试 + 翻译器集成测试

### QA Policy
- `GptTranslator` 单元测试：mock HTTP 响应，验证 JSON 解析和错误处理
- `DictionaryLoader` 单元测试：加载示例词典文件，验证替换正确性
- `TranslationValidator` 单元测试：输入各种翻译结果，验证过滤逻辑
- Agent-Executed QA：不适用（云端 API 依赖，只做逻辑验证）

---

## TODOs

- [x] 1. **添加 Ktor Client + kotlinx.serialization 依赖**

  **What to do**:
  - 在 `gradle/libs.versions.toml` 添加版本号：
    ```toml
    ktor = "3.1.2"
    kotlinx-serialization = "1.7.3"
    ```
  - 添加 library 定义（ktor-client-core, ktor-client-cio, ktor-client-content-negotiation, ktor-serialization-kotlinx-json）
  - 在 `app/build.gradle.kts` 添加：
    ```kotlin
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    ```
  - 添加 `kotlinx.serialization` 插件到根 build.gradle.kts
  - Sync Gradle，确认编译通过

  **Must NOT do**:
  - 不添加 OkHttp engine（用 CIO engine，纯 Kotlin）
  - 不添加 Retrofit 或 Moshi

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: `[]`

  **Parallelization**: 顺序，Wave 1 第一个任务
  - **Blocks**: Task 2-6

  **References**:
  - `gradle/libs.versions.toml` — 现有版本目录，按此格式添加
  - `app/build.gradle.kts` — 现有依赖声明位置，在此追加
  - 根 `build.gradle.kts` — 检查 plugins 块，可能需要添加 kotlinx.serialization 插件

  **Commit**: YES
  - Message: `build: add Ktor Client and kotlinx.serialization dependencies`
  - Files: `gradle/libs.versions.toml`, `app/build.gradle.kts`

- [x] 2. **创建 GptTranslator（OpenAI 兼容翻译器）**

  **What to do**:
  在 `translation/translator/GptTranslator.kt` 创建：

  ```kotlin
  class GptTranslator(private val httpClient: HttpClient) : Translator {
      override val name = "GPT Compatible"
      override val isReady get() = true

      override suspend fun translate(
          texts: List<String>,
          fromLanguage: String,
          toLanguage: String,
          config: TranslatorConfig,
      ): List<String> {
          // 1. 构建请求体 (OpenAI Chat Completions API 格式)
          //    - model: config.model ?: "gpt-4o-mini"
          //    - messages: [{role: "system", content: 翻译提示}, {role: "user", content: 拼接texts}]
          //    - 系统提示要求逐行翻译，保持行数一致
          //
          // 2. 发送 POST 到 ${config.apiBase ?: "https://api.openai.com/v1"}/chat/completions
          //    - Header: Authorization: Bearer ${config.apiKey}
          //
          // 3. 解析响应 JSON → 提取翻译文本行
          //    - 按行分割、去序号标记（<|1|>等）
          //    - 确保输出行数与输入一致
          //
          // 4. 错误处理：API 错误 → 返回原文
      }
  }
  ```

  **具体实现要点**:
  - 用 `@Serializable` 定义 `ChatCompletionRequest` 和 `ChatCompletionResponse` 数据类
  - 系统提示模板（参考 Python 的 `chat_system_template`）：
    ```
    "You are a professional manga translator. Translate the following text lines from {from} to {to}. 
     Preserve line count exactly. Return only the translations, one per line, no explanations."
    ```
  - Ktor 请求示例：
    ```kotlin
    val response = httpClient.post(endpoint) {
        header("Authorization", "Bearer ${config.apiKey}")
        contentType(ContentType.Application.Json)
        setBody(requestBody)
    }
    ```
  - 语言代码映射：内部 `"CHS"` → `"Simplified Chinese"`，`"JPN"` → `"Japanese"` 等
  - `supportedSourceLanguages`：返回所有 VALID_LANGUAGES 的键
  - `supportsLanguagePair`：API 翻译器始终返回 true（由 API 自行判断）

  **Must NOT do**:
  - 不实现 JSON mode（结构化请求/响应）
  - 不实现流式响应（streaming）
  - 不实现上下文翻译（前页翻译作为上下文）
  - 不添加有状态缓存

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: `[]`

  **Parallelization**:
  - **Can Run In Parallel**: NO（依赖 Task 1 的 Ktor 依赖）
  - **Parallel Group**: Wave 2
  - **Blocks**: Task 4（管线集成）
  - **Blocked By**: Task 1

  **References**:
  - `translation/api/Translator.kt` — 实现的接口
  - `translation/data/config/TranslatorConfig.kt` — 配置字段（apiKey, apiBase, model）
  - `translation/data/config/TranslatorType.kt` — GPT_COMPATIBLE 枚举值
  - `translation/stub/NoOpTranslator.kt` — 参考现有桩实现的模式
  - 官方文档: `https://platform.openai.com/docs/api-reference/chat/create` — Chat Completions API 格式

  **Acceptance Criteria**:
  - [ ] GptTranslator 实现 Translator 接口
  - [ ] 翻译请求格式符合 OpenAI Chat Completions API
  - [ ] 语言代码映射正确（CHS → Simplified Chinese 等）
  - [ ] API 错误时返回原文（优雅降级）

  **QA Scenarios**:
  ```
  Scenario: 正常翻译请求
    Tool: JUnit 4 单元测试（mock Ktor client）
    Preconditions: mock HttpClient 返回合法 JSON 响应
    Steps:
      1. 创建 GptTranslator(mockClient)
      2. 调用 translate(["こんにちは", "世界"], "auto", "CHS", config)
      3. 验证返回 List<String> size == 2
      4. 验证每个字段非空
    Expected Result: 返回翻译后文本，行数与输入一致
    Evidence: .sisyphus/evidence/task-2-translate-success.txt

  Scenario: API 返回错误
    Tool: JUnit 4 单元测试
    Preconditions: mock HttpClient 返回 401 Unauthorized
    Steps:
      1. 创建 GptTranslator(mockClient)
      2. 调用 translate(...) 当 API key 无效
      3. 验证不抛异常，返回原文列表
    Expected Result: 返回原始输入文本（优雅降级）
    Evidence: .sisyphus/evidence/task-2-translate-error.txt
  ```

  **Commit**: YES
  - Message: `feat(translator): add OpenAI-compatible GPT translator`
  - Files: `translation/translator/GptTranslator.kt`, `translation/translator/ChatCompletionDtos.kt`（如果有独立的 DTO 文件）

- [x] 3. **实现词典加载和应用系统**


- [x] 4. **实现翻译后校验（TranslationValidator）**

  **What to do**:
  创建 `translation/translator/TranslationValidator.kt`：

  ```kotlin
  object TranslationValidator {
      // 检测连续重复文本（幻觉检测）
      fun hasRepetition(text: String, threshold: Int = 20): Boolean
      // 检测目标语言文本比例是否过低
      fun isTargetLanguageRatio(text: String, targetLang: String, threshold: Float = 0.5f): Boolean
      // 综合校验
      fun validate(original: String, translation: String, targetLang: String): Boolean
  }
  ```

  **实现要点**:
  - `hasRepetition`: 检查是否存在超过 `threshold` 个连续相同字符（Python 版用 `repeating_sequence`）
  - `isTargetLanguageRatio`: 检测翻译结果中目标语言字符的比例（CJK 范围、Latin 范围等）
  - `validate`: 综合判断（非空、非原文、无重复、有目标语言字符）
  - 参考 Python 的可复用函数：`utils/generic.py:repeating_sequence`, `utils/generic2.py:is_valuable_text`

  **Must NOT do**:
  - 不实现 NLP 模型级别的质量评估（仅规则检查）
  - 不实现自动重试逻辑（后续加入）

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: `[]`

  **References**:
  - `utils/generic.py:repeating_sequence()` — Python 版重复检测逻辑
  - `utils/generic2.py:is_valuable_text()` — Python 版有价值文本判断
  - `translators/common.py:193-220` — `_is_translation_invalid` 参考

  **Acceptance Criteria**:
  - [ ] `hasRepetition("aaaaa...")` → true（连续 20+ 相同字符）
  - [ ] `hasRepetition("hello world")` → false
  - [ ] `validate("こんにちは", "Hello", "ENG")` → true（正常翻译）
  - [ ] `validate("こんにちは", "こんにちは", "ENG")` → false（原文未翻译）

  **QA Scenarios**:
  ```
  Scenario: 幻觉检测 — 模型输出大量重复文本
    Tool: JUnit 4 单元测试
    Preconditions: 输入 "AAAA..." × 30
    Steps:
      1. hasRepetition(repeatedText, threshold=20)
      2. 验证返回 true
    Expected Result: 正确识别幻觉输出
    Evidence: .sisyphus/evidence/task-4-repetition.txt

  Scenario: 正常翻译通过验证
    Tool: JUnit 4 单元测试
    Steps:
      1. validate("こんにちは", "Hello", "ENG")
      2. 验证返回 true
    Expected Result: 正常翻译通过所有检查
  ```

  **Commit**: YES
  - Message: `feat(validator): add post-translation validation (repetition + language check)`
  - Files: `translation/translator/TranslationValidator.kt`

- [x] 5. **更新 TranslationPipeline 集成词典和校验**

  **What to do**:
  修改 `translation/pipeline/TranslationPipeline.kt`：

  1. **Step 3 之后（合并后、翻译前）**：调用 `applyPreDictionary`
     ```kotlin
     // 在 Line 55 后插入
     ctx.textRegions = applyPreDictionary(ctx.textRegions, config)
     ```

  2. **Step 4 之后（翻译后）**：调用 `applyPostDictionary` → `filterInvalidTranslations`
     ```kotlin
     // 替换 Line 63-65 为：
     ctx.textRegions = ctx.textRegions.zip(translations) { region, translation ->
         region.copy(translation = translation)
     }.toMutableList()
     ctx.textRegions = applyPostDictionary(ctx.textRegions, config)
     ctx.textRegions = filterInvalidTranslations(ctx.textRegions, config.translator.targetLanguage)
     ```

  3. 实现 `applyPreDictionary` 方法：
     ```kotlin
     private fun applyPreDictionary(regions: List<TextBlock>, config: TranslationConfig): List<TextBlock> {
         val dict = config.preDictPath?.let { DictionaryLoader.load(it) } ?: return regions
         return regions.map { region -> region.copy(text = DictionaryLoader.apply(region.text, dict)) }
     }
     ```

  4. 实现 `applyPostDictionary` 方法：
     ```kotlin
     private fun applyPostDictionary(regions: List<TextBlock>, config: TranslationConfig): List<TextBlock> {
         val dict = config.postDictPath?.let { DictionaryLoader.load(it) } ?: return regions
         return regions.map { region -> region.copy(translation = DictionaryLoader.apply(region.translation, dict)) }
     }
     ```

  5. 实现 `filterInvalidTranslations` 方法：
     ```kotlin
     private fun filterInvalidTranslations(regions: List<TextBlock>, targetLang: String): List<TextBlock> {
         return regions.filter { region ->
             TranslationValidator.validate(region.text, region.translation, targetLang)
         }
     }
     ```

  **Must NOT do**:
  - 不修改检测/OCR/合并/修复/渲染步骤
  - 不改变方法的 public API

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: `[]`

  **Reference**:
  - `translation/pipeline/TranslationPipeline.kt:97-101` — 当前空桩方法的位置
  - `translation/pipeline/TranslationPipeline.kt:52-65` — 需要插入的位置（Step 3 后和 Step 4 后）

  **Acceptance Criteria**:
  - [ ] 管线调用 `applyPreDictionary`（在翻译前）
  - [ ] 管线调用 `applyPostDictionary`（在翻译后）
  - [ ] 管线调用 `filterInvalidTranslations`（在翻译后、后续步骤前）
  - [ ] 无词典文件时（路径为 null）不修改文本（直接返回）

  **QA**:
  ```
  Scenario: 管线使用前词典替换
    Tool: JUnit 4 集成测试
    Preconditions: 创建 TranslationPipeline，传入 mock 词典路径
    Steps:
      1. 运行 pipeline.translate(bitmap)
      2. 验证 applyPreDictionary 被调用且对 text 生效
    Expected Result: 翻译前的 text 被替换，翻译输入是替换后的文本
    Evidence: .sisyphus/evidence/task-5-pipeline-dict.txt
  ```

  **Commit**: YES
  - Message: `feat(pipeline): integrate pre/post-dictionary and translation validation`
  - Files: `translation/pipeline/TranslationPipeline.kt`

- [x] 6. **更新 Koin DI 模块（TranslationModule）**

  **What to do**:
  修改 `translation/di/TranslationModule.kt`：

  1. 替换 `single<Translator> { NoOpTranslator() }` 为条件绑定：
     ```kotlin
     single<Translator> {
         val config: TranslationConfig = get()
         when (config.translator.translator) {
             TranslatorType.GPT_COMPATIBLE -> GptTranslator(get())
             TranslatorType.NONE -> NoOpTranslator()
             TranslatorType.ORIGINAL -> OriginalTranslator()
             // DEEPL, BAIDU, YOUDAO 暂时保持 NoOp
             else -> NoOpTranslator()
         }
     }
     ```

  2. 添加 `single { HttpClient(CIO) { install(ContentNegotiation) { json() } } }` — 创建 Ktor 客户端单例

  3. 确保 `TranslationPipeline` 仍然正确注入所有依赖

  **Must NOT do**:
  - 不修改其他模块的 DI 绑定（detector, recognizer 等保持不变）
  - 不引入 Hilt 或替换 Koin

  **Reference**:
  - `translation/di/TranslationModule.kt` — 当前 DI 配置

  **Acceptance Criteria**:
  - [ ] `get<Translator>()` 在 `GPT_COMPATIBLE` 配置下返回 `GptTranslator`
  - [ ] `get<Translator>()` 在 `NONE` 配置下返回 `NoOpTranslator`
  - [ ] HttpClient 单例正确创建

  **Commit**: YES（与 Task 2 合并提交或单独提交）
  - Message: `feat(di): wire GptTranslator and Ktor HttpClient in Koin module`
  - Files: `translation/di/TranslationModule.kt`

- [x] 7. **更新 AppPreferences 和 PreferencesRepository**

  **What to do**:
  1. **修改 `data/preferences/AppPreferences.kt`**：
     - 将 `translator: String` → `translatorType: String`（存储枚举名 `GPT_COMPATIBLE` 等）
     - 将 `textDetector: String` → `detectorType: String`（`CTD` 等）
     - 将 `ocrEngine: String` → `ocrEngineType: String`（`MODEL_48PX` 等）
     - 将 `imageRepair: String` → `inpainterType: String`（`LAMA_LARGE` 等）
     - 添加：`apiKey: String?`, `apiBase: String?`, `modelName: String?`, `targetLanguage: String`
     - 更新默认值匹配新 enum

  2. **更新 `PreferencesRepository.kt`**：
     - 添加新字段的读写方法（`getApiKey()`, `setApiKey()`, 等）
     - `getPreferences()` 返回更新后的 `AppPreferences`

  3. **更新 `translation/config/TranslationConfigMapper.kt`**（如果存在）：
     - 添加 `AppPreferences → TranslationConfig` 的映射函数
     - 或改为 `TranslationConfig` 直接从 DataStore 序列化

  **Must NOT do**:
  - 不修改 UI 层的 SettingsTranslationScreen（只改数据流）
  - 不引入加密存储（API key 先明文存 DataStore）

  **Reference**:
  - `data/preferences/AppPreferences.kt` — 当前偏好类
  - `data/preferences/PreferencesRepository.kt` — 当前存储仓库
  - `translation/data/config/TranslatorType.kt` — 新枚举值
  - `translation/config/TranslationConfigMapper.kt` — 检查是否已有映射器

  **Acceptance Criteria**:
  - [ ] `AppPreferences` 字段与新 config 类型对应
  - [ ] `PreferencesRepository` 支持新字段的持久化
  - [ ] 默认值正确（GPT_COMPATIBLE, CHS, etc.）

  **Commit**: YES
  - Message: `refactor(prefs): update AppPreferences to match TranslationConfig types`
  - Files: `data/preferences/AppPreferences.kt`, `data/preferences/PreferencesRepository.kt`

- [x] 8. **添加 OriginalTranslator 桩实现**

  **What to do**:
  创建 `translation/stub/OriginalTranslator.kt`（DI 中的 `TranslatorType.ORIGINAL` 分支需要）：

  ```kotlin
  class OriginalTranslator : Translator {
      override val name = "Original (Keep)"
      override val isReady get() = true
      override suspend fun translate(texts, from, to, config) = texts  // 返回原文
      override fun supportsLanguagePair(from: String, to: String) = true
  }
  ```

  同时在 `translation/stub/` 目录下确认所有 NoOp 桩文件存在且接口一致。

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: `[]`

  **Commit**: YES（与 Task 2 合并提交）

---

## Final Verification Wave

- [x] F1. **编译检查** — `quick`
- [x] F3. **单元测试通过** — `unspecified-low`
- [x] F4. **接口一致性检查** — `unspecified-low`
  确认 `Translator` 接口未被修改（现有 `TranslationPipeline` 调用签名不变），`TranslationPipeline` 的修改不破坏现有流程。

---

## Commit Strategy

- **Commit 1**: `build: add Ktor Client and kotlinx.serialization` — `libs.versions.toml`, `app/build.gradle.kts`
- **Commit 2**: `feat(translator): add GPT translator, validator, and original stub` — `translation/translator/GptTranslator.kt`, `TranslationValidator.kt`, `translation/stub/OriginalTranslator.kt`
- **Commit 3**: `feat(dict): add DictionaryLoader` — `translation/dict/DictionaryLoader.kt`
- **Commit 4**: `feat(pipeline): integrate dict + validation into TranslationPipeline` — `translation/pipeline/TranslationPipeline.kt`
- **Commit 5**: `feat(di): wire translator + preferences update` — `translation/di/TranslationModule.kt`, `data/preferences/AppPreferences.kt`, `PreferencesRepository.kt`

---

## Success Criteria

### Verification Commands
```bash
# 编译检查
./gradlew assembleDebug

# 单元测试
./gradlew test

# 关键测试场景
# GptTranslator: mock HTTP 响应验证 JSON 解析
# DictionaryLoader: 加载示例文件验证替换
# TranslationValidator: 重复检测通过/正常文本通过
```

### Final Checklist
- [ ] `GptTranslator` 实现 `Translator` 接口并通过 mock 测试
- [ ] 词典系统能正确解析 Python 兼容的纯文本文件
- [ ] `TranslationValidator` 能检测重复输出和未翻译文本
- [ ] 管线集成了前词典 → 翻译 → 后词典 → 校验流水线
- [ ] Koin DI 根据 `TranslatorType` 条件注入正确实现
- [ ] AppPreferences 字段映射新类型，编译通过
- [ ] 整体 `assembleDebug` 编译通过

  **What to do**:
  创建 `translation/dict/DictionaryLoader.kt`：

  ```kotlin
  object DictionaryLoader {
      data class DictEntry(val pattern: Regex, val replacement: String, val lineNumber: Int)

      fun load(path: String): List<DictEntry>
      // 解析纯文本文件，格式与 Python 版兼容：
      // 每行: "pattern replacement"
      // 仅 pattern = 删除匹配内容（replacement 为空）
      // 忽略 # 和 // 开头的注释行
      // 忽略空行

      fun apply(text: String, dictionary: List<DictEntry>): String
      // 顺序应用每条规则，返回替换后的文本
  }
  ```

  **实现要点**:
  - 读取文件（从文件路径或 assets）
  - 解析每行为 `pattern replacement`（按空格分割，最多两段）
  - pattern 编译为 `Regex`（Python 的 `re.compile` → Kotlin `Regex`）
  - `apply()` 方法逐个 pattern 替换
  - 可选：添加替换日志（Python 版会 log 每条替换）

  **Must NOT do**:
  - 不实现 Python 的 `galtransl_dict` 或 `sakura_dict` 格式（不需要）
  - 不实现热加载

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: `[]`

  **Parallelization**:
  - **Can Run In Parallel**: YES（与 Task 2 同时进行）
  - **Parallel Group**: Wave 2
  - **Blocked By**: Task 1

  **References**:
  - Python 源码: `manga_translator/manga_translator.py:63-93` — `load_dictionary()` 和 `apply_dictionary()` 函数
  - `translation/data/config/TranslationConfig.kt:12-13` — `preDictPath` 和 `postDictPath` 字段
  - `translation/ocr/OcrDictionary.kt` — 现有资源加载模式参考（`object` 单例 + `load(context)`）

  **Acceptance Criteria**:
  - [ ] 解析示例词典文件正确（含注释、空行、pattern-only 行）
  - [ ] `apply("hello world", [("hello", "你好")])` → `"你好 world"`
  - [ ] 日志输出每条替换（参考 Python 版的 log 行为）

  **QA Scenarios**:
  ```
  Scenario: 加载并应用词典文件
    Tool: JUnit 4 单元测试
    Preconditions: 创建临时词典文件内容:
      "hello 你好
       world 世界
       # 这是注释
       
       badtext"
    Steps:
      1. DictionaryLoader.load(tempFile.path)
      2. 验证 entries.size == 3
      3. 验证 entries[0] = ("hello", "你好")
      4. 验证 entries[2] = ("badtext", "")  # 仅 pattern，删除匹配
      5. apply("hello world", entries) → "你好 世界"
      6. apply("this is badtext content", entries) → "this is  content"
    Expected Result: 3 条规则，替换正确
    Evidence: .sisyphus/evidence/task-3-dict-output.txt
  ```

  **Commit**: YES
  - Message: `feat(dict): add plain-text dictionary loader and applier`
  - Files: `translation/dict/DictionaryLoader.kt`

