"""
Export 48px OCR encoder-only model to ONNX.
Encoder = ConvNeXt backbone + 4 XPOS Transformer encoder layers.
Usage: python scripts/export_ocr_encoder_onnx.py
Output: models/ocr_ar_48px_encoder.onnx
"""

import os
import sys
import torch
import numpy as np
import einops

script_dir = os.path.dirname(os.path.abspath(__file__))
project_root = os.path.dirname(script_dir)
python_web_dir = os.path.join(project_root, 'python-web')
sys.path.insert(0, python_web_dir)

from manga_translator.ocr.model_48px import OCR


class ONNXEncoderWrapper(torch.nn.Module):
    """Wrapper that runs backbone (ConvNeXt) + 4 XPOS encoder layers only.

    Inputs:
        img: [N, 3, 48, W] float32 — batched image tensor (dynamic batch N, dynamic width W)
        img_widths: [N] int64 — original widths of each image before padding

    Outputs:
        memory: [N, W', 320] float32 — encoder output (backbone + 4 transformer encoder layers)
        input_mask: [N, W'] bool — padding mask (True = masked/padded positions)

    where W' = memory width after backbone = max_i floor(W_i / 4),
    and the mask masks positions beyond valid_feats_length[i] = (img_widths[i] + 3) // 4 + 2.
    """

    def __init__(self, backbone, encoder_list):
        super().__init__()
        self.backbone = backbone
        self.encoder_list = encoder_list

    def forward(self, img: torch.Tensor, img_widths: torch.Tensor):
        # backbone: [N, 3, 48, W] -> [N, 320, 1, W_mem]
        memory = self.backbone(img)
        # [N, 320, 1, W_mem] -> [N, W_mem, 320]
        memory = einops.rearrange(memory, 'N C 1 W -> N W C')

        W_mem = memory.size(1)
        # valid_feats_length = (w + 3) // 4 + 2  (from infer_beam_batch_tensor line 684)
        valid_lengths = (img_widths + 3) // 4 + 2  # [N]
        # Build padding mask: True for positions >= valid length
        positions = torch.arange(W_mem, device=img.device).unsqueeze(0)  # [1, W_mem]
        input_mask = positions >= valid_lengths.unsqueeze(1)  # [N, W_mem]

        # 4 XPOS Transformer encoder layers
        # Each layer has forward replaced with transformer_encoder_forward,
        # which takes (self, src, src_mask, src_key_padding_mask).
        # Calling layer(layer, ...) passes the layer instance as self.
        for layer in self.encoder_list:
            memory = layer(layer, src=memory, src_key_padding_mask=input_mask)

        return memory, input_mask


