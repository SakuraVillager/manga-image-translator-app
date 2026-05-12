"""
Export CTC OCR model (ocr-ctc) to ONNX for Android.

Model: OCR class from manga_translator.ocr.model_48px_ctc
  - Backbone: ResNet_FeatureExtractor(3→320 channels), output [N, 320, 1, W']
  - Encoder: 3-layer CustomTransformerEncoder (d_model=320, nhead=8, batch_first=True)
  - char_pred_norm: LayerNorm+GELU → char_pred(320→dict_size): logits [N, T, dict_size]
  - color_pred1(320→6): colors [N, T, 6] (fg_r, fg_g, fg_b, bg_r, bg_b, bg_b)

Input:  [N, 3, 48, W] float32, normalized (x-127.5)/127.5 → [-1, 1]
Output: logits [N, T, 19264], colors [N, T, 6]

CTC decoding (greedy argmax + blank-collapse) is done in Kotlin/ONNX Runtime.

Usage:
  cd D:/manga-image-translator/manga-image-translator
  python ../manga-image-translator-app/scripts/export_ctc_onnx.py [output_path]
"""

import os
import sys

# ── Path setup: find the Python project root ──────────────────────
# The script lives in the Kotlin project's scripts/ dir.
# The Python project root is where manga_translator/ package lives.
_script_dir = os.path.dirname(os.path.abspath(__file__))
_project_root = os.path.dirname(_script_dir)  # default: Kotlin project root

# Try common locations for the Python project
_candidates = [
    os.path.join(_project_root, '..', 'manga-image-translator', 'manga-image-translator'),
    r'D:\manga-image-translator\manga-image-translator',
]
for _c in _candidates:
    _abs = os.path.abspath(_c)
    if os.path.isdir(os.path.join(_abs, 'manga_translator')):
        _project_root = _abs
        break

if not os.path.isdir(os.path.join(_project_root, 'manga_translator')):
    print("ERROR: Cannot find Python manga-image-translator project.")
    print("Tried:", [_project_root] + [os.path.abspath(c) for c in _candidates])
    print("Run this script from the Python project root, or pass the path as second arg.")
    sys.exit(1)

sys.path.insert(0, _project_root)
PROJECT_ROOT = _project_root
print(f"Project root: {PROJECT_ROOT}")

import torch
import numpy as np

# ── Download / locate checkpoint ──────────────────────────────────
MODEL_URL = "https://github.com/zyddnys/manga-image-translator/releases/download/beta-0.3/ocr-ctc.zip"
MODEL_DIR = os.path.join(PROJECT_ROOT, 'models', 'ocr')
CKPT_PATH = os.path.join(MODEL_DIR, 'ocr-ctc.ckpt')
DICT_PATH = os.path.join(MODEL_DIR, 'alphabet-all-v5.txt')

# Also check project root (the checkpoint might be there from a previous manual download)
if not os.path.exists(CKPT_PATH):
    CKPT_PATH = os.path.join(PROJECT_ROOT, 'ocr-ctc.ckpt')
if not os.path.exists(DICT_PATH):
    DICT_PATH = os.path.join(PROJECT_ROOT, 'alphabet-all-v5.txt')

if not os.path.exists(CKPT_PATH) or not os.path.exists(DICT_PATH):
    print("CTC model files not found. Downloading from GitHub...")
    import zipfile
    zip_path = os.path.join(MODEL_DIR, 'ocr-ctc.zip')
    os.makedirs(MODEL_DIR, exist_ok=True)
    torch.hub.download_url_to_file(MODEL_URL, zip_path)
    with zipfile.ZipFile(zip_path, 'r') as zf:
        zf.extractall(MODEL_DIR)
    os.remove(zip_path)
    CKPT_PATH = os.path.join(MODEL_DIR, 'ocr-ctc.ckpt')
    DICT_PATH = os.path.join(MODEL_DIR, 'alphabet-all-v5.txt')
    print("Download complete.")

# ── Load model ────────────────────────────────────────────────────
from manga_translator.ocr.model_48px_ctc import OCR

with open(DICT_PATH, 'r', encoding='utf-8') as fp:
    dictionary = [s[:-1] for s in fp.readlines()]
DICT_SIZE = len(dictionary)
print(f"Dictionary: {DICT_SIZE} chars")

model = OCR(dictionary, 768)
sd = torch.load(CKPT_PATH, map_location='cpu', weights_only=True)
sd = sd['model'] if 'model' in sd else sd

# Remove PositionalEncoding buffers — PyTorch re-creates them in __init__.
# These may have wrong shapes from a different training config.
for k in list(sd.keys()):
    if 'pe.pe' in k:
        del sd[k]
        print(f"  Removed {k}")

