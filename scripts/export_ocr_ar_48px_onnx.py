"""
Export ocr_ar_48px.ckpt to two ONNX models:
  1. ocr_ar_48px_encoder.onnx  — ConvNeXt backbone + 4 XPOS encoder layers
  2. ocr_ar_48px_decoder.onnx  — 5 XPOS decoder layers + prediction heads (single step)

The encoder runs once per image. The decoder step is called repeatedly by the
runtime (Java/Python) to perform greedy or beam-search decoding.

Key innovation: PrecomputedXPOS replaces all dynamic torch.arange operations
in the XPOS module with precomputed lookup tables + index_select, eliminating
the shape-baking problem in TorchScript tracing.

Usage:
    python scripts/export_ocr_ar_48px_onnx.py [output_dir]
"""

import os
import sys
import math
import torch
import torch.nn as nn
import torch.nn.functional as F
import numpy as np
import einops

# ============================================================
# XPOS (original — used for encoder, which exports with dynamo=True)
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
# PrecomputedXPOS — replaces dynamic arange with table lookups
# ============================================================
# Position range analysis for MAX_SEQ=255:
#   min_pos = -(length + offset) // 2
#   The worst case: length=255, offset=254 (step=0) → min_pos = -254
#   max_pos = length + offset + min_pos, worst case: offset=254 → max_pos = 127
#   Full range: [-254, 127] → need P_BASE = 254
#   Scale table: 2*254+1 = 509 entries
#   Sin/Cos table: max rows = (2*MAX_SEQ)//2 + 1 = 255 entries
P_BASE = 254
P_TABLE_SIZE = 2 * P_BASE + 1  # 509
SINCOS_TABLE_SIZE = P_BASE + 1  # 255 (max number of rows in scale tensor)

def compute_xpos_tables(head_dim, scale_base, scale_param):
    """Precompute scale, sin, cos lookup tables for XPOS."""
    # Scale table: scale_table[p + P_BASE, d] = scale_param[d] ^ (p / scale_base)
    positions = torch.arange(-P_BASE, P_BASE + 1, dtype=torch.float32)
    scale_table = scale_param.float().unsqueeze(0) ** (positions.unsqueeze(1) / scale_base)

    # Sin/Cos table: sincos_table[i, d] = sin(i * inv_freq[d]) / cos(i * inv_freq[d])
    dim = head_dim // 2
    inv_freq = 1.0 / (10000 ** (torch.arange(0, dim, dtype=torch.float32) / dim))
    indices = torch.arange(0, SINCOS_TABLE_SIZE, dtype=torch.float32)
    sinusoid_inp = torch.einsum("i,j->ij", indices, inv_freq)
    sin_table = torch.sin(sinusoid_inp)
    cos_table = torch.cos(sinusoid_inp)

    return scale_table, sin_table, cos_table


class PrecomputedXPOS(nn.Module):
    """
    XPOS with precomputed lookup tables. Eliminates all dynamic torch.arange
    and power operations. Uses index_select for step-dependent lookups,
    which is fully compatible with TorchScript tracing and ONNX export.
    """
    def __init__(self, head_dim, scale_base, scale_param):
        super().__init__()
        self.head_dim = head_dim
        self.scale_base = scale_base
        self.dim_half = head_dim // 2

        scale_table, sin_table, cos_table = compute_xpos_tables(
            head_dim, scale_base, scale_param
        )
        self.register_buffer("scale_table", scale_table)  # [255, dim_half]
        self.register_buffer("sin_table", sin_table)       # [128, dim_half]
        self.register_buffer("cos_table", cos_table)       # [128, dim_half]

    def forward(self, x, offset=0, downscale=False):
        length = x.shape[1]

        # Scale table uses POSITION indices: table[i] corresponds to position (i - P_BASE)
        # Sin/Cos table uses ROW indices: table[j] corresponds to fixed_pos_embedding row j
        # For a given scale_table slice [table_start : table_start+length],
        # the corresponding sin/cos rows are [0 : length]

        if isinstance(offset, torch.Tensor):
            total = length + offset.long()
            half_total = total // 2
            table_start = (P_BASE - half_total).long()
            # Dynamic indexing for step-dependent scale lookup
            scale_idx = torch.arange(length, device=x.device) + table_start.unsqueeze(0)
            scale = self.scale_table.index_select(0, scale_idx.reshape(-1)).reshape(length, self.dim_half).to(x.dtype)
            # Sin/cos use simple 0..length-1 row indices
            sincos_idx = torch.arange(length, device=x.device)
            sin_val = self.sin_table.index_select(0, sincos_idx).to(x.dtype)
            cos_val = self.cos_table.index_select(0, sincos_idx).to(x.dtype)
        else:
            table_start = P_BASE - (length + offset) // 2
            scale = self.scale_table[table_start:table_start + length].to(x.dtype)
            sin_val = self.sin_table[:length].to(x.dtype)
            cos_val = self.cos_table[:length].to(x.dtype)

        if downscale:
            scale = 1.0 / scale

        return apply_rotary_pos_emb(x, sin_val, cos_val, scale)


