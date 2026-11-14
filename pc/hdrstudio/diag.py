"""Diagnostics: what this machine is, and where an export spends its time on it.

`hdrstudio diag` describes the environment (CPU, memory, graphics adapters, OpenGL, FFmpeg, every AI
runtime with its measured speed and accuracy) and runs short timed exports of a generated clip:
cleanup only, AI, and frame interpolation. The numbers answer "is this machine fast enough, and
which stage is the bottleneck" without guessing from the adapter's name.

Stage times are wall-clock around each step. GPU work is asynchronous, so shader time that has not
finished when a step returns is paid at the next read-back and shows up under "encode"."""
from __future__ import annotations

import ctypes
import json
import os
import platform
import subprocess
import sys
import tempfile
import time
from pathlib import Path


def peak_memory_mb() -> int:
    """Peak working set of this process (the FFmpeg child processes are not included)."""
    if sys.platform != "win32":
        try:
            import resource
            return int(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024)
        except Exception:
            return 0

    class PMC(ctypes.Structure):
        _fields_ = [("cb", ctypes.c_ulong), ("PageFaultCount", ctypes.c_ulong), ("PeakWorkingSetSize", ctypes.c_size_t),
                    ("WorkingSetSize", ctypes.c_size_t), ("QuotaPeakPagedPoolUsage", ctypes.c_size_t), ("QuotaPagedPoolUsage", ctypes.c_size_t),
                    ("QuotaPeakNonPagedPoolUsage", ctypes.c_size_t), ("QuotaNonPagedPoolUsage", ctypes.c_size_t),
                    ("PagefileUsage", ctypes.c_size_t), ("PeakPagefileUsage", ctypes.c_size_t)]
    try:
        pmc = PMC()
        pmc.cb = ctypes.sizeof(PMC)
        k32 = ctypes.WinDLL("kernel32", use_last_error=True)
        k32.GetCurrentProcess.restype = ctypes.c_void_p
        psapi = ctypes.WinDLL("psapi", use_last_error=True)
        psapi.GetProcessMemoryInfo.argtypes = [ctypes.c_void_p, ctypes.POINTER(PMC), ctypes.c_ulong]
        if psapi.GetProcessMemoryInfo(k32.GetCurrentProcess(), ctypes.byref(pmc), pmc.cb):
            return int(pmc.PeakWorkingSetSize / 1048576)
    except Exception:
        pass
    return 0


def _total_memory_mb() -> int:
    try:
        class MS(ctypes.Structure):
            _fields_ = [("dwLength", ctypes.c_ulong), ("dwMemoryLoad", ctypes.c_ulong)] + [(n, ctypes.c_ulonglong) for n in (
                "ullTotalPhys", "ullAvailPhys", "ullTotalPageFile", "ullAvailPageFile", "ullTotalVirtual", "ullAvailVirtual", "ullAvailExtendedVirtual")]
        ms = MS()
        ms.dwLength = ctypes.sizeof(MS)
        ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(ms))
        return int(ms.ullTotalPhys / 1048576)
    except Exception:
        return 0


