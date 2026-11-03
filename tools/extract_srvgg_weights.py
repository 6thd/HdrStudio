#!/usr/bin/env python3
"""Convert a Real-ESRGAN SRVGGNetCompact (realesr-general-x4v3 architecture: 3->64, 32x(64->64)+PReLU,
64->48, PixelShuffle(4), + nearest-x4 skip) from ncnn .param/.bin into the flat float32 blob the
Android GLSL engine loads (assets/models/srvgg_general_x4v3.bin).

Blob layout (little-endian float32), for conv layer L = 0..33:
    weight[out][in][ky][kx], bias[out], then (L <= 32) prelu_slope[64]
The last conv's 48 output channels follow PyTorch PixelShuffle order: channel = c*16 + dy*4 + dx.

Usage: extract_srvgg_weights.py model.param model.bin out.bin [tfjs_model_dir_for_crosscheck]
"""
import json, struct, sys, hashlib
import numpy as np

def load_ncnn(param_path, bin_path):
    layers = []
    for line in open(param_path).read().split('\n')[2:]:
        t = line.split()
        if not t: continue
        nb, nt = int(t[2]), int(t[3])
        kv = dict((int(k), v) for k, v in (x.split('=') for x in t[4 + nb + nt:]))
        layers.append((t[0], kv))
    raw = open(bin_path, 'rb').read()
    off = 0; convs = []; prelus = []
    for typ, kv in layers:
        if typ == 'Convolution':
            n = int(kv[6]); oc = int(kv[0]); k = int(kv[1])
            tag = struct.unpack_from('<I', raw, off)[0]; off += 4
            if tag == 0x01306B47:   # fp16 storage
                w = np.frombuffer(raw, '<f2', n, off).astype(np.float32); off += n * 2
                off = (off + 3) & ~3
            elif tag == 0:
                w = np.frombuffer(raw, '<f4', n, off).copy(); off += n * 4
            else:
                raise SystemExit('unsupported ncnn weight tag %x' % tag)
            b = np.frombuffer(raw, '<f4', oc, off).copy(); off += oc * 4
            convs.append((w.reshape(oc, n // (oc * k * k), k, k), b))
        elif typ == 'PReLU':
            n = int(kv[0]); prelus.append(np.frombuffer(raw, '<f4', n, off).copy()); off += n * 4
    if off != len(raw): raise SystemExit('bin size mismatch %d != %d' % (off, len(raw)))
    if len(convs) != 34 or len(prelus) != 33: raise SystemExit('not a 32-conv SRVGGNetCompact')
    return convs, prelus

def write_blob(convs, prelus, out_path):
    with open(out_path, 'wb') as f:
        for i, (w, b) in enumerate(convs):
            f.write(np.ascontiguousarray(w, '<f4').tobytes()); f.write(np.ascontiguousarray(b, '<f4').tobytes())
            if i < 33: f.write(np.ascontiguousarray(prelus[i], '<f4').tobytes())
    data = open(out_path, 'rb').read()
    return len(data) // 4, hashlib.sha256(data).hexdigest()

def crosscheck_tfjs(convs, tfjs_dir):
    d = json.load(open(tfjs_dir + '/model.json'))
    blob = b''.join(open(tfjs_dir + '/' + p, 'rb').read() for p in d['weightsManifest'][0]['paths'])
    off = 0; tens = []
    for w in d['weightsManifest'][0]['weights']:
        n = int(np.prod(w['shape'])) if w['shape'] else 1
        if w.get('quantization', {}).get('dtype') == 'float16':
            a = np.frombuffer(blob, '<f2', n, off).astype(np.float32); off += 2 * n
        else:
            a = np.frombuffer(blob, '<f4' if w['dtype'] != 'int32' else '<i4', n, off); off += 4 * n
        tens.append(a.reshape(w['shape']))
    cors = []
    for w, _ in convs[:33]:
        h = np.transpose(w, (2, 3, 1, 0))
        cands = [t for t in tens if t.shape == h.shape]
        cors.append(max(np.corrcoef(t.ravel(), h.ravel())[0, 1] for t in cands))
    return min(cors), float(np.median(cors))

if __name__ == '__main__':
    convs, prelus = load_ncnn(sys.argv[1], sys.argv[2])
    n, sha = write_blob(convs, prelus, sys.argv[3])
    print('params:', n, 'blob sha256:', sha)
    if len(sys.argv) > 4:
        mn, md = crosscheck_tfjs(convs, sys.argv[4])
        print('cross-check vs TF.js copy: per-layer weight correlation min %.4f median %.4f (1.0 = identical)' % (mn, md))
