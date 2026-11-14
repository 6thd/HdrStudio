"""FFmpeg I/O: probing, raw planar decode, chunked encode, HDR10 SEI patching, final mux.

FFmpeg is used only as a codec: frames leave the decoder in the stream's own Y'CbCr format and
enter the encoder as final Y'CbCr codes, so no swscale colour conversion happens anywhere.
"""
from __future__ import annotations

import glob
import json
import os
import re
import shutil
import subprocess
from dataclasses import dataclass, field
from fractions import Fraction
from pathlib import Path

import numpy as np

NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)
_PIX = re.compile(r"^yuv(j)?(420|422|444)p(\d+)?(le|be)?$")


def _find(name: str) -> str:
    exe = shutil.which(name)
    if exe:
        return exe
    local = os.environ.get("LOCALAPPDATA", "")
    here = Path(__file__).resolve().parent.parent
    for pat in (str(here / "bin" / f"{name}.exe"), os.path.join(local, "Microsoft", "WinGet", "Links", f"{name}.exe"),
                os.path.join(local, "Microsoft", "WinGet", "Packages", "Gyan.FFmpeg*", "*", "bin", f"{name}.exe")):
        hits = glob.glob(pat)
        if hits:
            return hits[0]
    raise FileNotFoundError(f"لم يُعثر على {name}. ثبّت FFmpeg (winget install Gyan.FFmpeg) أو ضعه في مجلد bin.")


_exe: dict[str, str] = {}


def _cached(name: str) -> str:
    if name not in _exe:            # looked up once: setdefault() would search the disk on every call
        _exe[name] = _find(name)
    return _exe[name]


def ffmpeg() -> str:
    return _cached("ffmpeg")


def ffprobe() -> str:
    return _cached("ffprobe")


def run(args, **kw):
    return subprocess.run(args, capture_output=True, creationflags=NO_WINDOW, **kw)


# ------------------------------------------------------------------ probe
@dataclass
class SourceInfo:
    path: str
    width: int = 0              # display size (after rotation)
    height: int = 0
    fps: Fraction = Fraction(30)
    vfr: bool = False
    frames: int = 0             # estimate
    duration: float = 0.0
    codec: str = ""
    pix_fmt: str = ""
    bit_depth: int = 8
    sub: tuple = (2, 2)
    full_range: bool = False
    matrix: int = 0             # 0 BT.709, 1 BT.2020 NCL, 2 BT.601
    transfer: int = 0           # 0 SDR, 1 PQ, 2 HLG
    src2020: bool = False
    rotation: int = 0
    max_cll: float = 0.0
    master_peak: float = 0.0
    audio_codec: str = ""
    assumed: list = field(default_factory=list)
    bitrate: int = 0

    @property
    def is_hdr(self):
        return self.transfer != 0

    @property
    def decode_fmt(self):
        s = {(2, 2): "420", (2, 1): "422", (1, 1): "444"}[self.sub]
        return f"yuv{s}p" + ("" if self.bit_depth == 8 else f"{self.bit_depth}le")

    def describe(self):
        tr = ["SDR", "HDR10 (PQ)", "HLG"][self.transfer]
        mx = ["BT.709", "BT.2020", "BT.601"][self.matrix]
        return {
            "path": self.path, "width": self.width, "height": self.height, "fps": round(float(self.fps), 3),
            "vfr": self.vfr, "frames": self.frames, "duration": round(self.duration, 3), "codec": self.codec,
            "pixFmt": self.pix_fmt, "bitDepth": self.bit_depth, "transfer": tr, "matrix": mx, "isHdr": self.is_hdr,
            "range": "Full" if self.full_range else "Limited", "rotation": self.rotation, "audio": self.audio_codec,
            "maxCll": self.max_cll, "masterPeak": self.master_peak, "assumed": self.assumed, "bitrate": self.bitrate}


