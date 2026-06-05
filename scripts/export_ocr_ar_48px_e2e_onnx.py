"""
Export ocr_ar_48px.ckpt to a single end-to-end ONNX model.

The model takes an image and outputs decoded character tokens plus color predictions.
The entire encoder + autoregressive decoding loop is inside one ONNX graph.

Usage:
    python scripts/export_ocr_ar_48px_e2e_onnx.py [output_dir]
"""
import os, sys, math, torch, torch.nn as nn, torch.nn.functional as F, numpy as np, einops

# ============================================================
# XPOS (same as original)
# ============================================================
def fixed_pos_embedding(x):
    seq_len, dim = x.shape
    inv_freq = 1.0 / (10000 ** (torch.arange(0, dim) / dim))
    sinusoid_inp = torch.einsum("i , j -> i j", torch.arange(0, seq_len, dtype=torch.float), inv_freq).to(x)
    return torch.sin(sinusoid_inp), torch.cos(sinusoid_inp)

def rotate_every_two(x):
    x1 = x[:, :, ::2]; x2 = x[:, :, 1::2]
    x = torch.stack((-x2, x1), dim=-1)
    return x.flatten(-2)

def duplicate_interleave(m):
    dim0 = m.shape[0]; m = m.view(-1, 1); m = m.repeat(1, 2)
    return m.view(dim0, -1)

def apply_rotary_pos_emb(x, sin, cos, scale=1):
    sin, cos = map(lambda t: duplicate_interleave(t * scale), (sin, cos))
    return (x * cos) + (rotate_every_two(x) * sin)

class XPOS(nn.Module):
    def __init__(self, head_dim, scale_base=512):
        super().__init__()
        self.head_dim = head_dim; self.scale_base = scale_base
        self.register_buffer("scale", (torch.arange(0, head_dim, 2) + 0.4 * head_dim) / (1.4 * head_dim))
    def forward(self, x, offset=0, downscale=False):
        length = x.shape[1]
        min_pos = -(length + offset) // 2
        max_pos = length + offset + min_pos
        scale = self.scale ** torch.arange(min_pos, max_pos, 1).to(self.scale).div(self.scale_base)[:, None]
        sin, cos = fixed_pos_embedding(scale)
        if scale.shape[0] > length:
            scale = scale[-length:]; sin = sin[-length:]; cos = cos[-length:]
        if downscale: scale = 1 / scale
        return apply_rotary_pos_emb(x, sin, cos, scale)

# ============================================================
# ConvNeXt
# ============================================================
class ConvNeXtBlock(nn.Module):
    def __init__(self, dim, layer_scale_init_value=1e-6, ks=7, padding=3):
        super().__init__()
        self.dwconv = nn.Conv2d(dim, dim, kernel_size=ks, padding=padding, groups=dim)
        self.norm = nn.BatchNorm2d(dim, eps=1e-6)
        self.pwconv1 = nn.Conv2d(dim, 4*dim, 1, 1, 0)
        self.act = nn.GELU()
        self.pwconv2 = nn.Conv2d(4*dim, dim, 1, 1, 0)
        self.gamma = nn.Parameter(layer_scale_init_value * torch.ones(1, dim, 1, 1),
                                  requires_grad=True) if layer_scale_init_value > 0 else None
    def forward(self, x):
        r = x; x = self.dwconv(x); x = self.norm(x)
        x = self.pwconv1(x); x = self.act(x); x = self.pwconv2(x)
        if self.gamma is not None: x = self.gamma * x
        return r + x

