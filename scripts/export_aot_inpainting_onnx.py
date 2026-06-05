"""
Export AOT-GAN inpainting model (inpainting.ckpt) to ONNX for Android.

Model: AOTGenerator from manga_translator.inpainting.inpainting_aot
  - Architecture: encoder (head) → 10× AOTBlock (body) → decoder (tail)
  - Custom layers: ScaledWSConv2d, GatedWSConvPadded, AOTBlock with gated attention
  - Weight Standardization is pre-computed (frozen) before export for:
    * Fewer ONNX nodes (~668 → ~400)
    * Faster inference (no runtime ReduceMean/Sqrt/Div per conv)
    * Better ONNX Runtime compatibility

Input:  [N, 4, H, W] float32 — channels: [mask, R, G, B]
        mask: binary (0 or 1), RGB: normalized to [-1, 1] (masked pixels = 0)
Output: [N, 3, H_out, W_out] float32 — inpainted RGB, clipped to [-1, 1]
        H_out = 4*(H//4), W_out = 4*(W//4) due to 2× down + 2× up sampling

Usage:
  cd <python-web project root>
  python ../scripts/export_aot_inpainting_onnx.py [output_path]
"""

import os
import sys
from typing import List, Optional

# ── Path setup ────────────────────────────────────────────────────
_script_dir = os.path.dirname(os.path.abspath(__file__))
_project_root = os.path.dirname(_script_dir)  # manga-image-translator-app root

_candidates = [
    os.path.join(_project_root, 'python-web'),
    r'D:\manga-image-translator\manga-image-translator',
]
_python_web = None
for _c in _candidates:
    _abs = os.path.abspath(_c)
    if os.path.isdir(os.path.join(_abs, 'manga_translator')):
        _python_web = _abs
        break

if _python_web is None:
    print("ERROR: Cannot find python-web project.")
    sys.exit(1)

CKPT_PATH = os.path.join(_python_web, 'models', 'inpainting', 'inpainting.ckpt')
OLD_ONNX_PATH = os.path.join(_python_web, 'models', 'inpainting', 'aot_inpainting.onnx')

if not os.path.exists(CKPT_PATH):
    print(f"ERROR: Checkpoint not found: {CKPT_PATH}")
    sys.exit(1)

print(f"Python-web root: {_python_web}")
print(f"Checkpoint:      {CKPT_PATH}")

import torch
import torch.nn as nn
import torch.nn.functional as F
import numpy as np

# ============================================================
# Section 1: Model Definitions (inlined from inpainting_aot.py)
# ============================================================

def relu_nf(x):
    return F.relu(x) * 1.7139588594436646

def gelu_nf(x):
    return F.gelu(x) * 1.7015043497085571

def silu_nf(x):
    return F.silu(x) * 1.7881293296813965


class LambdaLayer(nn.Module):
    def __init__(self, f):
        super(LambdaLayer, self).__init__()
        self.f = f

    def forward(self, x):
        return self.f(x)


class ScaledWSConv2d(nn.Conv2d):
    """2D Conv layer with Scaled Weight Standardization."""
    def __init__(self, in_channels, out_channels, kernel_size,
                 stride=1, padding=0, dilation=1, groups=1, bias=True,
                 gain=True, eps=1e-4):
        nn.Conv2d.__init__(self, in_channels, out_channels,
                           kernel_size, stride, padding, dilation, groups, bias)
        if gain:
            self.gain = nn.Parameter(torch.ones(self.out_channels, 1, 1, 1))
        else:
            self.gain = None
        self.eps = eps

    def get_weight(self):
        fan_in = np.prod(self.weight.shape[1:])
        var, mean = torch.var_mean(self.weight, dim=(1, 2, 3), keepdims=True)
        scale = torch.rsqrt(torch.max(
            var * fan_in, torch.tensor(self.eps).to(var.device))
        ) * self.gain.view_as(var).to(var.device)
        shift = mean * scale
        return self.weight * scale - shift

    def forward(self, x):
        return F.conv2d(x, self.get_weight(), self.bias,
                        self.stride, self.padding, self.dilation, self.groups)