def _frac(s, default=Fraction(0)):
    try:
        f = Fraction(s)
        return f if f > 0 else default
    except Exception:
        return default


def probe(path: str) -> SourceInfo:
    r = run([ffprobe(), "-v", "error", "-print_format", "json", "-show_format", "-show_streams", path])
    if r.returncode != 0:
        raise ValueError("تعذّر قراءة الملف: " + r.stderr.decode("utf-8", "replace").strip()[:300])
    d = json.loads(r.stdout)
    vs = next((s for s in d.get("streams", []) if s.get("codec_type") == "video" and not s.get("disposition", {}).get("attached_pic")), None)
    if not vs:
        raise ValueError("الملف لا يحتوي مسار فيديو")
    au = next((s for s in d["streams"] if s.get("codec_type") == "audio"), None)
    si = SourceInfo(path=path, codec=vs.get("codec_name", ""), pix_fmt=vs.get("pix_fmt", ""))
    w, h = int(vs["width"]), int(vs["height"])
    rot = 0
    for sd in vs.get("side_data_list", []):
        if "rotation" in sd:
            rot = int(round(float(sd["rotation"])))
    si.rotation = rot % 360
    si.width, si.height = (h, w) if si.rotation in (90, 270) else (w, h)
    rf, af = _frac(vs.get("r_frame_rate", "0")), _frac(vs.get("avg_frame_rate", "0"))
    fps = rf or af or Fraction(30)
    if af and rf and abs(float(af) - float(rf)) / float(rf) > 0.005:
        si.vfr = True
        fps = Fraction(round(float(af) * 1000), 1000).limit_denominator(1001)
    si.fps = fps
    si.duration = float(vs.get("duration") or d.get("format", {}).get("duration") or 0)
    si.frames = int(vs.get("nb_frames") or 0) if not si.vfr else 0
    if not si.frames:
        si.frames = int(round(si.duration * float(fps)))
    si.bitrate = int(vs.get("bit_rate") or d.get("format", {}).get("bit_rate") or 0)
    m = _PIX.match(si.pix_fmt)
    if m:
        si.sub = {"420": (2, 2), "422": (2, 1), "444": (1, 1)}[m.group(2)]
        si.bit_depth = int(m.group(3) or 8)
        si.full_range = bool(m.group(1))
    else:
        si.bit_depth = 10 if int(vs.get("bits_per_raw_sample") or 8) > 8 or "10" in si.pix_fmt else 8
    if si.bit_depth not in (8, 10, 12):
        si.bit_depth = 10
    if vs.get("color_range") == "pc":
        si.full_range = True
    elif vs.get("color_range") != "tv" and not si.full_range:
        si.assumed.append("النطاق: Limited")
    cs = vs.get("color_space", "")
    if cs in ("bt2020nc", "bt2020c"):
        si.matrix = 1
    elif cs in ("smpte170m", "bt470bg"):
        si.matrix = 2
    elif cs == "bt709":
        si.matrix = 0
    else:
        si.matrix = 0 if min(si.width, si.height) >= 720 else 2
        si.assumed.append("المصفوفة: " + ("BT.709" if si.matrix == 0 else "BT.601"))
    tr = vs.get("color_transfer", "")
    si.transfer = 1 if tr == "smpte2084" else 2 if tr == "arib-std-b67" else 0
    if not tr or tr == "unknown":
        si.assumed.append("منحنى النقل: SDR")
    si.src2020 = vs.get("color_primaries") == "bt2020" or (si.is_hdr and vs.get("color_primaries") in (None, "", "unknown"))
    si.audio_codec = au.get("codec_name", "") if au else ""
    if si.is_hdr:
        r = run([ffprobe(), "-v", "error", "-select_streams", "v:0", "-read_intervals", "%+#1", "-show_frames",
                 "-show_entries", "frame=side_data_list", "-print_format", "json", path])
        try:
            for fr in json.loads(r.stdout).get("frames", []):
                for sd in fr.get("side_data_list", []):
                    if "max_content" in sd:
                        si.max_cll = float(sd["max_content"])
                    if "max_luminance" in sd:
                        si.master_peak = float(Fraction(sd["max_luminance"]))
        except Exception:
            pass
    return si