model.load_state_dict(sd, strict=False)
model.eval()
print(f"Model loaded. Params: {sum(p.numel() for p in model.parameters()):,}")

# ── Export to ONNX ────────────────────────────────────────────────
output_path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    PROJECT_ROOT, 'ocr_ctc_48px.onnx'
)

# Dummy input matching real inference:  [N, 3, 48, W], normalized [-1, 1].
# Use realistic pixel values (simulate a crop from [0,255] uint8).
W = 256  # representative width
dummy_uint8 = np.random.randint(0, 256, (1, 48, W, 3), dtype=np.uint8)
dummy_img = torch.from_numpy(dummy_uint8).float()
dummy_img = (dummy_img - 127.5) / 127.5          # normalize → [-1, 1]
dummy_img = dummy_img.permute(0, 3, 1, 2)        # NHWC → NCHW
dummy_img = dummy_img.contiguous()

print(f"Exporting {output_path}  (input shape: {list(dummy_img.shape)}) ...")

with torch.inference_mode():
    torch.onnx.export(
        model,
        dummy_img,
        output_path,
        input_names=['img'],
        output_names=['logits', 'colors'],
        dynamic_axes={
            'img': {0: 'batch', 3: 'width'},
            'logits': {0: 'batch', 1: 'seq_len'},
            'colors': {0: 'batch', 1: 'seq_len'},
        },
        opset_version=18,  # Required by the model; Android ONNX Runtime 1.18+ supports this
        verbose=False,
    )

# ── Merge external data into a single .onnx file ──────────────────
# PyTorch ≥2.0 exports large tensors as external .onnx.data by default.
# ONNX Runtime on Android loads from byte[] and cannot find external files.
import onnx

print("Merging external data into single .onnx file ...")
model_proto = onnx.load(output_path)
onnx.save_model(
    model_proto, output_path,
    save_as_external_data=False,
    all_tensors_to_one_file=True,
)

# Delete leftover .data file if present
data_file = output_path + '.data'
if os.path.exists(data_file):
    os.remove(data_file)
    print(f"  Removed: {data_file}")

file_size_mb = os.path.getsize(output_path) / 1024 / 1024
print(f"Final file: {file_size_mb:.1f} MB  ({os.path.getsize(output_path):,} bytes)")

# ── Verify with ONNX Runtime ──────────────────────────────────────
print("\n" + "=" * 60)
print("Verifying ONNX model ...")
import onnxruntime as ort

session = ort.InferenceSession(output_path)
print(f"  Input  names: {[i.name for i in session.get_inputs()]}")
print(f"  Output names: {[o.name for o in session.get_outputs()]}")

inputs = {'img': dummy_img.numpy()}
logits_ort, colors_ort = session.run(None, inputs)
print(f"  logits: {logits_ort.shape}")
print(f"  colors: {colors_ort.shape}")

# Compare against PyTorch
with torch.no_grad():
    logits_pt, colors_pt = model(dummy_img)

diff_logits = np.abs(logits_pt.numpy() - logits_ort).max()
diff_colors = np.abs(colors_pt.numpy() - colors_ort).max()
print(f"  logits max diff: {diff_logits:.6f}")
print(f"  colors max diff: {diff_colors:.6f}")

if diff_logits > 1e-4 or diff_colors > 1e-4:
    print("  ⚠ WARNING: Numerical difference detected — model may be incorrect!")
else:
    print("  ✓ ONNX ↔ PyTorch match confirmed")

# Quick CTC sanity check: run real inference to confirm model works
print("\nSanity check: CTC decode on realistic input ...")
dummy_uint8_2 = np.random.randint(0, 256, (2, 48, 160, 3), dtype=np.uint8)
dummy_img_2 = torch.from_numpy(dummy_uint8_2).float()
dummy_img_2 = (dummy_img_2 - 127.5) / 127.5
dummy_img_2 = dummy_img_2.permute(0, 3, 1, 2).contiguous()
widths_2 = [160, 160]
texts = model.decode(dummy_img_2, widths_2, blank=0)
for i, line in enumerate(texts):
    chars = ''.join(dictionary[chid] for chid, *_ in line)
    print(f"  sample {i}: decoded {len(line)} tokens → '{chars}'")

print("\nDone! Copy the .onnx file to:")
print(f"  {os.path.join(_script_dir, '..', 'app', 'src', 'main', 'assets', 'models', 'ocr_ctc_48px.onnx')}")
print(f"  (resolved: {os.path.abspath(os.path.join(_script_dir, '..', 'app', 'src', 'main', 'assets', 'models', 'ocr_ctc_48px.onnx'))})")