class ScaledWSTransposeConv2d(nn.ConvTranspose2d):
    """2D Transpose Conv layer with Scaled Weight Standardization."""
    def __init__(self, in_channels: int, out_channels: int, kernel_size,
                 stride=1, padding=0, output_padding=0, groups: int = 1,
                 bias: bool = True, dilation: int = 1, gain=True, eps=1e-4):
        nn.ConvTranspose2d.__init__(self, in_channels, out_channels,
                                    kernel_size, stride, padding, output_padding,
                                    groups, bias, dilation, 'zeros')
        if gain:
            self.gain = nn.Parameter(torch.ones(self.in_channels, 1, 1, 1))
        else:
            self.gain = None
        self.eps = eps

    def get_weight(self):
        fan_in = np.prod(self.weight.shape[1:])
        var, mean = torch.var_mean(self.weight, dim=(1, 2, 3), keepdims=True)
        scale = torch.rsqrt(torch.max(
            var * fan_in, torch.tensor(self.eps).to(var.device))
        ) * self.gain.view_as(var).to(var.device)
        shift = mean * scale
        return self.weight * scale - shift

    def forward(self, x, output_size: Optional[List[int]] = None):
        output_padding = self._output_padding(
            input, output_size, self.stride, self.padding,
            self.kernel_size, self.dilation)
        return F.conv_transpose2d(x, self.get_weight(), self.bias,
                                  self.stride, self.padding,
                                  output_padding, self.groups, self.dilation)