def thumbnail(path: str, t: float, max_side: int = 960) -> bytes:
    """One frame as JPEG (tone-mapped by FFmpeg defaults; a navigation aid, not a measurement)."""
    r = run([ffmpeg(), "-v", "error", "-ss", f"{max(t, 0):.3f}", "-i", path, "-frames:v", "1",
             "-vf", f"scale='min({max_side},iw)':-2:flags=bicubic", "-q:v", "3", "-f", "mjpeg", "-"])
    if r.returncode != 0 or not r.stdout:
        raise ValueError("تعذّر استخراج الإطار")
    return r.stdout


# ------------------------------------------------------------------ decode
class Decoder:
    """Raw planar frames in the stream's own format. crop = (x, y, w, h) in display pixels."""

    def __init__(self, si: SourceInfo, start: float = 0.0, duration: float | None = None, crop=None, max_frames=None):
        self.si = si
        self.w, self.h = (crop[2], crop[3]) if crop else (si.width, si.height)
        sx, sy = si.sub
        self.cw, self.ch = (self.w + sx - 1) // sx, (self.h + sy - 1) // sy
        self.bps = 1 if si.bit_depth == 8 else 2
        self.dtype = np.uint8 if self.bps == 1 else np.dtype("<u2")
        self.frame_bytes = (self.w * self.h + 2 * self.cw * self.ch) * self.bps
        a = [ffmpeg(), "-v", "error", "-nostdin"]
        if start > 0:
            a += ["-ss", f"{start:.6f}"]
        a += ["-i", si.path]
        if duration:
            a += ["-t", f"{duration:.6f}"]
        a += ["-map", "0:v:0", "-an", "-sn", "-dn"]
        a += ["-fps_mode", "cfr", "-r", str(si.fps)] if si.vfr else ["-fps_mode", "passthrough"]
        if crop:
            a += ["-vf", f"crop={crop[2]}:{crop[3]}:{crop[0]}:{crop[1]}:exact=1"]
        if max_frames:
            a += ["-frames:v", str(max_frames)]
        a += ["-pix_fmt", si.decode_fmt, "-f", "rawvideo", "-"]
        self.proc = subprocess.Popen(a, stdout=subprocess.PIPE, stderr=subprocess.PIPE, creationflags=NO_WINDOW, bufsize=0)
        self._buf = bytearray(self.frame_bytes)

    def read(self):
        """Returns (Y, U, V) views valid until the next read, or None at end of stream."""
        mv, got = memoryview(self._buf), 0
        while got < self.frame_bytes:
            n = self.proc.stdout.readinto(mv[got:])
            if not n:
                break
            got += n
        if got < self.frame_bytes:
            return None
        a = np.frombuffer(self._buf, self.dtype)
        n1, n2 = self.w * self.h, self.cw * self.ch
        return a[:n1].reshape(self.h, self.w), a[n1:n1 + n2].reshape(self.ch, self.cw), a[n1 + n2:].reshape(self.ch, self.cw)

    def skip(self, n) -> int:
        done = 0
        while done < n and self.read() is not None:
            done += 1
        return done

    def close(self):
        if self.proc.poll() is None:
            self.proc.kill()
        self.proc.stdout.close()
        err = self.proc.stderr.read().decode("utf-8", "replace").strip()
        self.proc.stderr.close()
        self.proc.wait()
        return err


