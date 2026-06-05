## ocr_ar_48px 模型 ONNX 导出报告

### 项目背景

将漫画翻译 Android 应用中使用的 `ocr_ar_48px.ckpt` PyTorch 模型完全转换为功能等价的 ONNX 模型。该模型为自回归 OCR 系统，包含 ConvNeXt 骨干网络 + XPOS 位置编码的 Transformer 编码器/解码器架构。

### 导出结果

| 模型文件 | 大小 | 说明 |
|---------|------|------|
| `ocr_ar_48px_encoder.onnx` | 1.2 MB | 图像特征提取（ConvNeXt + 4层 XPOS 编码器） |
| `ocr_ar_48px_decoder.onnx` | 98.7 MB | 单步解码（5层 XPOS 解码器 + 预测头） |

总计 **99.9 MB**，相比原始 checkpoint（195 MB）有所减小。

### 文件位置

模型和导出脚本均已放置在项目目录中：

```
E:\yhz\Projects\manga-image-translator-app\
├── models\
│   ├── ocr_ar_48px_encoder.onnx    ← 编码器 ONNX
│   └── ocr_ar_48px_decoder.onnx    ← 解码器 ONNX
└── scripts\
    └── export_ocr_ar_48px_onnx.py  ← 导出脚本（含完整模型定义）
```

### 使用方式

**1. 编码器**（每张图像运行一次）

输入：
- `img` — float32, shape `[N, 3, 48, W]`，归一化后的图像（像素值除以 127.5 再减 1）
- `img_widths` — int64, shape `[N]`，每张图像的实际宽度（像素）

输出：
- `memory` — float32, shape `[N, W', 320]`，编码后的特征序列
- `input_mask` — bool, shape `[N, W']`，填充位置的掩码（True 表示无效）

**2. 解码器**（逐步调用，每次生成一个字符）

输入：
- `token_ids` — int64, shape `[N]`，当前 token ID（首次为 START=1）
- `step` — int64, 标量，当前步数（从 0 开始）
- `memory` — 编码器输出的特征序列
- `memory_mask` — 编码器输出的掩码
- `cache_flat` — float32, shape `[N*6, 255, 320]`，KV 缓存（首次全零）

输出：
- `logits` — float32, shape `[N, 46272]`，字符概率分布
- `fg_colors` / `bg_colors` — float32, shape `[N, 3]`，前景/背景颜色（RGB, 0-1）
- `fg_indicators` / `bg_indicators` — float32, shape `[N, 2]`，前景/背景指示器
- `cache_flat_out` — 更新后的 KV 缓存

**贪心解码示例（伪代码）：**

```
cache = zeros(N*6, 255, 320)
token = START  // 1

for step in 0, 1, 2, ...:
    logits, fg, bg, fg_ind, bg_ind, cache = decoder(token, step, memory, mask, cache)
    token = argmax(logits)
    if token == END:  // 2
        break
    // 记录 token, fg, bg, fg_ind, bg_ind
```

### 核心技术方案

导出过程中遇到的主要障碍是 TorchScript tracing（`dynamo=False`）会将 XPOS 模块内部的 `torch.arange` 产生的张量形状固化到 ONNX 图中，导致解码器在不同 step 下运行时形状不匹配。

解决方案是 **PrecomputedXPOS**：将 XPOS 中所有依赖 step 的动态计算（位置索引生成、幂运算）预计算为固定大小的查找表，运行时通过 `index_select` 查表获取对应的 scale/sin/cos 值。查找表覆盖 MAX_SEQ=255 范围内所有可能的位置组合（scale 表 509 行，sin/cos 表 255 行）。

同时将注意力掩码改为 additive masking（softmax 前对无效位置加 -inf），与原始模型行为严格一致。

### 验证结果

30 步强制解码测试中，PyTorch 参考实现与 ONNX Runtime 推理结果完全一致：

- 每步 logits 最大差异 < 0.00003
- Top-3 预测字符每一步都完全匹配
- 贪心解码序列完全一致