class ConvNext_FeatureExtractor(nn.Module):
    def __init__(self, img_height=48, in_dim=3, dim=512):
        super().__init__()
        base = dim // 8
        self.stem = nn.Sequential(
            nn.Conv2d(in_dim, base, 7, 1, 3), nn.BatchNorm2d(base), nn.ReLU(),
            nn.Conv2d(base, base*2, 2, 2, 0), nn.BatchNorm2d(base*2), nn.ReLU(),
            nn.Conv2d(base*2, base*2, 3, 1, 1), nn.BatchNorm2d(base*2), nn.ReLU())
        self.block1 = nn.Sequential(*[ConvNeXtBlock(base*2) for _ in range(4)])
        self.down1 = nn.Sequential(nn.Conv2d(base*2, base*4, 2, 2, 0), nn.BatchNorm2d(base*4), nn.ReLU())
        self.block2 = nn.Sequential(*[ConvNeXtBlock(base*4) for _ in range(12)])
        self.down2 = nn.Sequential(nn.Conv2d(base*4, base*8, (2,1), (2,1), (0,0)), nn.BatchNorm2d(base*8), nn.ReLU())
        self.block3 = nn.Sequential(*[ConvNeXtBlock(base*8, ks=5, padding=2) for _ in range(10)])
        self.down3 = nn.Sequential(nn.Conv2d(base*8, base*8, (2,1), (2,1), (0,0)), nn.BatchNorm2d(base*8), nn.ReLU())
        self.block4 = nn.Sequential(*[ConvNeXtBlock(base*8, ks=3, padding=1) for _ in range(8)])
        self.down4 = nn.Sequential(nn.Conv2d(base*8, base*8, (3,1), (1,1), (0,0)), nn.BatchNorm2d(base*8), nn.ReLU())
    def forward(self, x):
        x = self.stem(x); x = self.block1(x); x = self.down1(x)
        x = self.block2(x); x = self.down2(x); x = self.block3(x)
        x = self.down3(x); x = self.block4(x); x = self.down4(x)
        return x

# ============================================================
# Attention + Layers
# ============================================================
class XposMultiheadAttention(nn.Module):
    def __init__(self, embed_dim, num_heads, self_attention=False, encoder_decoder_attention=False):
        super().__init__()
        self.embed_dim = embed_dim; self.num_heads = num_heads
        self.head_dim = embed_dim // num_heads; self.scaling = self.head_dim ** -0.5
        self.k_proj = nn.Linear(embed_dim, embed_dim, bias=True)
        self.v_proj = nn.Linear(embed_dim, embed_dim, bias=True)
        self.q_proj = nn.Linear(embed_dim, embed_dim, bias=True)
        self.out_proj = nn.Linear(embed_dim, embed_dim, bias=True)
        self.xpos = XPOS(self.head_dim, embed_dim)
    def forward(self, query, key, value, key_padding_mask=None, attn_mask=None,
                need_weights=False, is_causal=False, k_offset=0, q_offset=0):
        bsz, tgt_len, embed_dim = query.size(); src_len = key.size(1)
        q = self.q_proj(query) * self.scaling; k = self.k_proj(key); v = self.v_proj(value)
        q = q.view(bsz, tgt_len, self.num_heads, self.head_dim).transpose(1,2)
        k = k.view(bsz, src_len, self.num_heads, self.head_dim).transpose(1,2)
        v = v.view(bsz, src_len, self.num_heads, self.head_dim).transpose(1,2)
        q = q.reshape(bsz*self.num_heads, tgt_len, self.head_dim)
        k = k.reshape(bsz*self.num_heads, src_len, self.head_dim)
        v = v.reshape(bsz*self.num_heads, src_len, self.head_dim)
        k = self.xpos(k, offset=k_offset, downscale=True)
        q = self.xpos(q, offset=q_offset, downscale=False)
        attn_weights = torch.bmm(q, k.transpose(1,2))
        if key_padding_mask is not None:
            attn_weights = attn_weights.view(bsz, self.num_heads, tgt_len, src_len)
            kpm = key_padding_mask.unsqueeze(1).unsqueeze(2).to(torch.bool)
            attn_weights = attn_weights.masked_fill(kpm, float("-inf"))
            attn_weights = attn_weights.view(bsz*self.num_heads, tgt_len, src_len)
        attn_weights = F.softmax(attn_weights, dim=-1, dtype=torch.float32).type_as(attn_weights)
        attn = torch.bmm(attn_weights, v)
        attn = attn.transpose(0,1).reshape(tgt_len, bsz, embed_dim).transpose(0,1)
        return self.out_proj(attn), None