# ------------------------------------------------------------------ encode
@dataclass
class EncodeConfig:
    codec: str = "hevc"         # hevc | h264
    hdr: bool = True
    bit_depth: int = 10
    crf: float = 16.0
    preset: str = "slow"
    width: int = 0
    height: int = 0
    fps: Fraction = Fraction(30)
    peak: float = 1000.0
    maxrate_kbps: int = 0       # 0 = unconstrained CRF

    ext = ".mp4"                # chunks are MP4: the encoder's own timestamps survive (B-frame order)

    @property
    def pix_fmt(self):
        return "yuv420p10le" if self.bit_depth == 10 else "yuv420p"


def master_display(peak: float) -> str:
    """ST 2086 for a declared P3-D65 mastering display (a grading target, not a measurement)."""
    return f"G(13250,34500)B(7500,3000)R(34000,16000)WP(15635,16450)L({int(round(peak * 10000))},50)"


PLACEHOLDER_CLL = (1000, 400)


def encoder_args(c: EncodeConfig, out: str) -> list:
    a = [ffmpeg(), "-v", "error", "-nostdin", "-y", "-f", "rawvideo", "-pix_fmt", c.pix_fmt, "-s", f"{c.width}x{c.height}",
         "-r", str(c.fps), "-i", "-", "-an", "-pix_fmt", c.pix_fmt]
    gop = max(1, round(float(c.fps) * 2))
    prim, trc, mat = ("bt2020", "smpte2084", "bt2020nc") if c.hdr else ("bt709", "bt709", "bt709")
    a += ["-color_primaries", prim, "-color_trc", trc, "-colorspace", mat, "-color_range", "tv"]
    if c.codec == "hevc":
        x = [f"keyint={gop}", "min-keyint=1", "repeat-headers=1", "aud=1", "info=0", "open-gop=0", f"colorprim={prim}", f"transfer={trc}",
             f"colormatrix={mat}", "range=limited", "chromaloc=0", "log-level=error"]
        # Detail retention. x265 defaults favour PSNR: SAO and the default AQ smear low-contrast
        # texture, worst in dark areas (measured on a dark fabric: half the detail energy lost).
        # aq-mode=3 biases bits to dark blocks; psy-rd/psy-rdoq keep texture energy; no SAO.
        x += [e for e in os.environ.get("HDRSTUDIO_X265", "sao=0:aq-mode=3:aq-strength=1.0:psy-rd=2.0:psy-rdoq=1.5:deblock=-1,-1").split(":") if e]
        if c.hdr:
            x += ["hdr10=1", "master-display=" + master_display(c.peak), "max-cll=%d,%d" % PLACEHOLDER_CLL]
        if c.maxrate_kbps:
            x += [f"vbv-maxrate={c.maxrate_kbps}", f"vbv-bufsize={c.maxrate_kbps * 2}"]
        a += ["-c:v", "libx265", "-preset", c.preset, "-crf", f"{c.crf:g}", "-profile:v", "main10" if c.bit_depth == 10 else "main",
              "-x265-params", ":".join(x), "-tag:v", "hvc1", "-f", "mp4", out]
    else:
        a += ["-c:v", "libx264", "-preset", c.preset, "-crf", f"{c.crf:g}", "-profile:v", "high", "-g", str(gop),
              "-x264-params", f"repeat-headers=1:open-gop=0:aud=1:colorprim={prim}:transfer={trc}:colormatrix={mat}:range=tv"]
        if c.maxrate_kbps:
            a += ["-maxrate", f"{c.maxrate_kbps}k", "-bufsize", f"{c.maxrate_kbps * 2}k"]
        a += ["-f", "mp4", out]
    return a


