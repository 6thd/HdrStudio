"""Resume test: kill an export (whole process tree) after its first chunk is recorded, resume it,
and compare the result with an uninterrupted export of the same clip.

usage: python tools/test_resume.py SOURCE [--quality off]
"""
import json
import subprocess
import sys
import time
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
from hdrstudio import media  # noqa: E402

sys.stdout.reconfigure(encoding="utf-8")
src = sys.argv[1]
quality = sys.argv[sys.argv.index("--quality") + 1] if "--quality" in sys.argv else "off"
out_a, out_b = ROOT / "test" / "resume_a.mp4", ROOT / "test" / "resume_b.mp4"
extra = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
cmd = [sys.executable, "-m", "hdrstudio", "export", src, "--preset", "tiktok_hdr", "--quality", quality, *extra, "-o"]
for f in (out_a, out_b):
    f.unlink(missing_ok=True)

p = subprocess.Popen(cmd + [str(out_a)], cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
state = Path(str(out_a) + ".hdrjob") / "state.json"
while p.poll() is None and not state.exists():
    time.sleep(0.05)
if p.poll() is not None:
    sys.exit("export finished before it could be interrupted: use a longer clip")
subprocess.run(["taskkill", "/T", "/F", "/PID", str(p.pid)], capture_output=True)
p.wait()
st = json.loads(state.read_text("utf-8"))
done = sum(c["frames"] for c in st["chunks"])
print(f"killed after {done} recorded frames; pipeline state {st['pipe']}")

r = subprocess.run(cmd + [str(out_a)], cwd=ROOT, capture_output=True, text=True, encoding="utf-8")
print([l for l in r.stdout.splitlines() if "استئناف" in l or "تم:" in l])
assert out_a.exists(), r.stdout[-800:]
r = subprocess.run(cmd + [str(out_b)], cwd=ROOT, capture_output=True, text=True, encoding="utf-8")
assert out_b.exists(), r.stdout[-800:]


def luma(path):
    si = media.probe(str(path))
    d = media.Decoder(si)
    frames = []
    while (f := d.read()) is not None:
        frames.append(f[0].astype(np.float32).copy())
    d.close()
    return frames


a, b = luma(out_a), luma(out_b)
assert len(a) == len(b), (len(a), len(b))
psnr = [10 * np.log10(1023 ** 2 / max(1e-9, float(((x - y) ** 2).mean()))) for x, y in zip(a, b)]
print(f"frames {len(a)}; PSNR resumed vs uninterrupted: before the cut min {min(psnr[:done]):.1f} dB, "
      f"first 5 after {[round(v, 1) for v in psnr[done:done + 5]]}, rest min {min(psnr[done + 5:] or [99]):.1f} dB")
