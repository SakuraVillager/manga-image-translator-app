"""
Quick test: verify E2E ONNX model with a structured image pattern
that produces non-trivial decoded output (not just END token).
"""
import os, sys, torch, numpy as np

def main():
    project_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    sys.path.insert(0, os.path.join(project_root, 'scripts'))
    from export_ocr_ar_48px_e2e_onnx import (
        OCR, load_ckpt, FullModelE2E, EMB_DIM, N_DECODERS, MAX_SEQ
    )
    import onnxruntime as ort

    python_web_dir = os.path.join(project_root, 'python-web')
    model_path = os.path.join(python_web_dir, 'models', 'ocr', 'ocr_ar_48px.ckpt')
    dict_path = os.path.join(python_web_dir, 'models', 'ocr', 'alphabet-all-v7.txt')
    onnx_path = os.path.join(project_root, 'models', 'ocr_ar_48px.onnx')

    with open(dict_path, 'r', encoding='utf-8') as fp:
        dictionary = [s[:-1] for s in fp.readlines()]
    dict_size = len(dictionary)

    model = OCR(dictionary, 768)
    sd = torch.load(model_path, map_location='cpu', weights_only=True)
    load_ckpt(model, sd)
    model.eval()

    MAX_DECODE = 30
    e2e = FullModelE2E(model, max_decode=MAX_DECODE)
    e2e.eval()

    # Create a structured image: gradient pattern that looks more like real text
    N, W_test = 1, 400
    img = torch.zeros(N, 3, 48, W_test)
    # Add gradient + some structure
    for c in range(3):
        img[0, c, :, :] = torch.linspace(-1, 1, W_test).unsqueeze(0).expand(48, -1)
    # Add some "text-like" dark regions
    img[0, :, 10:38, 50:120] = -0.8  # dark region
    img[0, :, 10:38, 140:180] = -0.6
    img[0, :, 10:38, 200:300] = -0.7
    widths = torch.tensor([W_test], dtype=torch.long)

    # PyTorch
    with torch.no_grad():
        pt_out = e2e(img, widths)
    pt_tokens = pt_out[0][0].tolist()
    end_pos = None
    for i, t in enumerate(pt_tokens):
        if t == 2:
            end_pos = i
            break
    valid_pt = pt_tokens[:end_pos] if end_pos else pt_tokens
    pt_txt = ''.join(dictionary[i] for i in valid_pt if i < dict_size and dictionary[i] not in ['<S>', '</S>'])
    print(f"PyTorch tokens ({len(valid_pt)} valid): {valid_pt[:20]}")
    print(f"PyTorch text: '{pt_txt}'")

    # ONNX
    sess = ort.InferenceSession(onnx_path, providers=['CPUExecutionProvider'])
    ort_out = sess.run(None, {'img': img.numpy(), 'img_widths': widths.numpy()})
    ort_tokens = ort_out[0][0].tolist()
    end_pos_ort = None
    for i, t in enumerate(ort_tokens):
        if t == 2:
            end_pos_ort = i
            break
    valid_ort = ort_tokens[:end_pos_ort] if end_pos_ort else ort_tokens
    ort_txt = ''.join(dictionary[i] for i in valid_ort if i < dict_size and dictionary[i] not in ['<S>', '</S>'])
    print(f"\nONNX tokens ({len(valid_ort)} valid): {valid_ort[:20]}")
    print(f"ONNX text: '{ort_txt}'")

    # Compare
    token_match = pt_tokens == ort_tokens
    print(f"\nToken sequences MATCH: {token_match}")
    for name, pt_o, ort_o in zip(
        ['tokens', 'fg_colors', 'bg_colors', 'fg_ind', 'bg_ind'], pt_out, ort_out):
        diff = np.abs(pt_o.detach().numpy() - ort_o).max()
        print(f"  {name}: max_diff={diff:.8f} {'OK' if diff < 0.01 else 'WARN'}")

if __name__ == '__main__':
    main()