def _adapters() -> list:
    """Graphics adapters as Windows reports them (name, driver version, dedicated memory)."""
    try:
        r = subprocess.run(["powershell", "-NoProfile", "-Command",
                            "Get-CimInstance Win32_VideoController | Select-Object Name,DriverVersion,AdapterRAM | ConvertTo-Json -Compress"],
                           capture_output=True, text=True, timeout=30, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        d = json.loads(r.stdout or "[]")
        return [{"name": a.get("Name"), "driver": a.get("DriverVersion"), "memoryMB": int((a.get("AdapterRAM") or 0) / 1048576)}
                for a in (d if isinstance(d, list) else [d])]
    except Exception:
        return []


def environment(engine=None) -> dict:
    from . import ai, media, models
    env = {"os": platform.platform(), "cpu": platform.processor() or os.environ.get("PROCESSOR_IDENTIFIER", ""),
           "cores": os.cpu_count(), "memoryMB": _total_memory_mb(), "python": platform.python_version(),
           "packaged": bool(getattr(sys, "frozen", False)), "adapters": _adapters(), "aiRuntimes": [c["label"] for c in ai.candidates()]}
    try:
        env["ffmpeg"] = media.run([media.ffmpeg(), "-version"]).stdout.decode("utf-8", "replace").splitlines()[0]
    except Exception as e:
        env["ffmpeg"] = "missing: " + str(e)
    if engine is not None and engine.k is not None:
        env["opengl"] = engine.k.info
    cache = ai._load_cache()
    env["aiBenchmarks"] = {m: [{k: r.get(k) for k in ("label", "tileS", "maxErr", "ok", "error")} for r in c.get("results", [])]
                           for m, c in cache.items() if m in models.REGISTRY}
    return env


def _clip(path: Path, short: int, seconds: float):
    from . import media
    w, h = (short * 16 // 9 // 2 * 2, short)
    r = media.run([media.ffmpeg(), "-v", "error", "-y", "-f", "lavfi", "-i", f"testsrc2=s={w}x{h}:r=30:d={seconds}",
                   "-f", "lavfi", "-i", f"sine=f=440:d={seconds}", "-c:v", "libx264", "-preset", "veryfast", "-crf", "16", "-pix_fmt", "yuv420p",
                   "-c:a", "aac", "-shortest", str(path)])
    if not path.exists():
        raise RuntimeError("could not create the test clip: " + r.stderr.decode("utf-8", "replace")[-300:])


def run(short: int = 1080, seconds: float = 2.0, ai_tier: str = "fast", out_json: str = "") -> int:
    from .job import Engine, ExportJob, Settings
    eng = Engine()
    eng.submit(lambda e: None, "init").result()          # creates the OpenGL context
    report = {"environment": environment(eng), "clip": f"{short}p, {seconds:g} s, 30 fps", "exports": []}
    env = report["environment"]
    print(f"OS        {env['os']}\nCPU       {env['cpu']} ({env['cores']} threads), {env['memoryMB']} MB RAM")
    for a in env["adapters"]:
        print(f"Adapter   {a['name']}  driver {a['driver']}  {a['memoryMB']} MB")
    print(f"OpenGL    {env.get('opengl', {}).get('GL_RENDERER', '?')}  {env.get('opengl', {}).get('GL_VERSION', '')}")
    print(f"FFmpeg    {env['ffmpeg']}\nAI        {', '.join(env['aiRuntimes'])}\n", flush=True)
    cases = [("cleanup + HDR10", {"quality": "off"}), ("cleanup + HDR10 + frame doubling", {"quality": "off", "fps_mode": "x2"})]
    if ai_tier != "off":
        cases.append((f"AI '{ai_tier}' + HDR10", {"quality": ai_tier}))
    with tempfile.TemporaryDirectory(prefix="hdrstudio-diag-") as tmp:
        src = Path(tmp) / "source.mp4"
        _clip(src, short, seconds)
        for i, (name, over) in enumerate(cases):
            job = ExportJob(Settings.from_dict({"input": str(src), "output": str(Path(tmp) / f"out{i}.mp4"), "preset": "tiktok_hdr", **over}),
                            log=lambda m: None)
            t = time.perf_counter()
            eng.submit(job.run, "export").result()
            rep = job.report or {}
            tm = rep.get("timing", {})
            row = {"case": name, "ok": bool(rep.get("ok")), "error": job.error, "frames": rep.get("frames"), "seconds": round(time.perf_counter() - t, 1),
                   "size": rep.get("size"), "timing": tm, "backend": (job.progress or {}).get("stage")}
            report["exports"].append(row)
            if not row["ok"]:
                print(f"{name}: FAILED  {job.error or rep.get('problems')}", flush=True)
                continue
            stages = "  ".join(f"{k} {tm[k]:.1f}s" for k in ("decode", "cleanup", "ai", "interpolation", "encode") if tm.get(k, 0) >= 0.05)
            work = sum(tm.get(k, 0) for k in ("analysis", "decode", "cleanup", "ai", "interpolation", "encode"))
            row["setupSeconds"] = round(max(0.0, row["seconds"] - work - tm.get("muxVerify", 0)), 1)   # model build/benchmark, first use only
            print(f"{name}: {rep['frames']} frames, {tm.get('fps')} fps while processing   [{stages}]   "
                  f"one-time setup {row['setupSeconds']} s, peak memory {tm.get('peakMemoryMB')} MB", flush=True)
    report["environment"] = environment(eng)              # now includes the AI runtime benchmarks made on the way
    if out_json:
        Path(out_json).write_text(json.dumps(report, ensure_ascii=False, indent=1), "utf-8")
        print("\nreport written to", out_json)
    return 0 if all(r["ok"] for r in report["exports"]) else 1
