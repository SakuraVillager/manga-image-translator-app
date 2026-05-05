"""
Export CTC OCR model (ocr-ctc) to ONNX.
Single forward pass, no autoregressive loop — easy ONNX export.

Usage:
  python scripts/export_ctc_onnx.py [output_path]

Output: ocr_ctc_48px.onnx (~100MB)
"""

import os
import sys
import torch
import numpy as np

# Add project root to path — use CWD (user should cd into python project root)
_project_root = os.getcwd()
if not os.path.exists(os.path.join(_project_root, 'manga_translator')):
    _project_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, _project_root)
PROJECT_ROOT = _project_root

# --- Download model if not present ---
MODEL_URL = "https://github.com/zyddnys/manga-image-translator/releases/download/beta-0.3/ocr-ctc.zip"
MODEL_HASH = "fc61c52f7a811bc72c54f6be85df814c6b60f63585175db27cb94a08e0c30101"
MODEL_DIR = os.path.join(PROJECT_ROOT, 'models', 'ocr')
CKPT_PATH = os.path.join(MODEL_DIR, 'ocr-ctc.ckpt')
DICT_PATH = os.path.join(MODEL_DIR, 'alphabet-all-v5.txt')

if not os.path.exists(CKPT_PATH) or not os.path.exists(DICT_PATH):
    print("CTC model files not found. Downloading...")
    import zipfile
    import hashlib
    zip_path = os.path.join(MODEL_DIR, 'ocr-ctc.zip')
    os.makedirs(MODEL_DIR, exist_ok=True)
    torch.hub.download_url_to_file(MODEL_URL, zip_path)
    with zipfile.ZipFile(zip_path, 'r') as zf:
        zf.extractall(MODEL_DIR)
    os.remove(zip_path)
    print("Download complete.")

# --- Load model ---
from manga_translator.ocr.model_48px_ctc import OCR

with open(DICT_PATH, 'r', encoding='utf-8') as fp:
    dictionary = [s[:-1] for s in fp.readlines()]
print(f"Dictionary size: {len(dictionary)}")

# CTC OCR model - ResNet backbone + 3-layer TransformerEncoder
# Input:  [N, 3, 48, W]  image tensor
# Output: logits [N, W', dict_size], colors [N, W', 6]
model = OCR(dictionary, 768)
sd = torch.load(CKPT_PATH, map_location='cpu')
sd = sd['model'] if 'model' in sd else sd
# Remove encoders position encoding (not used in CTC forward)
for k in list(sd.keys()):
    if 'pe.pe' in k:
        del sd[k]
model.load_state_dict(sd, strict=False)
model.eval()

# --- Export to ONNX ---
output_path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    PROJECT_ROOT, 'ocr_ctc_48px.onnx'
)

# Dummy input: N=1, C=3, H=48, W=128 (dynamic width)
dummy_img = torch.randn(1, 3, 48, 128, dtype=torch.float32)

print(f"Exporting to: {output_path}")

with torch.inference_mode():
    torch.onnx.export(
        model,                          # Direct export of OCR.forward()
        dummy_img,
        output_path,
        input_names=['img'],
        output_names=['logits', 'colors'],
        dynamic_axes={
            'img': {0: 'batch', 3: 'width'},
            'logits': {0: 'batch', 1: 'seq_len'},
            'colors': {0: 'batch', 1: 'seq_len'},
        },
        opset_version=18,
        verbose=False,
    )

file_size_mb = os.path.getsize(output_path) / 1024 / 1024
print(f"Export complete! {file_size_mb:.1f} MB")

# --- Verify ---
print("Verifying with ONNX Runtime...")
import onnxruntime as ort
session = ort.InferenceSession(output_path)
inputs = {'img': dummy_img.numpy()}
logits_ort, colors_ort = session.run(None, inputs)
print(f"  logits: {logits_ort.shape}")
print(f"  colors: {colors_ort.shape}")

print("Comparing with PyTorch...")
with torch.no_grad():
    logits_pt, colors_pt = model(dummy_img)
diff_logits = (logits_pt.numpy() - logits_ort).max()
diff_colors = (colors_pt.numpy() - colors_ort).max()
print(f"  logits max diff: {diff_logits:.6f}")
print(f"  colors max diff: {diff_colors:.6f}")

# Also test CTC decode works
preds = logits_ort.argmax(2)  # greedy argmax
print(f"  Sample decoded chars: {preds[0, :10].tolist()}")
print("Done!")