class Encoder:
    def __init__(self, c: EncodeConfig, out: Path, log: Path):
        self.out, self._log = out, open(log, "ab")
        self.proc = subprocess.Popen(encoder_args(c, str(out)), stdin=subprocess.PIPE, stderr=self._log, stdout=self._log,
                                     creationflags=NO_WINDOW)
        self.frames = 0

    def write(self, *planes):
        try:
            for p in planes:
                self.proc.stdin.write(p)
        except (BrokenPipeError, OSError):
            raise RuntimeError("المرمّز توقف: " + self.tail()) from None
        self.frames += 1

    def tail(self):
        self._log.flush()
        try:
            return Path(self._log.name).read_text("utf-8", "replace")[-600:].strip()
        except Exception:
            return ""

    def close(self):
        self.proc.stdin.close()
        rc = self.proc.wait()
        msg = self.tail()
        self._log.close()
        if rc != 0:
            raise RuntimeError(f"فشل الترميز ({rc}): {msg}")

    def abort(self):
        try:
            self.proc.kill()
            self.proc.wait()
            self._log.close()
        except Exception:
            pass


# ------------------------------------------------------------------ HDR10 SEI
# x265 writes the content-light-level SEI as its own NAL unit:
#   4E 01 (prefix SEI) 90 (payload 144) 04 (size) CLL(2) FALL(2) 80 (trailing bits)
# The encoder is given placeholder values; once the real MaxCLL/MaxFALL are measured the four
# bytes are overwritten in the finished file. The size never changes because values that would
# need an emulation-prevention byte (00 00 0x) are nudged by one nit.
_CLL_HEAD = bytes([0x4E, 0x01, 0x90, 0x04])


def _cll_bytes(cll: int, fall: int) -> bytes:
    cll, fall = min(65535, max(1, cll)), min(65535, max(1, fall))
    if cll & 0xFF == 0 and fall <= 3:
        cll += 1
    return bytes([cll >> 8, cll & 255, fall >> 8, fall & 255])


def safe_cll(cll: int, fall: int) -> tuple:
    b = _cll_bytes(cll, fall)
    return (b[0] << 8) | b[1], (b[2] << 8) | b[3]


def patch_cll(path: Path, cll: int, fall: int) -> int:
    """Overwrites every placeholder content-light SEI in place. Returns how many were written."""
    import mmap
    old = _CLL_HEAD + _cll_bytes(*PLACEHOLDER_CLL) + bytes([0x80])
    new = _CLL_HEAD + _cll_bytes(cll, fall) + bytes([0x80])
    n = 0
    with open(path, "r+b") as f, mmap.mmap(f.fileno(), 0) as m:
        i = m.find(old)
        while i >= 0:
            m[i:i + len(new)] = new
            n += 1
            i = m.find(old, i + len(old))
        m.flush()
    return n


# ------------------------------------------------------------------ mux / verify
def mux(chunks: list, c: EncodeConfig, si: SourceInfo, out: Path, start=0.0, frames=0):
    """Joins the chunks (stream copy) and adds the source audio."""
    lst = out.with_suffix(".txt")
    lines = ["ffconcat version 1.0"] + [f"file '{Path(p).name}'" for p in chunks]
    lst.write_text("\n".join(lines) + "\n", "utf-8")
    a = [ffmpeg(), "-v", "error", "-nostdin", "-y", "-f", "concat", "-safe", "0", "-i", str(lst)]
    has_audio = bool(si.audio_codec)
    if has_audio:
        if start > 0:
            a += ["-ss", f"{start:.6f}"]
        a += ["-i", si.path]
    a += ["-map", "0:v:0"]
    if has_audio:
        copy = si.audio_codec in ("aac", "ac3", "eac3", "mp3", "alac")
        a += ["-map", "1:a:0", "-c:a", "copy"] if copy else ["-map", "1:a:0", "-c:a", "aac", "-b:a", "256k"]
        if frames:
            a += ["-t", f"{frames / float(c.fps):.6f}"]
    a += ["-c:v", "copy", "-map_metadata", "-1", "-movflags", "+faststart", "-video_track_timescale", str(c.fps.numerator * (1 if c.fps.numerator >= 1000 else 1000))]
    if c.codec == "hevc":
        a += ["-tag:v", "hvc1"]
    a += [str(out)]
    r = run(a)
    lst.unlink(missing_ok=True)
    if r.returncode != 0:
        raise RuntimeError("فشل الدمج: " + r.stderr.decode("utf-8", "replace")[-500:])


