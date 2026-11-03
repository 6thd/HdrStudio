#!/usr/bin/env python3
"""Source / binary fingerprints, platform independent (POSIX relative paths, raw bytes).

  build      : sha256 of `find app/src/main -type f | LC_ALL=C sort | xargs sha256sum` (BUILD_INFO.txt)
  harness    : check.py source_fingerprint (assets + java + tools/harness, minus *.json/*.log/node_modules)
  apk <file> : sha256 of the APK and of every classes*.dex inside it

usage: fingerprints.py build|harness|all [apk_path ...]
"""
import hashlib, os, sys, zipfile

ROOT = os.path.abspath(os.environ.get('FP_ROOT') or os.path.join(os.path.dirname(__file__), '..'))


def build_fingerprint(root=ROOT):
    base = os.path.join(root, 'app', 'src', 'main')
    rel = []
    for dp, dn, fn in os.walk(base):
        for f in fn:
            rel.append(os.path.relpath(os.path.join(dp, f), root).replace(os.sep, '/'))
    rel.sort(key=lambda s: s.encode())                      # LC_ALL=C byte order
    lines = ''.join('%s  %s\n' % (hashlib.sha256(open(os.path.join(root, p), 'rb').read()).hexdigest(), p) for p in rel)
    return hashlib.sha256(lines.encode()).hexdigest(), len(rel)


def harness_fingerprint(root=ROOT):
    sys.path.insert(0, os.path.join(root, 'tools', 'harness'))
    h = hashlib.sha256()
    for d in ['app/src/main/assets', 'app/src/main/java', 'tools/harness']:
        walk = [(os.path.relpath(dp, root).replace(os.sep, '/'), dn, fn) for dp, dn, fn in os.walk(os.path.join(root, d))]
        for dp, dn, fn in sorted(walk):
            for f in sorted(fn):
                if f.endswith(('.json', '.log')) or 'node_modules' in dp or '__pycache__' in dp:
                    continue
                p = dp + '/' + f
                h.update(p.encode()); h.update(open(os.path.join(root, p), 'rb').read())
    return h.hexdigest()


def apk_fingerprint(path):
    out = {'apk_sha256': hashlib.sha256(open(path, 'rb').read()).hexdigest()}
    with zipfile.ZipFile(path) as z:
        for n in sorted(z.namelist()):
            if n.startswith('classes') and n.endswith('.dex'):
                out[n + '_sha256'] = hashlib.sha256(z.read(n)).hexdigest()
    return out


if __name__ == '__main__':
    what = sys.argv[1] if len(sys.argv) > 1 else 'all'
    if what in ('build', 'all'):
        fp, n = build_fingerprint()
        print('build_sources_sha256 %s (%d files under app/src/main)' % (fp, n))
    if what in ('harness', 'all'):
        print('harness_source_sha256 %s' % harness_fingerprint())
    for a in sys.argv[2:]:
        for k, v in apk_fingerprint(a).items():
            print('%s %s  (%s)' % (k, v, os.path.basename(a)))
