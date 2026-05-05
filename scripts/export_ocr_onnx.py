"""
Export 48px OCR model to ONNX.
Usage: python scripts/export_ocr_onnx.py
Output: ocr_ar_48px.onnx (~200MB)
"""

import os
import sys
import torch
import numpy as np
import einops

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from manga_translator.ocr.model_48px import OCR


class ONNXOCRWrapper(torch.nn.Module):
    """Wrapper that exports encoder + autoregressive decoder as a single ONNX model."""

    def __init__(self, model: OCR):
        super().__init__()
        self.model = model
        self.max_seq_length = 255
        self.dict_size = model.dict_size
        self.start_tok = 1
        self.end_tok = 2
        self.pad_tok = 0
        self.embd_dim = 320

    def forward(self, img: torch.Tensor, img_widths: torch.Tensor):
        N = img.shape[0]
        device = img.device
        max_seq = self.max_seq_length

        # --- Encoder ---
        memory = self.model.backbone(img)
        memory = einops.rearrange(memory, 'N C 1 W -> N W C')

        valid_lengths = (img_widths + 3) // 4 + 2  # [N] tensor
        W_mem = memory.size(1)
        positions = torch.arange(W_mem, device=device).unsqueeze(0)  # [1, W_mem]
        input_mask = positions >= valid_lengths.unsqueeze(1)  # [N, W_mem]

        memory = self.model.encoders(memory, input_mask)

        # --- Output buffers ---
        logits = torch.zeros(N, max_seq, self.dict_size, device=device)
        fg_colors = torch.zeros(N, max_seq, 3, device=device)
        bg_colors = torch.zeros(N, max_seq, 3, device=device)
        fg_indicators = torch.zeros(N, max_seq, 2, device=device)
        bg_indicators = torch.zeros(N, max_seq, 2, device=device)

        # --- Autoregressive decoding ---
        out_idx = torch.full((N, 1), self.start_tok, dtype=torch.long, device=device)

        n_decoder_layers = len(list(self.model.decoders))
        cached_activations = torch.zeros(
            N, n_decoder_layers + 1, max_seq, self.embd_dim, device=device
        )

        finished = torch.zeros(N, dtype=torch.bool, device=device)

        for step in range(max_seq):
            tgt = self.model.embd(out_idx[:, -1:])
            decoded, cached_activations = self.model.decoders(
                tgt, cached_activations, memory, input_mask, step
            )

            char_logits = self.model.pred(self.model.pred1(decoded))
            pred_chars = char_logits.argmax(dim=-1).squeeze(-1)  # [N]

            color_feats = self.model.color_pred1(decoded)
            fg_c = self.model.color_pred_fg(color_feats)
            bg_c = self.model.color_pred_bg(color_feats)
            fg_i = self.model.color_pred_fg_ind(color_feats)
            bg_i = self.model.color_pred_bg_ind(color_feats)

            logits[:, step:step+1, :] = char_logits
            fg_colors[:, step:step+1, :] = fg_c
            bg_colors[:, step:step+1, :] = bg_c
            fg_indicators[:, step:step+1, :] = fg_i
            bg_indicators[:, step:step+1, :] = bg_i

            pred_chars[finished] = self.pad_tok
            just_finished = (pred_chars == self.end_tok) & (~finished)
            finished = finished | just_finished

            out_idx = torch.cat([out_idx, pred_chars.unsqueeze(-1)], dim=1)

            if finished.all():
                break

        return (logits, fg_colors, bg_colors, fg_indicators, bg_indicators)


def main():
    base_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    model_path = os.path.join(base_dir, 'models', 'ocr', 'ocr_ar_48px.ckpt')
    dict_path = os.path.join(base_dir, 'models', 'ocr', 'alphabet-all-v7.txt')
    output_path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(base_dir, 'ocr_ar_48px.onnx')

    with open(dict_path, 'r', encoding='utf-8') as fp:
        dictionary = [s[:-1] for s in fp.readlines()]
    print(f"Dictionary size: {len(dictionary)}")

    model = OCR(dictionary, 768)
    sd = torch.load(model_path, map_location='cpu')
    model.load_state_dict(sd)
    model.eval()

    wrapper = ONNXOCRWrapper(model)
    wrapper.eval()

    N, W = 1, 128
    dummy_img = torch.randn(N, 3, 48, W, dtype=torch.float32)
    dummy_widths = torch.tensor([W], dtype=torch.long)

    print(f"Exporting to: {output_path}")

    with torch.inference_mode():
        torch.onnx.export(
            wrapper,
            (dummy_img, dummy_widths),
            output_path,
            input_names=['img', 'img_widths'],
            output_names=[
                'logits', 'fg_colors', 'bg_colors',
                'fg_indicators', 'bg_indicators'
            ],
            dynamic_axes={
                'img': {0: 'batch', 3: 'width'},
                'img_widths': {0: 'batch'},
                'logits': {0: 'batch', 1: 'seq_len'},
                'fg_colors': {0: 'batch', 1: 'seq_len'},
                'bg_colors': {0: 'batch', 1: 'seq_len'},
                'fg_indicators': {0: 'batch', 1: 'seq_len'},
                'bg_indicators': {0: 'batch', 1: 'seq_len'},
            },
            opset_version=18,
            dynamo=False,  # Use legacy TorchScript path (more compatible)
            verbose=False,
        )

    file_size_mb = os.path.getsize(output_path) / 1024 / 1024
    print(f"Export complete! {file_size_mb:.1f} MB")

    print("Verifying with ONNX Runtime...")
    import onnxruntime as ort
    session = ort.InferenceSession(output_path)
    inputs = {
        'img': dummy_img.numpy(),
        'img_widths': dummy_widths.numpy(),
    }
    outputs = session.run(None, inputs)
    names = ['logits', 'fg_colors', 'bg_colors', 'fg_indicators', 'bg_indicators']
    for name, out in zip(names, outputs):
        print(f"  {name}: {out.shape}")

    print("Comparing with PyTorch...")
    with torch.no_grad():
        pt_outputs = wrapper(dummy_img, dummy_widths)
    for name, pt_out, onnx_out in zip(names, pt_outputs, outputs):
        diff = (pt_out.numpy() - onnx_out).max()
        print(f"  {name}: max diff = {diff:.6f}")
    print("Done!")


if __name__ == '__main__':
    main()