class XposEncoderLayer(nn.Module):
    def __init__(self, d_model, nhead, dim_feedforward=2048, dropout=0.0):
        super().__init__()
        self.self_attn = XposMultiheadAttention(d_model, nhead, self_attention=True)
        self.norm1 = nn.LayerNorm(d_model); self.norm2 = nn.LayerNorm(d_model)
        self.linear1 = nn.Linear(d_model, dim_feedforward)
        self.linear2 = nn.Linear(dim_feedforward, d_model)
        self.dropout1 = nn.Dropout(dropout); self.dropout2 = nn.Dropout(dropout); self.dropout3 = nn.Dropout(dropout)
    def _ff_block(self, x): return self.dropout3(self.linear2(self.dropout2(F.relu(self.linear1(x)))))
    def forward(self, src, src_key_padding_mask=None):
        x = src + self.self_attn(self.norm1(src), self.norm1(src), self.norm1(src), key_padding_mask=src_key_padding_mask)[0]
        x = x + self._ff_block(self.norm2(x))
        return x

class XposDecoderLayer(nn.Module):
    def __init__(self, d_model, nhead, dim_feedforward=2048, dropout=0.0):
        super().__init__()
        self.self_attn = XposMultiheadAttention(d_model, nhead, self_attention=True)
        self.multihead_attn = XposMultiheadAttention(d_model, nhead, encoder_decoder_attention=True)
        self.norm1 = nn.LayerNorm(d_model); self.norm2 = nn.LayerNorm(d_model); self.norm3 = nn.LayerNorm(d_model)
        self.linear1 = nn.Linear(d_model, dim_feedforward)
        self.linear2 = nn.Linear(dim_feedforward, d_model)
        self.dropout1 = nn.Dropout(dropout); self.dropout2 = nn.Dropout(dropout); self.dropout3 = nn.Dropout(dropout)
    def _ff_block(self, x): return self.dropout3(self.linear2(self.dropout2(F.relu(self.linear1(x)))))

# ============================================================
# OCR Model
# ============================================================
EMB_DIM = 320; NHEAD = 4; FF_DIM = 2048
N_ENCODERS = 4; N_DECODERS = 5; MAX_SEQ = 255

class OCR(nn.Module):
    def __init__(self, dictionary, max_len):
        super().__init__()
        self.dictionary = dictionary; self.dict_size = len(dictionary)
        self.backbone = ConvNext_FeatureExtractor(48, 3, EMB_DIM)
        self.encoder_layers = nn.ModuleList([XposEncoderLayer(EMB_DIM, NHEAD, FF_DIM) for _ in range(N_ENCODERS)])
        self.decoder_layers = nn.ModuleList([XposDecoderLayer(EMB_DIM, NHEAD, FF_DIM) for _ in range(N_DECODERS)])
        self.embd = nn.Embedding(self.dict_size, EMB_DIM)
        self.pred1 = nn.Sequential(nn.Linear(EMB_DIM, EMB_DIM), nn.GELU(), nn.Dropout(0.15))
        self.pred = nn.Linear(EMB_DIM, self.dict_size)
        self.pred.weight = self.embd.weight
        self.color_pred1 = nn.Sequential(nn.Linear(EMB_DIM, 64), nn.ReLU())
        self.color_pred_fg = nn.Linear(64, 3); self.color_pred_bg = nn.Linear(64, 3)
        self.color_pred_fg_ind = nn.Linear(64, 2); self.color_pred_bg_ind = nn.Linear(64, 2)

def load_ckpt(model, sd):
    new_sd = {}
    for k, v in sd.items():
        nk = k
        if k.startswith('encoders.'): nk = 'encoder_layers.' + k[len('encoders.'):]
        elif k.startswith('decoders.'): nk = 'decoder_layers.' + k[len('decoders.'):]
        new_sd[nk] = v
    missing, unexpected = model.load_state_dict(new_sd, strict=False)
    if missing: print(f"  Missing keys ({len(missing)}): {missing[:3]}...")
    if unexpected: print(f"  Unexpected keys ({len(unexpected)}): {unexpected[:3]}...")
    return model