def inject_precomputed_xpos(model):
    """Replace XPOS instances in decoder layers with PrecomputedXPOS."""
    head_dim = EMB_DIM // NHEAD  # 80
    scale_base = EMB_DIM         # 320
    for layer in model.decoder_layers:
        for attn in [layer.self_attn, layer.multihead_attn]:
            old_xpos = attn.xpos
            scale_param = old_xpos.scale  # [head_dim//2]
            new_xpos = PrecomputedXPOS(head_dim, scale_base, scale_param)
            attn.xpos = new_xpos


# ============================================================
# ConvNeXt Backbone
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
# XPOS Multihead Attention
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
        # Additive attention mask BEFORE softmax (0 for valid, -inf for invalid)
        if attn_mask is not None:
            attn_weights = attn_weights + attn_mask
        if key_padding_mask is not None:
            attn_weights = attn_weights.view(bsz, self.num_heads, tgt_len, src_len)
            kpm = key_padding_mask.unsqueeze(1).unsqueeze(2).to(torch.bool)
            attn_weights = attn_weights.masked_fill(kpm, float("-inf"))
            attn_weights = attn_weights.view(bsz*self.num_heads, tgt_len, src_len)
        attn_weights = F.softmax(attn_weights, dim=-1, dtype=torch.float32).type_as(attn_weights)
        attn = torch.bmm(attn_weights, v)
        attn = attn.transpose(0,1).reshape(tgt_len, bsz, embed_dim).transpose(0,1)
        return self.out_proj(attn), None

# ============================================================
# Encoder/Decoder Layers
# ============================================================
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
# Export Wrapper 1: ENCODER
# ============================================================
class EncoderWrapper(nn.Module):
    """
    Inputs:  img [N,3,48,W], img_widths [N]
    Outputs: memory [N,W',320], input_mask [N,W']
    """
    def __init__(self, model):
        super().__init__()
        self.model = model
    def forward(self, img, img_widths):
        memory = self.model.backbone(img)
        memory = einops.rearrange(memory, 'N C 1 W -> N W C')
        W_mem = memory.size(1)
        valid_lengths = (img_widths + 3) // 4 + 2
        positions = torch.arange(W_mem, device=img.device).unsqueeze(0)
        input_mask = positions >= valid_lengths.unsqueeze(1)
        for layer in self.model.encoder_layers:
            memory = layer(memory, src_key_padding_mask=input_mask)
        return memory, input_mask