def main():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    project_root = os.path.dirname(script_dir)
    python_web_dir = os.path.join(project_root, 'python-web')

    # --- Locate checkpoint and dictionary ---
    model_path = os.path.join(python_web_dir, 'models', 'ocr', 'ocr_ar_48px.ckpt')
    dict_path = os.path.join(python_web_dir, 'models', 'ocr', 'alphabet-all-v7.txt')
    output_path = os.path.join(project_root, 'models', 'ocr_ar_48px_encoder.onnx')

    # Fallback paths
    if not os.path.exists(model_path):
        alt = os.path.join(project_root, 'models', 'ocr_ar_48px.ckpt')
        if os.path.exists(alt):
            model_path = alt
        else:
            print(f"ERROR: checkpoint not found at {model_path}")
            sys.exit(1)

    if not os.path.exists(dict_path):
        alt = os.path.join(project_root, 'models', 'ocr', 'alphabet-all-v7.txt')
        if os.path.exists(alt):
            dict_path = alt
        else:
            print(f"ERROR: dictionary not found at {dict_path}")
            sys.exit(1)

    print(f"Checkpoint: {model_path}")
    print(f"Dictionary: {dict_path}")
    print(f"Output:     {output_path}")

    # --- Load dictionary ---
    with open(dict_path, 'r', encoding='utf-8') as fp:
        dictionary = [s[:-1] for s in fp.readlines()]
    dict_size = len(dictionary)
    print(f"Dictionary size: {dict_size}")

    # --- Load model ---
    model = OCR(dictionary, 768)
    sd = torch.load(model_path, map_location='cpu')
    model.load_state_dict(sd)
    model.eval()
    print("Model loaded successfully.")

    # --- Create encoder-only wrapper ---
    wrapper = ONNXEncoderWrapper(model.backbone, model.encoders)
    wrapper.eval()

    # --- Test forward pass with dummy inputs ---
    N, W = 2, 128
    dummy_img = torch.randn(N, 3, 48, W, dtype=torch.float32)
    dummy_widths = torch.tensor([W, W], dtype=torch.long)

    with torch.no_grad():
        pt_memory, pt_mask = wrapper(dummy_img, dummy_widths)
    print(f"PyTorch forward shapes: memory {list(pt_memory.shape)}, mask {list(pt_mask.shape)}")

    # --- Export to ONNX ---
    os.makedirs(os.path.dirname(output_path), exist_ok=True)

    print("Exporting to ONNX...")
    with torch.inference_mode():
        torch.onnx.export(
            wrapper,
            (dummy_img, dummy_widths),
            output_path,
            input_names=['img', 'img_widths'],
            output_names=['memory', 'input_mask'],
            dynamic_axes={
                'img': {0: 'batch', 3: 'width'},
                'img_widths': {0: 'batch'},
                'memory': {0: 'batch', 1: 'feat_len'},
                'input_mask': {0: 'batch', 1: 'feat_len'},
            },
            opset_version=18,
            dynamo=False,
            verbose=False,
        )

    file_size_mb = os.path.getsize(output_path) / 1024 / 1024
    print(f"Export complete! {file_size_mb:.1f} MB")

    # --- Verify with ONNX checker ---
    import onnx
    onnx_model = onnx.load(output_path)
    onnx.checker.check_model(onnx_model)
    print("ONNX checker passed!")

    # --- Verify with ONNX Runtime ---
    print("Verifying with ONNX Runtime...")
    import onnxruntime as ort

    # Use CPU provider for verification
    session = ort.InferenceSession(output_path, providers=['CPUExecutionProvider'])
    inputs = {
        'img': dummy_img.numpy(),
        'img_widths': dummy_widths.numpy(),
    }
    onnx_outputs = session.run(None, inputs)
    onnx_memory, onnx_mask = onnx_outputs
    print(f"  ONNX Runtime shapes: memory {list(onnx_memory.shape)}, mask {list(onnx_mask.shape)}")

    # --- Compare outputs ---
    print("Comparing ONNX Runtime vs PyTorch...")
    memory_l2 = np.sqrt(np.mean((pt_memory.numpy() - onnx_memory) ** 2))
    mask_match = np.all(pt_mask.numpy() == onnx_mask)
    print(f"  memory L2 error: {memory_l2:.8f}")
    print(f"  mask exact match: {mask_match}")

    if memory_l2 < 1e-4:
        print("SUCCESS: L2 error < 1e-4!")
    else:
        print(f"WARNING: L2 error {memory_l2:.8f} >= 1e-4")
        print("This may indicate a mismatch between PyTorch and ONNX Runtime outputs.")

    # Also test with different batch size and width
    print("\nTesting with different input shapes (N=1, W=256)...")
    test_img = torch.randn(1, 3, 48, 256, dtype=torch.float32)
    test_widths = torch.tensor([256], dtype=torch.long)
    with torch.no_grad():
        pt_mem2, pt_mask2 = wrapper(test_img, test_widths)
    ort_out2 = session.run(None, {
        'img': test_img.numpy(),
        'img_widths': test_widths.numpy(),
    })
    mem_l2_2 = np.sqrt(np.mean((pt_mem2.numpy() - ort_out2[0]) ** 2))
    mask_ok_2 = np.all(pt_mask2.numpy() == ort_out2[1])
    print(f"  memory L2 error: {mem_l2_2:.8f}, mask match: {mask_ok_2}")

    print("Done!")


if __name__ == '__main__':
    main()