class GatedWSConvPadded(nn.Module):
    def __init__(self, in_ch, out_ch, ks, stride=1, dilation=1):
        super(GatedWSConvPadded, self).__init__()
        self.in_ch = in_ch
        self.out_ch = out_ch
        self.padding = nn.ReflectionPad2d(((ks - 1) * dilation) // 2)
        self.conv = ScaledWSConv2d(in_ch, out_ch, kernel_size=ks,
                                   stride=stride, dilation=dilation)
        self.conv_gate = ScaledWSConv2d(in_ch, out_ch, kernel_size=ks,
                                        stride=stride, dilation=dilation)

    def forward(self, x):
        x = self.padding(x)
        signal = self.conv(x)
        gate = torch.sigmoid(self.conv_gate(x))
        return signal * gate * 1.8


class GatedWSTransposeConvPadded(nn.Module):
    def __init__(self, in_ch, out_ch, ks, stride=1):
        super(GatedWSTransposeConvPadded, self).__init__()
        self.in_ch = in_ch
        self.out_ch = out_ch
        self.conv = ScaledWSTransposeConv2d(in_ch, out_ch, kernel_size=ks,
                                            stride=stride,
                                            padding=(ks - 1) // 2)
        self.conv_gate = ScaledWSTransposeConv2d(in_ch, out_ch, kernel_size=ks,
                                                 stride=stride,
                                                 padding=(ks - 1) // 2)

    def forward(self, x):
        signal = self.conv(x)
        gate = torch.sigmoid(self.conv_gate(x))
        return signal * gate * 1.8


def my_layer_norm(feat):
    mean = feat.mean((2, 3), keepdim=True)
    std = feat.std((2, 3), keepdim=True) + 1e-9
    feat = 2 * (feat - mean) / std - 1
    feat = 5 * feat
    return feat


class AOTBlock(nn.Module):
    def __init__(self, dim, rates=[2, 4, 8, 16]):
        super(AOTBlock, self).__init__()
        self.rates = rates
        for i, rate in enumerate(rates):
            self.__setattr__(
                'block{}'.format(str(i).zfill(2)),
                nn.Sequential(
                    nn.ReflectionPad2d(rate),
                    nn.Conv2d(dim, dim // 4, 3, padding=0, dilation=rate),
                    nn.ReLU(True)))
        self.fuse = nn.Sequential(
            nn.ReflectionPad2d(1),
            nn.Conv2d(dim, dim, 3, padding=0, dilation=1))
        self.gate = nn.Sequential(
            nn.ReflectionPad2d(1),
            nn.Conv2d(dim, dim, 3, padding=0, dilation=1))

    def forward(self, x):
        out = [self.__getattr__(f'block{str(i).zfill(2)}')(x)
               for i in range(len(self.rates))]
        out = torch.cat(out, 1)
        out = self.fuse(out)
        mask = my_layer_norm(self.gate(x))
        mask = torch.sigmoid(mask)
        return x * (1 - mask) + out * mask


class AOTGenerator(nn.Module):
    def __init__(self, in_ch=4, out_ch=3, ch=32, alpha=0.0):
        super(AOTGenerator, self).__init__()
        self.head = nn.Sequential(
            GatedWSConvPadded(in_ch, ch, 3, stride=1),
            LambdaLayer(relu_nf),
            GatedWSConvPadded(ch, ch * 2, 4, stride=2),
            LambdaLayer(relu_nf),
            GatedWSConvPadded(ch * 2, ch * 4, 4, stride=2),
        )
        self.body_conv = nn.Sequential(*[AOTBlock(ch * 4) for _ in range(10)])
        self.tail = nn.Sequential(
            GatedWSConvPadded(ch * 4, ch * 4, 3, 1),
            LambdaLayer(relu_nf),
            GatedWSConvPadded(ch * 4, ch * 4, 3, 1),
            LambdaLayer(relu_nf),
            GatedWSTransposeConvPadded(ch * 4, ch * 2, 4, 2),
            LambdaLayer(relu_nf),
            GatedWSTransposeConvPadded(ch * 2, ch, 4, 2),
            LambdaLayer(relu_nf),
            GatedWSConvPadded(ch, out_ch, 3, stride=1),
        )

    def forward(self, img, mask):
        x = torch.cat([mask, img], dim=1)
        x = self.head(x)
        conv = self.body_conv(x)
        x = self.tail(conv)
        if self.training:
            return x
        else:
            return torch.clip(x, -1, 1)


# ============================================================
# Section 2: Weight Standardization Freezing
# ============================================================

def _replace_module(parent: nn.Module, name: str, new_module: nn.Module):
    """Replace a child module by attribute name."""
    setattr(parent, name, new_module)


def freeze_ws_conv2d(module: ScaledWSConv2d) -> nn.Conv2d:
    """Freeze ScaledWSConv2d → standard nn.Conv2d with pre-computed weights."""
    with torch.no_grad():
        frozen_weight = module.get_weight().detach().clone()

    new_conv = nn.Conv2d(
        module.in_channels, module.out_channels,
        module.kernel_size, stride=module.stride,
        padding=module.padding, dilation=module.dilation,
        groups=module.groups, bias=module.bias is not None,
    )
    new_conv.weight = nn.Parameter(frozen_weight)
    if module.bias is not None:
        new_conv.bias = module.bias
    return new_conv


def freeze_ws_transpose_conv2d(module: ScaledWSTransposeConv2d) -> nn.ConvTranspose2d:
    """Freeze ScaledWSTransposeConv2d → standard nn.ConvTranspose2d."""
    with torch.no_grad():
        frozen_weight = module.get_weight().detach().clone()

    new_conv = nn.ConvTranspose2d(
        module.in_channels, module.out_channels,
        module.kernel_size, stride=module.stride,
        padding=module.padding, output_padding=module.output_padding,
        groups=module.groups, bias=module.bias is not None,
        dilation=module.dilation,
    )
    new_conv.weight = nn.Parameter(frozen_weight)
    if module.bias is not None:
        new_conv.bias = module.bias
    return new_conv


def freeze_weight_standardization(model: nn.Module) -> nn.Module:
    """
    Recursively replace all ScaledWSConv2d / ScaledWSTransposeConv2d
    with frozen standard conv layers.
    """
    frozen_count = 0

    for name, child in list(model.named_children()):
        if isinstance(child, ScaledWSConv2d):
            _replace_module(model, name, freeze_ws_conv2d(child))
            frozen_count += 1
        elif isinstance(child, ScaledWSTransposeConv2d):
            _replace_module(model, name, freeze_ws_transpose_conv2d(child))
            frozen_count += 1
        else:
            # Recurse into submodules
            sub_count = freeze_weight_standardization(child)
            frozen_count += sub_count

    return frozen_count


# ============================================================
# Section 3: ONNX Export Wrapper
# ============================================================

class AOTInpaintingWrapper(nn.Module):
    """
    Single-input ONNX wrapper for AOTGenerator.

    Input:  x [N, 4, H, W] — channel order: [mask, R, G, B]
    Output: [N, 3, H_out, W_out] — clipped to [-1, 1]
    """
    def __init__(self, model: AOTGenerator):
        super().__init__()
        self.model = model

    def forward(self, x):
        mask = x[:, 0:1, :, :]   # [N, 1, H, W]
        img = x[:, 1:4, :, :]    # [N, 3, H, W]
        return self.model(img, mask)


# ============================================================
# Section 4: Main — Load, Freeze, Export, Verify
# ============================================================

def main():
    # ── 4a. Load model ────────────────────────────────────────────
    print("\n" + "=" * 60)
    print("Loading AOT-GAN model ...")
    model = AOTGenerator()
    sd = torch.load(CKPT_PATH, map_location='cpu', weights_only=True)
    sd = sd['model'] if 'model' in sd else sd
    model.load_state_dict(sd)
    model.eval()
    total_params = sum(p.numel() for p in model.parameters())
    print(f"  Parameters: {total_params:,}")

    # ── 4b. Freeze weight standardization ─────────────────────────
    print("\nFreezing weight standardization ...")
    frozen = freeze_weight_standardization(model)
    print(f"  Frozen {frozen} WS conv layers")

    remaining_ws = sum(
        1 for m in model.modules()
        if isinstance(m, (ScaledWSConv2d, ScaledWSTransposeConv2d))
    )
    assert remaining_ws == 0, f"Missed {remaining_ws} WS layers!"
    print("  ✓ All WS layers replaced")

    # ── 4c. Create wrapper ────────────────────────────────────────
    wrapper = AOTInpaintingWrapper(model)
    wrapper.eval()

    # Quick smoke test with PyTorch
    with torch.no_grad():
        dummy = torch.randn(1, 4, 256, 256)
        pt_output = wrapper(dummy)
    print(f"\n  PyTorch smoke test: input {list(dummy.shape)} → output {list(pt_output.shape)}")
    print(f"  Output range: [{pt_output.min():.4f}, {pt_output.max():.4f}]")

    # ── 4d. Export to ONNX ────────────────────────────────────────
    output_path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        _project_root, 'models', 'aot_inpainting.onnx'
    )
    os.makedirs(os.path.dirname(output_path) or '.', exist_ok=True)
    tmp_path = output_path + '.tmp'

    print(f"\nExporting ONNX → {output_path} ...")
    with torch.inference_mode():
        torch.onnx.export(
            wrapper,
            (dummy,),
            tmp_path,
            input_names=['input'],
            output_names=['output'],
            dynamic_axes={
                'input':  {2: 'height', 3: 'width'},
                'output': {2: 'height', 3: 'width'},
            },
            opset_version=18,
            do_constant_folding=True,
            verbose=False,
        )

    # ── 4e. Merge external data ───────────────────────────────────
    import onnx

    print("Merging external data into single .onnx file ...")
    model_proto = onnx.load(tmp_path)
    onnx.save_model(
        model_proto, output_path,
        save_as_external_data=False,
        all_tensors_to_one_file=True,
    )

    # Cleanup temp files
    if os.path.exists(tmp_path):
        os.remove(tmp_path)
    data_file = tmp_path + '.data'
    if os.path.exists(data_file):
        os.remove(data_file)
    data_file2 = output_path + '.data'
    if os.path.exists(data_file2):
        os.remove(data_file2)

    file_size_mb = os.path.getsize(output_path) / 1024 / 1024
    print(f"  File size: {file_size_mb:.1f} MB ({os.path.getsize(output_path):,} bytes)")

    # Count ONNX nodes
    node_count = len(model_proto.graph.node)
    print(f"  ONNX nodes: {node_count}")

    # ── 4f. Verify with ONNX Runtime ──────────────────────────────
    print("\n" + "=" * 60)
    print("Verifying ONNX model ...")
    import onnxruntime as ort

    # Structural check
    onnx.checker.check_model(onnx.load(output_path))
    print("  ✓ onnx.checker passed")

    session = ort.InferenceSession(output_path)
    input_info = session.get_inputs()[0]
    output_info = session.get_outputs()[0]
    print(f"  Input:  name='{input_info.name}', shape={input_info.shape}")
    print(f"  Output: name='{output_info.name}', shape={output_info.shape}")

    # ── Layer 2: Numerical accuracy (PyTorch vs ORT) ──────────────
    # Note: PyTorch→ONNX export inherently introduces ~1e-4 to 5e-4 fp32
    # differences due to operator decomposition (especially my_layer_norm
    # which uses mean/std/clip). The old ONNX also shows 3e-4 vs PyTorch.
    # Threshold: 5e-4 max diff (generous but ensures correctness).
    THRESHOLD = 5e-4
    print(f"\nNumerical accuracy: PyTorch vs ONNX Runtime (threshold: {THRESHOLD:.1e})")
    test_sizes = [(128, 128), (256, 256), (192, 320), (100, 200)]
    all_pass = True

    for H, W in test_sizes:
        x = torch.randn(1, 4, H, W)
        with torch.no_grad():
            pt_out = wrapper(x).numpy()
        ort_out = session.run(None, {'input': x.numpy()})[0]

        max_diff = np.abs(pt_out - ort_out).max()
        mean_diff = np.abs(pt_out - ort_out).mean()
        n_large = int(np.sum(np.abs(pt_out - ort_out) > 1e-4))
        total = pt_out.size
        status = "✓" if max_diff < THRESHOLD else "✗"
        if max_diff >= THRESHOLD:
            all_pass = False
        print(f"  {status} {H}×{W}: max={max_diff:.2e}, mean={mean_diff:.2e}, px>1e-4={n_large}/{total} ({n_large/total*100:.2f}%)")

    if all_pass:
        print(f"  ✓ All sizes pass (max_diff < {THRESHOLD:.1e})")
    else:
        print(f"  ⚠ Some sizes exceeded {THRESHOLD:.1e} — check for export issues")

    # ── Layer 3: Dynamic shape (non-multiple-of-4) ────────────────
    print("\nDynamic shape test (non-4-multiple sizes):")
    # Note: minimum input is ~64×64 due to ReflectionPad2d in AOTBlock
    # (dilation=16 requires input ≥ 2*16+3 = 35 at the deepest layer,
    #  which is H//4 after 2× downsampling → H ≥ 140 worst case)
    for H, W in [(101, 203), (128, 128), (300, 180)]:
        x = np.random.randn(1, 4, H, W).astype(np.float32)
        try:
            out = session.run(None, {'input': x})[0]
            expected_H = 4 * (H // 4)
            expected_W = 4 * (W // 4)
            match = out.shape == (1, 3, expected_H, expected_W)
            status = "✓" if match else "✗"
            print(f"  {status} {H}×{W} → {out.shape} (expected (1,3,{expected_H},{expected_W}))")
        except Exception as e:
            print(f"  ✗ {H}×{W} → ERROR: {e}")

    # ── 4g. Baseline comparison with old ONNX ─────────────────────
    if os.path.exists(OLD_ONNX_PATH):
        print("\n" + "=" * 60)
        print("Baseline comparison: new ONNX vs old ONNX ...")
        old_session = ort.InferenceSession(OLD_ONNX_PATH)
        old_input_name = old_session.get_inputs()[0].name
        old_old_nodes = len(onnx.load(OLD_ONNX_PATH).graph.node)
        old_size_mb = os.path.getsize(OLD_ONNX_PATH) / 1024 / 1024

        print(f"  Old ONNX: {old_size_mb:.1f} MB, {old_old_nodes} nodes")
        print(f"  New ONNX: {file_size_mb:.1f} MB, {node_count} nodes")
        print(f"  Node reduction: {old_old_nodes - node_count} ({(1 - node_count/old_old_nodes)*100:.0f}%)")

        for H, W in [(128, 128), (256, 256)]:
            x = np.random.randn(1, 4, H, W).astype(np.float32)
            new_out = session.run(None, {'input': x})[0]
            old_out = old_session.run(None, {old_input_name: x})[0]

            # Shapes might differ slightly (old uses 4*((H//4)) expression)
            min_H = min(new_out.shape[2], old_out.shape[2])
            min_W = min(new_out.shape[3], old_out.shape[3])
            new_crop = new_out[:, :, :min_H, :min_W]
            old_crop = old_out[:, :, :min_H, :min_W]

            max_diff = np.abs(new_crop - old_crop).max()
            mean_diff = np.abs(new_crop - old_crop).mean()
            print(f"  {H}×{W}: max_diff={max_diff:.2e}, mean_diff={mean_diff:.2e}")
    else:
        print(f"\n  Old ONNX not found at {OLD_ONNX_PATH}, skipping baseline comparison")

    # ── Summary ───────────────────────────────────────────────────
    print("\n" + "=" * 60)
    print("Done!")
    print(f"  Output: {output_path}")
    print(f"  Size:   {file_size_mb:.1f} MB")
    print(f"  Nodes:  {node_count}")
    print(f"\n  Copy to Android assets:")
    print(f"    cp {output_path} app/src/main/assets/models/aot_inpainting.onnx")


if __name__ == '__main__':
    main()