def verify(out: Path, c: EncodeConfig, frames: int, want_audio: bool) -> dict:
    """Checks the finished file before it is handed over: tags, frame count, audio, HDR side data."""
    r = run([ffprobe(), "-v", "error", "-count_packets", "-print_format", "json", "-show_streams", "-show_format", str(out)])
    d = json.loads(r.stdout or b"{}")
    vs = next((s for s in d.get("streams", []) if s.get("codec_type") == "video"), {})
    au = next((s for s in d.get("streams", []) if s.get("codec_type") == "audio"), None)
    rep = {"codec": vs.get("codec_name"), "profile": vs.get("profile"), "pixFmt": vs.get("pix_fmt"), "size": f"{vs.get('width')}x{vs.get('height')}",
           "frames": int(vs.get("nb_read_packets") or 0), "expectedFrames": frames, "primaries": vs.get("color_primaries"),
           "transfer": vs.get("color_transfer"), "matrix": vs.get("color_space"), "range": vs.get("color_range"),
           "audio": au.get("codec_name") if au else None, "duration": float(d.get("format", {}).get("duration") or 0),
           "bytes": int(d.get("format", {}).get("size") or 0)}
    problems = []
    if rep["frames"] != frames:
        problems.append(f"عدد الإطارات {rep['frames']} ≠ {frames}")
    want = ("bt2020", "smpte2084", "bt2020nc") if c.hdr else ("bt709", "bt709", "bt709")
    if (rep["primaries"], rep["transfer"], rep["matrix"]) != want:
        problems.append(f"وسوم الألوان {rep['primaries']}/{rep['transfer']}/{rep['matrix']}")
    if rep["pixFmt"] != c.pix_fmt.replace("le", "") and rep["pixFmt"] != c.pix_fmt:
        problems.append(f"صيغة البكسل {rep['pixFmt']}")
    if want_audio and not au:
        problems.append("الصوت مفقود")
    if c.hdr:
        r = run([ffprobe(), "-v", "error", "-select_streams", "v:0", "-read_intervals", "%+#1", "-show_frames",
                 "-show_entries", "frame=side_data_list", "-print_format", "json", str(out)])
        try:
            for fr in json.loads(r.stdout).get("frames", []):
                for sd in fr.get("side_data_list", []):
                    if "max_content" in sd:
                        rep["maxCll"], rep["maxFall"] = int(sd["max_content"]), int(sd["max_average"])
                    if "max_luminance" in sd:
                        rep["masterPeak"] = float(Fraction(sd["max_luminance"]))
        except Exception:
            pass
        if "maxCll" not in rep or "masterPeak" not in rep:
            problems.append("بيانات HDR10 الوصفية مفقودة")
    r = run([ffprobe(), "-v", "error", "-select_streams", "v:0", "-show_entries", "packet=pts_time", "-of", "csv=p=0", str(out)])
    pts = sorted(float(x) for x in r.stdout.decode().split() if x not in ("", "N/A"))
    if len(pts) == frames and frames > 1:
        gaps = np.diff(pts)
        step = 1.0 / float(c.fps)
        if abs(gaps - step).max() > step * 0.02 or abs(pts[0]) > step * 3:
            problems.append(f"طوابع زمنية غير منتظمة (أقصى انحراف {abs(gaps - step).max() * 1000:.2f} ms، البداية {pts[0]:.3f})")
    elif frames > 1:
        problems.append("طوابع زمنية مفقودة")
    r = run([ffmpeg(), "-v", "error", "-i", str(out), "-map", "0:v:0", "-f", "null", "-"])
    if r.returncode != 0 or r.stderr.strip():
        problems.append("تعذّر فك ترميز الناتج: " + r.stderr.decode("utf-8", "replace")[:200])
    rep["problems"] = problems
    rep["ok"] = not problems
    return rep