# ============================================================
# Export Wrapper 2: DECODER STEP
# ============================================================
class DecoderStepWrapper(nn.Module):
    """
    Single decoder step for autoregressive inference.
    Uses MASKED ATTENTION (read full cache, mask invalid positions) and
    PRECOMPUTED XPOS tables to ensure ONNX tracing compatibility at all steps.

    Inputs:
        token_ids:   [N]        int64
        step:        []         int64 scalar
        memory:      [N,W',320] float
        memory_mask: [N,W']     bool
        cache_flat:  [N*6, max_seq, 320] float

    Outputs:
        logits:       [N, dict_size]
        fg_colors:    [N, 3]
        bg_colors:    [N, 3]
        fg_indicators:[N, 2]
        bg_indicators:[N, 2]
        cache_flat:   [N*6, max_seq, 320]
    """
    def __init__(self, model, max_seq=MAX_SEQ):
        super().__init__()
        self.model = model
        self.max_seq = max_seq
        self.n_layers = N_DECODERS

        # Precompute ADDITIVE attention masks: mask[step, pos] = 0 if valid, -inf if invalid
        # Applied BEFORE softmax so masked positions become exactly 0 after softmax
        masks = torch.full((max_seq, max_seq), float('-inf'))
        for s in range(max_seq):
            masks[s, :s+1] = 0.0
        self.register_buffer("attn_masks", masks)

    def forward(self, token_ids, step, memory, memory_mask, cache_flat):
        N = token_ids.shape[0]
        E = EMB_DIM
        n_lp1 = self.n_layers + 1  # 6

        # Reshape cache: [N*6, max_seq, E] -> [N, 6, max_seq, E]
        cache = cache_flat.view(N, n_lp1, self.max_seq, E)

        # step as scalar tensor for index operations
        s = step if step.dim() == 0 else step[0]

        # Embed current token
        tgt = self.model.embd(token_ids).unsqueeze(1)  # [N, 1, E]

        batch_idx = torch.arange(N, device=cache.device)

        # Select attention mask for this step: [1, max_seq]
        # Additive mask: 0.0 for valid positions, -inf for invalid
        step_idx = s.long().unsqueeze(0)  # [1]
        attn_mask = self.attn_masks.index_select(0, step_idx)  # [1, max_seq]

        # Compute XPOS key offset for correct position range
        # This matches the original model's position semantics:
        #   Original self-attn at step s: key length = s+1, offset=0
        #   Masked: key length = MAX_SEQ, offset = MAX_SEQ-1-s
        #   Both produce position range [-(s+1)//2, ...]
        key_offset = self.max_seq - 1 - s.long()

        # Run decoder layers
        for l, layer in enumerate(self.model.decoder_layers):
            # Write current token to cache BEFORE reading (so it's included in the key)
            cache.index_put_(
                (batch_idx, torch.tensor(l, device=cache.device), s),
                tgt.squeeze(1)
            )

            # Read FULL cache for this layer: [N, max_seq, E]
            full_key = cache[:, l, :, :]

            # Self-attention with masked key/value
            sa = layer.self_attn(
                layer.norm1(tgt),           # query: [N, 1, E]
                layer.norm1(full_key),      # key: [N, max_seq, E]
                layer.norm1(full_key),      # value: [N, max_seq, E]
                attn_mask=attn_mask,        # [1, max_seq] additive mask
                q_offset=s,                 # query position = step
                k_offset=key_offset,        # key offset for correct XPOS range
            )[0]
            tgt = tgt + sa

            # Cross-attention (no attn_mask, uses encoder output)
            ca = layer.multihead_attn(
                layer.norm2(tgt), memory, memory,
                key_padding_mask=memory_mask, q_offset=s
            )[0]
            tgt = tgt + ca

            tgt = tgt + layer._ff_block(layer.norm3(tgt))

        # Store final layer output in cache
        cache.index_put_(
            (batch_idx, torch.tensor(self.n_layers, device=cache.device), s),
            tgt.squeeze(1)
        )
        cache_flat_out = cache.view(N * n_lp1, self.max_seq, E)

        decoded = tgt.squeeze(1)  # [N, E]
        char_logits = self.model.pred(self.model.pred1(decoded))
        color_feats = self.model.color_pred1(decoded)
        fg_c = self.model.color_pred_fg(color_feats)
        bg_c = self.model.color_pred_bg(color_feats)
        fg_i = self.model.color_pred_fg_ind(color_feats)
        bg_i = self.model.color_pred_bg_ind(color_feats)

        return char_logits, fg_c, bg_c, fg_i, bg_i, cache_flat_out


# ============================================================
# Helper: PyTorch decoder step (for verification, uses PrecomputedXPOS)
# ============================================================
def model_decode_step(model, token_ids, step, memory, memory_mask, cache):
    """PyTorch reference decoder step using masked attention + PrecomputedXPOS."""
    N = token_ids.shape[0]
    s = step.item() if step.dim() == 0 else step[0].item()
    key_offset = MAX_SEQ - 1 - s
    tgt = model.embd(token_ids).unsqueeze(1)

    # Additive attention mask: 0.0 for valid, -inf for invalid
    attn_mask = torch.full((1, MAX_SEQ), float('-inf'), device=cache.device)
    attn_mask[0, :s+1] = 0.0

    for l, layer in enumerate(model.decoder_layers):
        cache[:, l, s, :] = tgt.squeeze(1)
        full_key = cache[:, l, :, :]
        tgt = tgt + layer.self_attn(
            layer.norm1(tgt), layer.norm1(full_key), layer.norm1(full_key),
            attn_mask=attn_mask, q_offset=s, k_offset=key_offset
        )[0]
        tgt = tgt + layer.multihead_attn(
            layer.norm2(tgt), memory, memory,
            key_padding_mask=memory_mask, q_offset=s
        )[0]
        tgt = tgt + layer._ff_block(layer.norm3(tgt))
    cache[:, N_DECODERS, s, :] = tgt.squeeze(1)
    decoded = tgt.squeeze(1)
    logits = model.pred(model.pred1(decoded))
    cfeats = model.color_pred1(decoded)
    return logits, model.color_pred_fg(cfeats), model.color_pred_bg(cfeats), \
           model.color_pred_fg_ind(cfeats), model.color_pred_bg_ind(cfeats), cache