# ============================================================
# Full End-to-End Wrapper
# ============================================================
class FullModelE2E(nn.Module):
    """
    Single ONNX model: image -> greedy decoded tokens + color predictions.

    Inputs:
        img:        [N, 3, 48, W]  float32
        img_widths: [N]            int64

    Outputs:
        tokens:      [N, max_decode]  int64   (decoded token IDs, 0-padded after END)
        fg_colors:   [N, max_decode, 3] float32
        bg_colors:   [N, max_decode, 3] float32
        fg_ind:      [N, max_decode, 2] float32
        bg_ind:      [N, max_decode, 2] float32
    """
    def __init__(self, model, max_decode=50):
        super().__init__()
        self.model = model
        self.max_decode = max_decode

    def forward(self, img, img_widths):
        N = img.shape[0]
        device = img.device

        # === ENCODER ===
        memory = self.model.backbone(img)
        memory = einops.rearrange(memory, 'N C 1 W -> N W C')
        W_mem = memory.size(1)
        valid_lengths = (img_widths + 3) // 4 + 2
        positions = torch.arange(W_mem, device=device).unsqueeze(0)
        input_mask = positions >= valid_lengths.unsqueeze(1)
        for layer in self.model.encoder_layers:
            memory = layer(memory, src_key_padding_mask=input_mask)

        # === DECODER (greedy autoregressive loop) ===
        cache = torch.zeros(N, N_DECODERS + 1, MAX_SEQ, EMB_DIM, device=device)
        tokens = torch.full((N,), 1, dtype=torch.long, device=device)  # START_TOK=1

        all_tokens = torch.zeros(N, self.max_decode, dtype=torch.long, device=device)
        all_fg = torch.zeros(N, self.max_decode, 3, device=device)
        all_bg = torch.zeros(N, self.max_decode, 3, device=device)
        all_fg_ind = torch.zeros(N, self.max_decode, 2, device=device)
        all_bg_ind = torch.zeros(N, self.max_decode, 2, device=device)

        for si in range(self.max_decode):
            tgt = self.model.embd(tokens).unsqueeze(1)  # [N, 1, E]

            for l, layer in enumerate(self.model.decoder_layers):
                combined = cache[:, l, :si, :]
                combined = torch.cat([combined, tgt], dim=1)
                cache[:, l, si, :] = tgt.squeeze(1)

                sa = layer.self_attn(
                    layer.norm1(tgt), layer.norm1(combined), layer.norm1(combined),
                    q_offset=si
                )[0]
                tgt = tgt + sa

                ca = layer.multihead_attn(
                    layer.norm2(tgt), memory, memory,
                    key_padding_mask=input_mask, q_offset=si
                )[0]
                tgt = tgt + ca

                tgt = tgt + layer._ff_block(layer.norm3(tgt))

            cache[:, N_DECODERS, si, :] = tgt.squeeze(1)

            decoded = tgt.squeeze(1)  # [N, E]
            char_logits = self.model.pred(self.model.pred1(decoded))
            color_feats = self.model.color_pred1(decoded)
            fg_c = self.model.color_pred_fg(color_feats)
            bg_c = self.model.color_pred_bg(color_feats)
            fg_i = self.model.color_pred_fg_ind(color_feats)
            bg_i = self.model.color_pred_bg_ind(color_feats)

            pred = char_logits.argmax(dim=-1)  # [N]

            all_tokens[:, si] = pred
            all_fg[:, si] = fg_c
            all_bg[:, si] = bg_c
            all_fg_ind[:, si] = fg_i
            all_bg_ind[:, si] = bg_i

            tokens = pred

        return all_tokens, all_fg, all_bg, all_fg_ind, all_bg_ind


