#!/usr/bin/env python3
"""Convert the OFFICIAL Real-ESRGAN realesr-general-x4v3.pth (PyTorch zip checkpoint) into the
app's weight blob WITHOUT PyTorch, and compare it with the shipped asset.
Used by the 'verify-model' CI job, where github.com release assets are reachable:
  https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.5.0/realesr-general-x4v3.pth
usage: convert_official_pth.py <model.pth> <out.bin> [shipped_asset.bin]"""
import hashlib, pickle, sys, zipfile
import numpy as np

class _Storage:
    def __init__(self, dtype, key): self.dtype, self.key = dtype, key

def load_state_dict(path):
    z = zipfile.ZipFile(path)
    prefix = [n for n in z.namelist() if n.endswith('data.pkl')][0][:-len('data.pkl')]
    dtypes = {'FloatStorage': np.float32, 'HalfStorage': np.float16, 'DoubleStorage': np.float64}
    class U(pickle.Unpickler):
        def find_class(self, mod, name):
            if mod == 'collections' and name == 'OrderedDict':
                import collections; return collections.OrderedDict
            if mod == 'torch._utils' and name == '_rebuild_tensor_v2':
                def rebuild(storage, offset, size, stride, *a):
                    raw = np.frombuffer(z.read(prefix + 'data/' + storage.key), storage.dtype)
                    if not size: return raw[offset:offset + 1].reshape(())
                    return np.lib.stride_tricks.as_strided(raw[offset:], size, [s * raw.itemsize for s in stride]).copy()
                return rebuild
            if mod == 'torch' and name in dtypes:
                return name
            raise pickle.UnpicklingError('unexpected global %s.%s' % (mod, name))
        def persistent_load(self, pid):
            # ('storage', storage_type, key, location, numel)
            st = pid[1]; st = st if isinstance(st, str) else getattr(st, '__name__', str(st))
            return _Storage(dtypes[st], pid[2])
    obj = U(z.open(prefix + 'data.pkl')).load()
    for k in ('params_ema', 'params'):
        if isinstance(obj, dict) and k in obj: return obj[k], k
    return obj, 'root'

def main(pth, out, shipped=None):
    sd, which = load_state_dict(pth)
    print('pth sha256:', hashlib.sha256(open(pth, 'rb').read()).hexdigest(), 'using key:', which)
    blob = []
    for L in range(34):
        w = sd['body.%d.weight' % (2 * L)].astype(np.float32); b = sd['body.%d.bias' % (2 * L)].astype(np.float32)
        assert w.shape[2:] == (3, 3), w.shape
        blob += [w.ravel(), b.ravel()]
        if L < 33: blob.append(sd['body.%d.weight' % (2 * L + 1)].astype(np.float32).ravel())
    data = np.concatenate(blob).astype('<f4')
    data.tofile(out)
    print('blob params:', data.size, 'sha256:', hashlib.sha256(data.tobytes()).hexdigest())
    if shipped:
        s = np.fromfile(shipped, '<f4')
        if s.size != data.size: print('SIZE MISMATCH with shipped asset'); return
        d16 = np.abs(s - data.astype(np.float16).astype(np.float32)).max()
        print('max |shipped - official rounded to fp16| =', d16, '(0 => shipped asset == official weights in fp16)')
        print('max |shipped - official fp32|            =', np.abs(s - data).max())

if __name__ == '__main__':
    main(*sys.argv[1:4])
