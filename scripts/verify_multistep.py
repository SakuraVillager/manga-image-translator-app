"""
Quick multi-step verification: compare PyTorch decoder step vs ONNX decoder step
across multiple decoding steps to ensure XPOS offsets adapt correctly.
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
    dict_size = len(dictionary)

    model = OCR(dictionary, 768)
    sd = torch.load(model_path, map_location='cpu', weights_only=True)
    load_ckpt(model, sd)
    model.eval()

    N = 1
    W_test = 200  # wider image for more features
    dummy_img = torch.randn(N, 3, 48, W_test)
    dummy_widths = torch.tensor([W_test], dtype=torch.long)

    enc_wrapper = EncoderWrapper(model)
    enc_wrapper.eval()

    with torch.no_grad():
        pt_memory, pt_mask = enc_wrapper(dummy_img, dummy_widths)
    print(f"Encoder output: memory {list(pt_memory.shape)}, mask {list(pt_mask.shape)}")

    enc_sess = ort.InferenceSession(enc_path, providers=['CPUExecutionProvider'])
    dec_sess = ort.InferenceSession(dec_path, providers=['CPUExecutionProvider'])

    # Run encoder via ORT
    ort_enc = enc_sess.run(None, {
        'img': dummy_img.numpy(), 'img_widths': dummy_widths.numpy()
    })
    mem_ort, mask_ort = ort_enc[0], ort_enc[1]

    # Multi-step decoding comparison
    START_TOK = 1
    MAX_STEPS = 30

    # PyTorch greedy decoding
    cache_pt = torch.zeros(N, N_DECODERS + 1, MAX_SEQ, EMB_DIM)
    tokens_pt = torch.full((N,), START_TOK, dtype=torch.long)
    pt_logits_list = []
    pt_seq = []

    with torch.no_grad():
        for si in range(MAX_STEPS):
            s = torch.tensor(si, dtype=torch.long)
            logits, fg, bg, fg_i, bg_i, cache_pt = model_decode_step(
                model, tokens_pt, s, pt_memory, pt_mask, cache_pt
            )
            pt_logits_list.append(logits.detach().clone())
            pred = logits.argmax(dim=-1)
            pt_seq.append(pred[0].item())
            if pred[0].item() == 2:  # END
                break
            tokens_pt = pred

    print(f"\nPyTorch greedy: {len(pt_seq)} tokens -> {pt_seq[:20]}")

    # ONNX greedy decoding
    cache_ort = np.zeros((N * (N_DECODERS + 1), MAX_SEQ, EMB_DIM), dtype=np.float32)
    tok_ort = np.array([START_TOK], dtype=np.int64)
    ort_logits_list = []
    ort_seq = []

    for si in range(MAX_STEPS):
        step_ort = np.array(si, dtype=np.int64)
        out = dec_sess.run(None, {
            'token_ids': tok_ort, 'step': step_ort,
            'memory': mem_ort, 'memory_mask': mask_ort, 'cache_flat': cache_ort,
        })
        logits_ort = out[0]
        cache_ort = out[5]
        ort_logits_list.append(logits_ort.copy())
        pred = int(np.argmax(logits_ort, axis=-1)[0])
        ort_seq.append(pred)
        if pred == 2:
            break
        tok_ort = np.array([pred], dtype=np.int64)

    print(f"ONNX   greedy: {len(ort_seq)} tokens -> {ort_seq[:20]}")

    # Compare logits at each step
    min_steps = min(len(pt_logits_list), len(ort_logits_list))
    print(f"\nLogits comparison (first {min_steps} steps):")
    max_diffs = []
    for i in range(min_steps):
        pt_l = pt_logits_list[i].numpy()
        ort_l = ort_logits_list[i]
        diff = np.abs(pt_l - ort_l).max()
        max_diffs.append(diff)
        pred_match = int(pt_l.argmax()) == int(ort_l.argmax())
        print(f"  Step {i:2d}: max_diff={diff:.6f}, argmax match={pred_match}, "
              f"pt_pred={int(pt_l.argmax())}, ort_pred={int(ort_l.argmax())}")

    overall_max = max(max_diffs) if max_diffs else 0
    print(f"\nOverall max logit diff: {overall_max:.6f}")
    seq_match = pt_seq == ort_seq
    print(f"Sequence match: {seq_match}")

    # Convert to text
    pt_txt = ''.join(dictionary[i] for i in pt_seq if i < dict_size and dictionary[i] not in ['<S>', '</S>'])
    ort_txt = ''.join(dictionary[i] for i in ort_seq if i < dict_size and dictionary[i] not in ['<S>', '</S>'])
    print(f"PyTorch text: '{pt_txt}'")
    print(f"ONNX   text: '{ort_txt}'")

if __name__ == '__main__':
    main()