# ============================================================
# Main
# ============================================================
def main():
    project_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    python_web_dir = os.path.join(project_root, 'python-web')

    model_path = os.path.join(python_web_dir, 'models', 'ocr', 'ocr_ar_48px.ckpt')
    dict_path = os.path.join(python_web_dir, 'models', 'ocr', 'alphabet-all-v7.txt')
    output_dir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(project_root, 'models')
    onnx_path = os.path.join(output_dir, 'ocr_ar_48px.onnx')

    if not os.path.exists(model_path):
        model_path = os.path.join(project_root, 'models', 'ocr', 'ocr_ar_48px.ckpt')
    if not os.path.exists(dict_path):
        dict_path = os.path.join(project_root, 'models', 'ocr', 'alphabet-all-v7.txt')

    print(f"Checkpoint : {model_path}")
    print(f"Dictionary : {dict_path}")
    print(f"Output dir : {output_dir}")

    with open(dict_path, 'r', encoding='utf-8') as fp:
        dictionary = [s[:-1] for s in fp.readlines()]
    dict_size = len(dictionary)
    print(f"Dictionary size: {dict_size}")

    model = OCR(dictionary, 768)
    sd = torch.load(model_path, map_location='cpu', weights_only=True)
    load_ckpt(model, sd)
    model.eval()
    total_params = sum(p.numel() for p in model.parameters())
    print(f"Model loaded. Parameters: {total_params:,}")

    MAX_DECODE = 30  # max characters to decode
    N_test, W_test = 1, 200
    torch.manual_seed(42)
    dummy_img = torch.randn(N_test, 3, 48, W_test)
    dummy_widths = torch.tensor([W_test], dtype=torch.long)

    # === PyTorch reference ===
    e2e = FullModelE2E(model, max_decode=MAX_DECODE)
    e2e.eval()
    with torch.no_grad():
        pt_out = e2e(dummy_img, dummy_widths)
    pt_tokens = pt_out[0]
    print(f"\nPyTorch decoded tokens: {pt_tokens[0].tolist()[:20]}")
    # Find END token position
    end_pos = None
    for i in range(pt_tokens.shape[1]):
        if pt_tokens[0, i].item() == 2:
            end_pos = i
            break
    print(f"END token at position: {end_pos}")
    pt_text_tokens = pt_tokens[0, :end_pos].tolist() if end_pos else pt_tokens[0].tolist()
    pt_txt = ''.join(dictionary[i] for i in pt_text_tokens if i < dict_size and dictionary[i] not in ['<S>', '</S>'])
    print(f"PyTorch text: '{pt_txt}'")

    # === Export ONNX ===
    print(f"\nExporting ONNX (max_decode={MAX_DECODE})...")
    os.makedirs(output_dir, exist_ok=True)
    torch.onnx.export(
        e2e, (dummy_img, dummy_widths), onnx_path,
        input_names=['img', 'img_widths'],
        output_names=['tokens', 'fg_colors', 'bg_colors', 'fg_indicators', 'bg_indicators'],
        dynamic_axes={
            'img': {0: 'batch', 3: 'width'},
            'img_widths': {0: 'batch'},
            'tokens': {0: 'batch'},
            'fg_colors': {0: 'batch'},
            'bg_colors': {0: 'batch'},
            'fg_indicators': {0: 'batch'},
            'bg_indicators': {0: 'batch'},
        },
        opset_version=18, dynamo=False,
    )
    onnx_mb = os.path.getsize(onnx_path) / 1024 / 1024
    print(f"ONNX model: {onnx_mb:.1f} MB -> {onnx_path}")

    # === Verify with ONNX Runtime ===
    print("\nVerifying with ONNX Runtime...")
    import onnx, onnxruntime as ort
    onnx.checker.check_model(onnx.load(onnx_path))
    print("ONNX checker: PASSED")

    sess = ort.InferenceSession(onnx_path, providers=['CPUExecutionProvider'])
    ort_out = sess.run(None, {'img': dummy_img.numpy(), 'img_widths': dummy_widths.numpy()})

    ort_tokens = ort_out[0]
    print(f"ONNX decoded tokens: {ort_tokens[0].tolist()[:20]}")
    end_pos_ort = None
    for i in range(ort_tokens.shape[1]):
        if ort_tokens[0, i] == 2:
            end_pos_ort = i
            break
    print(f"END token at position: {end_pos_ort}")
    ort_text_tokens = ort_tokens[0, :end_pos_ort].tolist() if end_pos_ort else ort_tokens[0].tolist()
    ort_txt = ''.join(dictionary[i] for i in ort_text_tokens if i < dict_size and dictionary[i] not in ['<S>', '</S>'])
    print(f"ONNX text: '{ort_txt}'")

    # Compare
    token_match = np.array_equal(pt_tokens.numpy(), ort_tokens)
    print(f"\nToken sequences {'MATCH' if token_match else 'DIFFER'}!")
    for i, (name, pt_o, ort_o) in enumerate(zip(
        ['tokens', 'fg_colors', 'bg_colors', 'fg_ind', 'bg_ind'], pt_out, ort_out)):
        diff = np.abs(pt_o.detach().numpy() - ort_o).max()
        print(f"  {name}: max_diff={diff:.8f} {'OK' if diff < 1e-3 else 'WARN'}")

    print(f"\n{'='*60}")
    print(f"SUMMARY")
    print(f"{'='*60}")
    print(f"ONNX model: {onnx_path} ({onnx_mb:.1f} MB)")
    print(f"Max decode length: {MAX_DECODE}")
    print("Done!")


if __name__ == '__main__':
    main()