# ============================================================
# Main
# ============================================================
def main():
    project_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    python_web_dir = os.path.join(project_root, 'python-web')

    model_path = os.path.join(python_web_dir, 'models', 'ocr', 'ocr_ar_48px.ckpt')
    dict_path = os.path.join(python_web_dir, 'models', 'ocr', 'alphabet-all-v7.txt')
    output_dir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(project_root, 'models')
    enc_path = os.path.join(output_dir, 'ocr_ar_48px_encoder.onnx')
    dec_path = os.path.join(output_dir, 'ocr_ar_48px_decoder.onnx')

    # Fallback paths
    for base in [
        os.path.join(project_root, 'python-web'),
        os.path.join(project_root, 'models'),
        r'E:\yhz\Projects\manga-image-translator-app\python-web',
        r'D:\manga-image-translator\manga-image-translator',
    ]:
        if not os.path.exists(model_path):
            model_path = os.path.join(base, 'models', 'ocr', 'ocr_ar_48px.ckpt')
        if not os.path.exists(dict_path):
            dict_path = os.path.join(base, 'models', 'ocr', 'alphabet-all-v7.txt')

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

    N_test, W_test = 1, 128
    dummy_img = torch.randn(N_test, 3, 48, W_test)
    dummy_widths = torch.tensor([W_test], dtype=torch.long)

    # ============================================================
    # PART 1: Export Encoder (uses original XPOS with dynamo=True)
    # ============================================================
    print("\n" + "="*60)
    print("PART 1: Exporting Encoder ONNX")
    print("="*60)

    enc_wrapper = EncoderWrapper(model)
    enc_wrapper.eval()

    with torch.no_grad():
        pt_memory, pt_mask = enc_wrapper(dummy_img, dummy_widths)
    print(f"PyTorch encoder: memory {list(pt_memory.shape)}, mask {list(pt_mask.shape)}")

    os.makedirs(output_dir, exist_ok=True)
    torch.onnx.export(
        enc_wrapper, (dummy_img, dummy_widths), enc_path,
        input_names=['img', 'img_widths'],
        output_names=['memory', 'input_mask'],
        dynamic_axes={
            'img': {0: 'batch', 3: 'width'},
            'img_widths': {0: 'batch'},
            'memory': {0: 'batch', 1: 'feat_len'},
            'input_mask': {0: 'batch', 1: 'feat_len'},
        },
        opset_version=18, dynamo=True,
    )
    enc_mb = os.path.getsize(enc_path) / 1024 / 1024
    print(f"Encoder ONNX: {enc_mb:.1f} MB -> {enc_path}")

    # ============================================================
    # Inject PrecomputedXPOS into decoder layers for decoder export
    # ============================================================
    print("\nInjecting PrecomputedXPOS into decoder layers...")
    inject_precomputed_xpos(model)
    print("Done. Decoder XPOS now uses precomputed lookup tables.")

    # ============================================================
    # PART 2: Export Decoder Step
    # ============================================================
    print("\n" + "="*60)
    print("PART 2: Exporting Decoder Step ONNX")
    print("="*60)

    dec_wrapper = DecoderStepWrapper(model, max_seq=MAX_SEQ)
    dec_wrapper.eval()

    dummy_token_ids = torch.ones(N_test, dtype=torch.long)  # start_tok=1
    dummy_step = torch.tensor(0, dtype=torch.long)  # scalar
    dummy_cache = torch.zeros(N_test * (N_DECODERS+1), MAX_SEQ, EMB_DIM)

    with torch.no_grad():
        pt_dec = dec_wrapper(dummy_token_ids, dummy_step, pt_memory, pt_mask, dummy_cache)
    print(f"PyTorch decoder step:")
    print(f"  logits: {list(pt_dec[0].shape)}, fg: {list(pt_dec[1].shape)}, cache: {list(pt_dec[5].shape)}")

    # Use dynamo=False (TorchScript tracing) for decoder step.
    # PrecomputedXPOS ensures all step-dependent operations use table lookups
    # (index_select) instead of dynamic arange, so shapes are NOT baked.
    torch.onnx.export(
        dec_wrapper,
        (dummy_token_ids, dummy_step, pt_memory, pt_mask, dummy_cache),
        dec_path,
        input_names=['token_ids', 'step', 'memory', 'memory_mask', 'cache_flat'],
        output_names=['logits', 'fg_colors', 'bg_colors', 'fg_indicators', 'bg_indicators', 'cache_flat_out'],
        dynamic_axes={
            'token_ids': {0: 'batch'},
            'step': {},
            'memory': {0: 'batch', 1: 'feat_len'},
            'memory_mask': {0: 'batch', 1: 'feat_len'},
            'cache_flat': {0: 'batch_x_layers'},
            'logits': {0: 'batch'},
            'fg_colors': {0: 'batch'},
            'bg_colors': {0: 'batch'},
            'fg_indicators': {0: 'batch'},
            'bg_indicators': {0: 'batch'},
            'cache_flat_out': {0: 'batch_x_layers'},
        },
        opset_version=18, dynamo=False,
    )
    dec_mb = os.path.getsize(dec_path) / 1024 / 1024
    print(f"Decoder ONNX: {dec_mb:.1f} MB -> {dec_path}")

    # ============================================================
    # PART 3: Verification
    # ============================================================
    print("\n" + "="*60)
    print("PART 3: Verification")
    print("="*60)

    import onnx, onnxruntime as ort

    onnx.checker.check_model(onnx.load(enc_path))
    print("Encoder ONNX checker: PASSED")
    onnx.checker.check_model(onnx.load(dec_path))
    print("Decoder ONNX checker: PASSED")

    enc_sess = ort.InferenceSession(enc_path, providers=['CPUExecutionProvider'])
    dec_sess = ort.InferenceSession(dec_path, providers=['CPUExecutionProvider'])

    # Verify encoder
    ort_enc = enc_sess.run(None, {'img': dummy_img.numpy(), 'img_widths': dummy_widths.numpy()})
    enc_diff = np.abs(pt_memory.detach().numpy() - ort_enc[0]).max()
    print(f"\nEncoder ORT vs PyTorch: memory max_diff={enc_diff:.8f} {'OK' if enc_diff < 1e-4 else 'WARN'}")

    # Verify decoder step at step=0
    ort_dec = dec_sess.run(None, {
        'token_ids': dummy_token_ids.detach().numpy(), 'step': dummy_step.detach().numpy(),
        'memory': ort_enc[0], 'memory_mask': ort_enc[1], 'cache_flat': dummy_cache.detach().numpy(),
    })
    dec_names = ['logits', 'fg_colors', 'bg_colors', 'fg_indicators', 'bg_indicators', 'cache']
    print(f"Decoder step 0 ORT vs PyTorch:")
    for name, pt_o, ort_o in zip(dec_names, pt_dec, ort_dec):
        diff = np.abs(pt_o.detach().numpy() - ort_o).max()
        print(f"  {name}: max_diff={diff:.8f} {'OK' if diff < 1e-4 else 'WARN'}")

    # ============================================================
    # PART 4: Multi-step verification (the critical test!)
    # ============================================================
    print("\n" + "="*60)
    print("PART 4: Multi-step forced decoding test")
    print("="*60)

    NUM_STEPS = 10
    torch.manual_seed(123)
    forced_tokens = [1]  # START
    for _ in range(NUM_STEPS - 1):
        forced_tokens.append(int(torch.randint(3, min(200, dict_size), (1,)).item()))
    print(f"Forced tokens: {forced_tokens}")

    # PyTorch multi-step (with PrecomputedXPOS)
    cache_pt = torch.zeros(N_test, N_DECODERS + 1, MAX_SEQ, EMB_DIM)
    pt_logits_all = []
    with torch.no_grad():
        for si in range(NUM_STEPS):
            tok = torch.tensor([forced_tokens[si]], dtype=torch.long)
            s = torch.tensor(si, dtype=torch.long)
            logits, *_rest, cache_pt = model_decode_step(
                model, tok, s, pt_memory, pt_mask, cache_pt
            )
            pt_logits_all.append(logits.detach().clone().numpy())

    # ONNX multi-step
    cache_ort = np.zeros((N_test * (N_DECODERS + 1), MAX_SEQ, EMB_DIM), dtype=np.float32)
    ort_logits_all = []
    for si in range(NUM_STEPS):
        tok_ort = np.array([forced_tokens[si]], dtype=np.int64)
        step_ort = np.array(si, dtype=np.int64)
        out = dec_sess.run(None, {
            'token_ids': tok_ort, 'step': step_ort,
            'memory': ort_enc[0], 'memory_mask': ort_enc[1], 'cache_flat': cache_ort,
        })
        ort_logits_all.append(out[0].copy())
        cache_ort = out[5]

    # Compare at each step
    print(f"\n{'Step':>4} {'Max Diff':>12} {'Argmax Match':>14}")
    print("-" * 34)
    all_ok = True
    for si in range(NUM_STEPS):
        diff = np.abs(pt_logits_all[si] - ort_logits_all[si]).max()
        pt_top = int(np.argmax(pt_logits_all[si]))
        ort_top = int(np.argmax(ort_logits_all[si]))
        match = pt_top == ort_top
        status = "OK" if diff < 0.05 else "FAIL"
        if not match or diff >= 0.05:
            all_ok = False
        print(f"  {si:2d}   {diff:12.6f}   {'Yes' if match else 'NO':>14}  {status}")

    print(f"\nAll steps OK: {all_ok}")

    # ============================================================
    # PART 5: End-to-end greedy decoding
    # ============================================================
    print("\n" + "="*60)
    print("PART 5: End-to-end greedy decoding test")
    print("="*60)

    START_TOK, END_TOK = 1, 2

    # PyTorch greedy
    with torch.no_grad():
        cache_pt2 = torch.zeros(N_test, N_DECODERS+1, MAX_SEQ, EMB_DIM)
        tokens = torch.full((N_test,), START_TOK, dtype=torch.long)
        seq_pt = []
        for si in range(50):
            s = torch.tensor(si, dtype=torch.long)
            logits, *_rest, cache_pt2 = model_decode_step(model, tokens, s, pt_memory, pt_mask, cache_pt2)
            pred = logits.argmax(dim=-1)
            seq_pt.append(pred[0].item())
            if pred[0].item() == END_TOK: break
            tokens = pred
    print(f"PyTorch greedy: {len(seq_pt)} tokens -> {seq_pt[:15]}")

    # ONNX greedy
    cache_ort2 = np.zeros((N_test*(N_DECODERS+1), MAX_SEQ, EMB_DIM), dtype=np.float32)
    tok_ort = np.array([START_TOK], dtype=np.int64)
    seq_ort = []
    for si in range(50):
        step_ort = np.array(si, dtype=np.int64)
        out = dec_sess.run(None, {
            'token_ids': tok_ort, 'step': step_ort,
            'memory': ort_enc[0], 'memory_mask': ort_enc[1], 'cache_flat': cache_ort2,
        })
        logits_ort = out[0]
        cache_ort2 = out[5]
        pred = int(np.argmax(logits_ort, axis=-1)[0])
        seq_ort.append(pred)
        if pred == END_TOK: break
        tok_ort = np.array([pred], dtype=np.int64)
    print(f"ONNX   greedy: {len(seq_ort)} tokens -> {seq_ort[:15]}")

    # Compare
    match = seq_pt == seq_ort
    print(f"Sequences {'MATCH' if match else 'DIFFER'}!")
    pt_txt = ''.join(dictionary[i] for i in seq_pt if i < dict_size and dictionary[i] not in ['<S>', '</S>'])
    ort_txt = ''.join(dictionary[i] for i in seq_ort if i < dict_size and dictionary[i] not in ['<S>', '</S>'])
    print(f"PyTorch text: {pt_txt}")
    print(f"ONNX   text: {ort_txt}")

    print(f"\n{'='*60}")
    print(f"SUMMARY")
    print(f"{'='*60}")
    print(f"Encoder ONNX: {enc_path} ({enc_mb:.1f} MB)")
    print(f"Decoder ONNX: {dec_path} ({dec_mb:.1f} MB)")
    print(f"Total:        {enc_mb + dec_mb:.1f} MB")
    print(f"Multi-step test: {'PASSED' if all_ok else 'FAILED'}")
    print(f"Greedy match:    {'YES' if match else 'NO'}")
    print("Done!")


if __name__ == '__main__':
    main()
