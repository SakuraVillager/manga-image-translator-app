"""
Fix and re-export OCR CTC ONNX model with FULLY dynamic shape support.

PROBLEM:
  torch.onnx.export() traces view() calls in nn.MultiheadAttention with static
  shapes, producing Reshape nodes with hardcoded dimensions. At runtime with
  different input widths, the Reshape fails because element counts don''t match.

FIX:
  Replace nn.MultiheadAttention with DynamicMultiheadAttention that uses
  manual QKV projections and ONNX-friendly reshape(B, S, H, D) patterns.
  This ensures all Reshape nodes use dynamically-computed shapes.

Usage:
  python scripts/fix_onnx_export.py
'''

import os
import sys
import math

import torch
import torch.nn as nn
import torch.nn.functional as F
import numpy as np


class DynamicMultiheadAttention(nn.Module):
    def __init__(self, embed_dim, num_heads, dropout=0.0, batch_first=True):
        super().__init__()
        self.embed_dim = embed_dim
        self.num_heads = num_heads
        self.head_dim = embed_dim // num_heads
        self.batch_first = batch_first
        self.dropout = dropout
        assert self.head_dim * num_heads == embed_dim
        self.q_proj = nn.Linear(embed_dim, embed_dim, bias=True)
        self.k_proj = nn.Linear(embed_dim, embed_dim, bias=True)
        self.v_proj = nn.Linear(embed_dim, embed_dim, bias=True)
        self.out_proj = nn.Linear(embed_dim, embed_dim, bias=True)

    def forward(self, query, key, value, attn_mask=None, key_padding_mask=None, need_weights=False):
        B, S, E = query.shape
        q = self.q_proj(query)
        k = self.k_proj(key)
        v = self.v_proj(value)
        q = q.reshape(B, S, self.num_heads, self.head_dim)
        k = k.reshape(B, S, self.num_heads, self.head_dim)
        v = v.reshape(B, S, self.num_heads, self.head_dim)
        q = q.transpose(1, 2)
        k = k.transpose(1, 2)
        v = v.transpose(1, 2)
        scale = self.head_dim ** -0.5
        q = q * scale
        attn_weights = torch.matmul(q, k.transpose(-2, -1))
        if attn_mask is not None:
            attn_weights = attn_weights + attn_mask
        if key_padding_mask is not None:
            attn_weights = attn_weights.masked_fill(
                key_padding_mask.unsqueeze(1).unsqueeze(2), float("-inf")
            )
        attn_weights = F.softmax(attn_weights, dim=-1)
        attn_weights = F.dropout(attn_weights, p=self.dropout, training=self.training)
        attn_output = torch.matmul(attn_weights, v)
        attn_output = attn_output.transpose(1, 2)
        attn_output = attn_output.reshape(B, S, self.embed_dim)
        attn_output = self.out_proj(attn_output)
        if need_weights:
            return attn_output, attn_weights
        return attn_output, None


class PositionalEncoding(nn.Module):
    def __init__(self, d_model, dropout=0.1, max_len=5000):
        super().__init__()
        self.dropout = nn.Dropout(p=dropout)
        pe = torch.zeros(max_len, d_model)
        position = torch.arange(0, max_len, dtype=torch.float).unsqueeze(1)
        div_term = torch.exp(torch.arange(0, d_model, 2).float() * (-math.log(10000.0) / d_model))
        pe[:, 0::2] = torch.sin(position * div_term)
        pe[:, 1::2] = torch.cos(position * div_term)
        pe = pe.unsqueeze(0)
        self.register_buffer("pe", pe)

    def forward(self, x, offset=0):
        x = x + self.pe[:, offset: offset + x.size(1), :]
        return x


class CustomTransformerEncoderLayer(nn.Module):
    def __init__(self, d_model, nhead, dim_feedforward=2048, dropout=0.1, activation="gelu",
                 layer_norm_eps=1e-5, batch_first=False, norm_first=False, device=None, dtype=None):
        factory_kwargs = {"device": device, "dtype": dtype}
        super().__init__()
        self.self_attn = DynamicMultiheadAttention(d_model, nhead, dropout=dropout, batch_first=batch_first)
        self.linear1 = nn.Linear(d_model, dim_feedforward, **factory_kwargs)
        self.dropout = nn.Dropout(dropout)
        self.linear2 = nn.Linear(dim_feedforward, d_model, **factory_kwargs)
        self.norm_first = norm_first
        self.norm1 = nn.LayerNorm(d_model, eps=layer_norm_eps, **factory_kwargs)
        self.norm2 = nn.LayerNorm(d_model, eps=layer_norm_eps, **factory_kwargs)
        self.dropout1 = nn.Dropout(dropout)
        self.dropout2 = nn.Dropout(dropout)
        self.pe = PositionalEncoding(d_model, max_len=2048)
        self.activation = F.gelu

    def forward(self, src, src_mask=None, src_key_padding_mask=None, is_causal=None):
        x = src
        if self.norm_first:
            x = x + self._sa_block(self.norm1(x), src_mask, src_key_padding_mask)
            x = x + self._ff_block(self.norm2(x))
        else:
            x = self.norm1(x + self._sa_block(x, src_mask, src_key_padding_mask))
            x = self.norm2(x + self._ff_block(x))
        return x

    def _sa_block(self, x, attn_mask=None, key_padding_mask=None):
        x = self.self_attn(self.pe(x), self.pe(x), x, attn_mask=attn_mask, key_padding_mask=key_padding_mask, need_weights=False)[0]
        return self.dropout1(x)

    def _ff_block(self, x):
        x = self.linear2(self.dropout(self.activation(self.linear1(x))))
        return self.dropout2(x)


class BasicBlock(nn.Module):
    expansion = 1
    def __init__(self, inplanes, planes, stride=1, downsample=None):
        super().__init__()
        self.bn1 = nn.BatchNorm2d(inplanes)
        self.conv1 = self._conv3x3(inplanes, planes)
        self.bn2 = nn.BatchNorm2d(planes)
        self.conv2 = self._conv3x3(planes, planes)
        self.downsample = downsample
        self.stride = stride

    def _conv3x3(self, in_planes, out_planes, stride=1):
        return nn.Conv2d(in_planes, out_planes, kernel_size=3, stride=stride, padding=1, bias=False)

    def forward(self, x):
        residual = x
        out = self.bn1(x); out = F.relu(out)
        out = self.conv1(out)
        out = self.bn2(out); out = F.relu(out)
        out = self.conv2(out)
        if self.downsample is not None:
            residual = self.downsample(residual)
        return out + residual


class ResNet(nn.Module):
    def __init__(self, input_channel, output_channel, block, layers):
        super().__init__()
        self.output_channel_block = [int(output_channel / 4), int(output_channel / 2), output_channel, output_channel]
        self.inplanes = int(output_channel / 8)
        self.conv0_1 = nn.Conv2d(input_channel, int(output_channel / 8), kernel_size=3, stride=1, padding=1, bias=False)
        self.bn0_1 = nn.BatchNorm2d(int(output_channel / 8))
        self.conv0_2 = nn.Conv2d(int(output_channel / 8), self.inplanes, kernel_size=3, stride=1, padding=1, bias=False)
        self.maxpool1 = nn.AvgPool2d(kernel_size=2, stride=2, padding=0)
        self.layer1 = self._make_layer(block, self.output_channel_block[0], layers[0])
        self.bn1 = nn.BatchNorm2d(self.output_channel_block[0])
        self.conv1 = nn.Conv2d(self.output_channel_block[0], self.output_channel_block[0], kernel_size=3, stride=1, padding=1, bias=False)
        self.maxpool2 = nn.AvgPool2d(kernel_size=2, stride=2, padding=0)
        self.layer2 = self._make_layer(block, self.output_channel_block[1], layers[1], stride=1)
        self.bn2 = nn.BatchNorm2d(self.output_channel_block[1])
        self.conv2 = nn.Conv2d(self.output_channel_block[1], self.output_channel_block[1], kernel_size=3, stride=1, padding=1, bias=False)
        self.maxpool3 = nn.AvgPool2d(kernel_size=2, stride=(2, 1), padding=(0, 1))
        self.layer3 = self._make_layer(block, self.output_channel_block[2], layers[2], stride=1)
        self.bn3 = nn.BatchNorm2d(self.output_channel_block[2])
        self.conv3 = nn.Conv2d(self.output_channel_block[2], self.output_channel_block[2], kernel_size=3, stride=1, padding=1, bias=False)
        self.layer4 = self._make_layer(block, self.output_channel_block[3], layers[3], stride=1)
        self.bn4_1 = nn.BatchNorm2d(self.output_channel_block[3])
        self.conv4_1 = nn.Conv2d(self.output_channel_block[3], self.output_channel_block[3], kernel_size=3, stride=(2, 1), padding=(1, 1), bias=False)
        self.bn4_2 = nn.BatchNorm2d(self.output_channel_block[3])
        self.conv4_2 = nn.Conv2d(self.output_channel_block[3], self.output_channel_block[3], kernel_size=3, stride=1, padding=0, bias=False)
        self.bn4_3 = nn.BatchNorm2d(self.output_channel_block[3])

    def _make_layer(self, block, planes, blocks, stride=1):
        downsample = None
        if stride != 1 or self.inplanes != planes * block.expansion:
            downsample = nn.Sequential(nn.BatchNorm2d(self.inplanes), nn.Conv2d(self.inplanes, planes * block.expansion, kernel_size=1, stride=stride, bias=False))
        layers = [block(self.inplanes, planes, stride, downsample)]
        self.inplanes = planes * block.expansion
        for i in range(1, blocks):
            layers.append(block(self.inplanes, planes))
        return nn.Sequential(*layers)

    def forward(self, x):
        x = self.conv0_1(x); x = self.bn0_1(x); x = F.relu(x)
        x = self.conv0_2(x); x = self.maxpool1(x)
        x = self.layer1(x); x = self.bn1(x); x = F.relu(x)
        x = self.conv1(x); x = self.maxpool2(x)
        x = self.layer2(x); x = self.bn2(x); x = F.relu(x)
        x = self.conv2(x); x = self.maxpool3(x)
        x = self.layer3(x); x = self.bn3(x); x = F.relu(x)
        x = self.conv3(x); x = self.layer4(x)
        x = self.bn4_1(x); x = F.relu(x)
        x = self.conv4_1(x); x = self.bn4_2(x); x = F.relu(x)
        x = self.conv4_2(x); x = self.bn4_3(x)
        return x


class ResNet_FeatureExtractor(nn.Module):
    def __init__(self, input_channel, output_channel=128):
        super().__init__()
        self.ConvNet = ResNet(input_channel, output_channel, BasicBlock, [4, 6, 8, 6, 3])

    def forward(self, input):
        return self.ConvNet(input)


class OCR(nn.Module):
    def __init__(self, dictionary, max_len):
        super().__init__()
        self.max_len = max_len
        self.dictionary = dictionary
        self.dict_size = len(dictionary)
        self.backbone = ResNet_FeatureExtractor(3, 320)
        enc = CustomTransformerEncoderLayer(320, 8, 320 * 4, dropout=0.05, batch_first=True, norm_first=True)
        self.encoders = nn.TransformerEncoder(enc, 3)
        self.char_pred_norm = nn.Sequential(nn.LayerNorm(320), nn.Dropout(0.1), nn.GELU())
        self.char_pred = nn.Linear(320, self.dict_size)
        self.color_pred1 = nn.Sequential(nn.Linear(320, 6))

    def forward(self, img):
        feats = self.backbone(img).squeeze(2)
        feats = self.encoders(feats.permute(0, 2, 1))
        pred_char_logits = self.char_pred(self.char_pred_norm(feats))
        pred_color_values = self.color_pred1(feats)
        return pred_char_logits, pred_color_values


def split_qkv_weights(sd, embed_dim=320):
    new_sd = {}
    for k, v in sd.items():
        new_k = k.replace("self_attn.in_proj_weight", "self_attn.q_proj.weight")
        new_k = new_k.replace("self_attn.in_proj_bias", "self_attn.q_proj.bias")
        new_k = new_k.replace("self_attn.out_proj.weight", "self_attn.out_proj.weight")
        new_k = new_k.replace("self_attn.out_proj.bias", "self_attn.out_proj.bias")
        new_sd[new_k] = v
    keys_to_add = {}
    for k in list(new_sd.keys()):
        if "q_proj.weight" in k and new_sd[k].shape == (3 * embed_dim, embed_dim):
            w = new_sd[k]
            prefix = k.replace(".q_proj.weight", "")
            keys_to_add[f"{prefix}.q_proj.weight"] = w[:embed_dim]
            keys_to_add[f"{prefix}.k_proj.weight"] = w[embed_dim:2*embed_dim]
            keys_to_add[f"{prefix}.v_proj.weight"] = w[2*embed_dim:]
            del new_sd[k]
        elif "q_proj.bias" in k and new_sd[k].shape == (3 * embed_dim,):
            b = new_sd[k]
            prefix = k.replace(".q_proj.bias", "")
            keys_to_add[f"{prefix}.q_proj.bias"] = b[:embed_dim]
            keys_to_add[f"{prefix}.k_proj.bias"] = b[embed_dim:2*embed_dim]
            keys_to_add[f"{prefix}.v_proj.bias"] = b[2*embed_dim:]
            del new_sd[k]
    new_sd.update(keys_to_add)
    return new_sd


def main():
    PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    CKPT_PATH = os.path.join(PROJECT_ROOT, "models", "ocr", "ocr-ctc.ckpt")
    DICT_PATH = os.path.join(PROJECT_ROOT, "models", "ocr", "alphabet-all-v5.txt")
    OUTPUT_PATH = os.path.join(PROJECT_ROOT, "app", "src", "main", "assets", "models", "ocr_ctc_48px.onnx")

    for p in [CKPT_PATH, DICT_PATH]:
        if not os.path.exists(p):
            print(f"ERROR: {p} not found")
            sys.exit(1)

    with open(DICT_PATH, "r", encoding="utf-8") as fp:
        dictionary = [s[:-1] for s in fp.readlines()]
    print(f"Dictionary size: {len(dictionary)}")

    model = OCR(dictionary, 768)
    sd = torch.load(CKPT_PATH, map_location="cpu", weights_only=True)
    sd = sd["model"] if "model" in sd else sd
    for k in list(sd.keys()):
        if "pe.pe" in k:
            del sd[k]
    sd = split_qkv_weights(sd)
    model.load_state_dict(sd, strict=False)
    model.eval()
    print("Model loaded")

    with torch.no_grad():
        test_img = torch.randn(1, 3, 48, 128)
        logits_pt, colors_pt = model(test_img)
    print(f"Forward: logits={list(logits_pt.shape)}, colors={list(colors_pt.shape)}")

    tmp_path = OUTPUT_PATH + ".tmp"
    dummy_img = torch.randn(1, 3, 48, 128, dtype=torch.float32)
    with torch.inference_mode():
        torch.onnx.export(model, dummy_img, tmp_path,
            input_names=["img"], output_names=["logits", "colors"],
            dynamic_axes={"img": {0: "batch", 3: "width"}, "logits": {0: "batch", 1: "seq_len"}, "colors": {0: "batch", 1: "seq_len"}},
            opset_version=18, verbose=False)
    print("ONNX export done")

    import onnx
    model_onnx = onnx.load(tmp_path)
    onnx.save_model(model_onnx, OUTPUT_PATH, save_as_external_data=False)
    for p in [tmp_path, tmp_path + ".data"]:
        if os.path.exists(p): os.remove(p)

    m = onnx.load(OUTPUT_PATH)
    ext = sum(1 for init in m.graph.initializer if init.HasField("data_location") and init.data_location == onnx.TensorProto.EXTERNAL)
    print(f"External data: {ext}")

    # Verify no static reshape tensors
    static = False
    for init in m.graph.initializer:
        if init.data_type == 7:
            data = np.frombuffer(init.raw_data, dtype=np.int64).tolist()
            if len(data) in [1,2,3]:
                for node in m.graph.node:
                    if node.op_type == "Reshape" and init.name in node.input:
                        print(f"WARNING: Static shape tensor {init.name}={data}")
                        static = True
    if not static:
        print("No static reshape tensors - SUCCESS")

    import onnxruntime as ort
    session = ort.InferenceSession(OUTPUT_PATH)
    for w in [64, 100, 128, 200, 256, 400, 512, 768, 1024]:
        inp = {"img": np.random.randn(1, 3, 48, w).astype(np.float32)}
        logits, colors = session.run(None, inp)
        print(f"  w={w:4d}: logits={logits.shape[1]:3d} colors={colors.shape[1]:3d}")

    with torch.no_grad():
        test_img = torch.randn(1, 3, 48, 128)
        logits_pt, colors_pt = model(test_img)
    lo, co = session.run(None, {"img": test_img.numpy()})
    print(f"Max diff: logits={np.abs(logits_pt.numpy()-lo).max():.6f}, colors={np.abs(colors_pt.numpy()-co).max():.6f}")
    print(f"Done: {OUTPUT_PATH} ({os.path.getsize(OUTPUT_PATH)/1024/1024:.1f} MB)")


if __name__ == "__main__":
    main()
