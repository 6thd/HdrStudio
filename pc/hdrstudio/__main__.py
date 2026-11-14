"""HDR Studio for PC.

  python -m hdrstudio                      desktop app
  python -m hdrstudio export IN -o OUT     export from the command line (resumes automatically)
  python -m hdrstudio probe IN             source information
  python -m hdrstudio bench [MODEL ...]    test and time every AI runtime on this machine
"""
from __future__ import annotations

import argparse
import json
import sys
import time


def main(argv=None):
    for s in (sys.stdout, sys.stderr):
        try:
            s.reconfigure(encoding="utf-8")
        except Exception:
            pass
    args = sys.argv[1:] if argv is None else list(argv)
    if args[:1] == ["_pick"]:                     # internal: file dialog child process
        from .dialogs import child_main
        return child_main(args[1:4])
    ap = argparse.ArgumentParser(prog="hdrstudio", description="HDR Studio — AI video enhancement and HDR10 export")
    sub = ap.add_subparsers(dest="cmd")
    g = sub.add_parser("gui", help="desktop app (default)")
    g.add_argument("--browser", action="store_true", help="open in the default browser instead of an app window")
    g.add_argument("--port", type=int, default=0)
    g.add_argument("--no-open", action="store_true")
    e = sub.add_parser("export")
    e.add_argument("input")
    e.add_argument("-o", "--output", required=True)
    e.add_argument("--preset", default="tiktok_hdr", choices=["tiktok_hdr", "tiktok_hlg", "tiktok_sdr", "hdr10_master", "sdr_master"])
    e.add_argument("--quality", default="fast", choices=["off", "fast", "detail", "high", "max"])
    e.add_argument("--resolution", default="source", help="source | x2 | 1080 | 1440 | 2160 (short side)")
    e.add_argument("--start", type=float, default=0.0)
    e.add_argument("--duration", type=float, default=0.0)
    e.add_argument("--allow-fp16", action="store_true")
    e.add_argument("--fps", default="source", choices=["source", "x2"], help="x2 = RIFE frame interpolation (30 -> 60 fps)")
    e.add_argument("--set", action="append", default=[], metavar="KEY=VALUE", help="any other setting, e.g. --set sharpen=0.5")
    p = sub.add_parser("probe")
    p.add_argument("input")
    dg = sub.add_parser("diag", help="environment report and timed test exports (send the output with a bug report)")
    dg.add_argument("--size", type=int, default=1080, help="short side of the test clip (1080, 2160, ...)")
    dg.add_argument("--seconds", type=float, default=2.0)
    dg.add_argument("--ai", default="fast", choices=["off", "fast", "detail", "high", "max"])
    dg.add_argument("-o", "--output", default="", help="also write the report as JSON")
    b = sub.add_parser("bench")
    b.add_argument("models", nargs="*", default=["general"])
    a = ap.parse_args(argv)

    if a.cmd in (None, "gui"):
        from .server import serve
        return serve(browser=getattr(a, "browser", False), port=getattr(a, "port", 0), open_ui=not getattr(a, "no_open", False))
    if a.cmd == "diag":
        from . import diag
        return diag.run(a.size, a.seconds, a.ai, a.output)
    if a.cmd == "probe":
        from . import media
        print(json.dumps(media.probe(a.input).describe(), ensure_ascii=False, indent=1))
        return 0
    if a.cmd == "bench":
        from . import ai, models
        for m in a.models:
            r = ai.benchmark(models.ensure(m), force=True)
            print(f"{m}: الأفضل {r['best']['label']} — {r['best']['tileS']} ث/بلاطة")
        return 0

    from .job import Engine, ExportJob, Settings
    d = {"input": a.input, "output": a.output, "preset": a.preset, "quality": a.quality, "resolution": a.resolution,
         "start": a.start, "duration": a.duration, "allow_fp16": a.allow_fp16, "fps_mode": a.fps}
    for kv in a.set:
        k, v = kv.split("=", 1)
        d[k] = json.loads(v) if v[:1] in "0123456789-.tf[{\"" else v
    job = ExportJob(Settings.from_dict(d))
    fut = Engine().submit(job.run, "export")
    try:
        last = None
        while not fut.done():
            time.sleep(1)
            pr = job.progress
            line = f"{pr.get('stage')}  {pr.get('frame')}/{pr.get('total')}  {pr.get('fps', 0)} fps  ETA {pr.get('eta')} ث"
            if line != last:
                print(line, flush=True)
                last = line
    except KeyboardInterrupt:
        job.cancel.set()
        fut.result()
    print(job.progress.get("stage"))
    if job.report:
        print(json.dumps(job.report, ensure_ascii=False, indent=1))
    return 0 if job.progress.get("state") == "done" else 1


if __name__ == "__main__":
    sys.exit(main())
