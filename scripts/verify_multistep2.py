"""
Forced multi-step verification: feed predetermined tokens and compare
logits at each step to verify XPOS adapts correctly at different offsets.
"""
import os, sys, torch, numpy as np

def main():
    project_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    sys.path.insert(0, os.path.join(project_root, 'scripts'))
    from export_ocr_ar_48px_onnx import (
        OCR, load_ckpt, EncoderWrapper, DecoderStepWrapper,
        model_decode_step, EMB_DIM, N_DECODERS, MAX_SEQ
    )
    import onnxruntime as ort

    python_web_dir = os.path.join(project_root, 'python-web')
    model_path = os.path.join(python_web_dir, 'models', 'ocr', 'ocr_ar_48px.ckpt')
    dict_path = os.path.join(python_web_dir, 'models', 'ocr', 'alphabet-all-v7.txt')
    enc_path = os.path.join(project_root, 'models', 'ocr_ar_48px_encoder.onnx')
    dec_path = os.path.join(project_root, 'models', 'ocr_ar_48px_decoder.onnx')

    with open(dict_path, 'r', encoding='utf-8') as fp:
        dictionary = [s[:-1] for s in fp.readlines()]

    model = OCR(dictionary, 768)
    sd = torch.load(model_path, map_location='cpu', weights_only=True)
    load_ckpt(model, sd)
    model.eval()

    N = 1
    W_test = 200
    # Use a fixed seed for reproducibility
    torch.manual_seed(42)
    dummy_img = torch.randn(N, 3, 48, W_test)
    dummy_widths = torch.tensor([W_test], dtype=torch.long)

    enc_wrapper = EncoderWrapper(model)
    enc_wrapper.eval()
    with torch.no_grad():
        pt_memory, pt_mask = enc_wrapper(dummy_img, dummy_widths)

    enc_sess = ort.InferenceSession(enc_path, providers=['CPUExecutionProvider'])
    dec_sess = ort.InferenceSession(dec_path, providers=['CPUExecutionProvider'])

    ort_enc = enc_sess.run(None, {
        'img': dummy_img.numpy(), 'img_widths': dummy_widths.numpy()
    })
    mem_ort, mask_ort = ort_enc[0], ort_enc[1]

    # Force-feed a predetermined token sequence to test multiple steps
    # Use common characters: START(1), then some CJK chars, then compare outputs
    NUM_STEPS = 15
    # Generate random but valid token IDs (between 3 and dict_size-1)
    torch.manual_seed(123)
    forced_tokens = [1]  # START
    for _ in range(NUM_STEPS - 1):
        forced_tokens.append(int(torch.randint(3, min(200, len(dictionary)), (1,)).item()))

    print(f"Forced token sequence: {forced_tokens}")
    print(f"Testing {NUM_STEPS} decoder steps...\n")

    # PyTorch forced decoding
    cache_pt = torch.zeros(N, N_DECODERS + 1, MAX_SEQ, EMB_DIM)
    pt_logits_all = []
    with torch.no_grad():
        for si in range(NUM_STEPS):
            tok = torch.tensor([forced_tokens[si]], dtype=torch.long)
            s = torch.tensor(si, dtype=torch.long)
            logits, fg, bg, fg_i, bg_i, cache_pt = model_decode_step(
                model, tok, s, pt_memory, pt_mask, cache_pt
            )
            pt_logits_all.append(logits.detach().clone().numpy())

    # ONNX forced decoding
    cache_ort = np.zeros((N * (N_DECODERS + 1), MAX_SEQ, EMB_DIM), dtype=np.float32)
    ort_logits_all = []
    for si in range(NUM_STEPS):
        tok_ort = np.array([forced_tokens[si]], dtype=np.int64)
        step_ort = np.array(si, dtype=np.int64)
        out = dec_sess.run(None, {
            'token_ids': tok_ort, 'step': step_ort,
            'memory': mem_ort, 'memory_mask': mask_ort, 'cache_flat': cache_ort,
        })
        ort_logits_all.append(out[0].copy())
        cache_ort = out[5]

    # Compare at each step
    print(f"{'Step':>4} {'Max Diff':>12} {'Argmax Match':>14} {'PT Top3':>30} {'ORT Top3':>30}")
    print("-" * 96)
    all_match = True
    for si in range(NUM_STEPS):
        pt_l = pt_logits_all[si]
        ort_l = ort_logits_all[si]
        diff = np.abs(pt_l - ort_l).max()
        pt_top = np.argsort(pt_l[0])[-3:][::-1].tolist()
        ort_top = np.argsort(ort_l[0])[-3:][::-1].tolist()
        match = pt_top[0] == ort_top[0]
        if not match:
            all_match = False
        status = "OK" if diff < 0.01 else "WARN"
        print(f"  {si:2d}   {diff:12.6f}   {'Yes' if match else 'NO':>14}   "
              f"{str(pt_top):>30}   {str(ort_top):>30}  {status}")

    print(f"\nAll argmax match: {all_match}")

    # Also test: free-running greedy decoding (allow model to pick tokens)
    print("\n--- Free-running greedy decoding ---")
    cache_pt2 = torch.zeros(N, N_DECODERS + 1, MAX_SEQ, EMB_DIM)
    cache_ort2 = np.zeros((N * (N_DECODERS + 1), MAX_SEQ, EMB_DIM), dtype=np.float32)
    tok_pt = torch.tensor([1], dtype=torch.long)
    tok_ort = np.array([1], dtype=np.int64)
    pt_seq, ort_seq = [], []

    with torch.no_grad():
        for si in range(50):
            # PyTorch step
            s = torch.tensor(si, dtype=torch.long)
            logits_pt, *_, cache_pt2 = model_decode_step(
                model, tok_pt, s, pt_memory, pt_mask, cache_pt2
            )
            pred_pt = logits_pt.argmax(dim=-1)[0].item()

            # ONNX step
            out = dec_sess.run(None, {
                'token_ids': tok_ort, 'step': np.array(si, dtype=np.int64),
                'memory': mem_ort, 'memory_mask': mask_ort, 'cache_flat': cache_ort2,
            })
            pred_ort = int(np.argmax(out[0], axis=-1)[0])
            cache_ort2 = out[5]

            pt_seq.append(pred_pt)
            ort_seq.append(pred_ort)

            if pred_pt == 2 or pred_ort == 2:
                break

            tok_pt = torch.tensor([pred_pt], dtype=torch.long)
            tok_ort = np.array([pred_ort], dtype=np.int64)

    print(f"PyTorch: {pt_seq[:20]}")
    print(f"ONNX:    {ort_seq[:20]}")
    print(f"Match: {pt_seq == ort_seq}")

if __name__ == '__main__':
    main()
