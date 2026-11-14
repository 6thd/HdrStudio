"""Local app server: a JSON API on 127.0.0.1 plus the static UI, shown in a native window
(WebView2 through pywebview) or in the browser."""
from __future__ import annotations

import base64
import json
import hmac
import mimetypes
import os
import secrets
import subprocess
import sys
import threading
import time
import traceback
from collections import deque
from dataclasses import asdict
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

from . import ai, dialogs, media, models
from .job import PRESETS, QUALITY, Engine, ExportJob, Settings, estimate, plan, preview

UI = Path(__file__).parent / "ui"
VIDEO_TYPES = [("Video", "*.mp4 *.mov *.mkv *.m4v *.webm *.avi *.ts *.mts")]


class App:
    def __init__(self):
        self.logs: deque = deque(maxlen=400)
        self.log_n = 0
        self.engine = Engine(self.log)
        self.job: ExportJob | None = None
        self.prep: dict = {}

    def log(self, msg):
        self.log_n += 1
        self.logs.append({"n": self.log_n, "t": time.strftime("%H:%M:%S"), "msg": str(msg)})

    # ------------------------------------------------------------------ API
    def system(self, _):
        info = {"ffmpeg": "", "gl": {}, "backends": [c["label"] for c in ai.candidates()],
                "presets": PRESETS, "quality": {k: {**v, "ready": not v["model"] or models.status(v["model"])["ready"]} for k, v in QUALITY.items()},
                "models": [models.status(m) for m in models.REGISTRY], "defaults": asdict(Settings())}
        try:
            r = media.run([media.ffmpeg(), "-version"])
            info["ffmpeg"] = r.stdout.decode("utf-8", "replace").splitlines()[0]
        except Exception as e:
            info["ffmpegError"] = str(e)
        if self.engine.k:
            info["gl"] = self.engine.k.info
        cache = ai._load_cache()
        info["bench"] = {m: {"best": c["best"]["label"], "tileS": c["best"]["tileS"],
                             "results": [{k: r.get(k) for k in ("label", "tileS", "maxErr", "ok", "error")} for r in c["results"]]}
                         for m, c in cache.items() if m in models.REGISTRY and "best" in c}
        return info

    def pick(self, q):
        """Native file dialog (a child process: see dialogs.py)."""
        return {"path": dialogs.pick(q.get("kind", "open"), str(q.get("initial", "")))}

    def probe(self, q):
        si = media.probe(q["path"])
        d = si.describe()
        p = Path(q["path"])
        d["suggestedOutput"] = str(p.with_name(p.stem + "_HDRStudio.mp4"))
        return d

    def plan(self, q):
        s = Settings.from_dict(q["settings"])
        si = media.probe(s.input)
        pl = plan(s, si)
        cache = ai._load_cache().get(pl.model or "", {})
        est = None
        if not pl.model:
            est = estimate(pl, None)
        elif "best" in cache:
            from .job import pick_backend
            b = pick_backend(cache, s.allow_fp16)
            spec = models.REGISTRY[pl.model]
            tiles = ((pl.lr_w + spec.tile - 1) // spec.tile) * ((pl.lr_h + spec.tile - 1) // spec.tile)
            enc_s = pl.params.out_w * pl.params.out_h / 8.3e6 * 0.6 + 0.05
            per = tiles * b["tileS"] + enc_s
            if pl.interp:
                from .job import rife_seconds
                per = (per + (rife_seconds(pl) or 0.0) + enc_s) / 2
            est ={"secPerFrame": round(per, 2), "frames": pl.frames, "totalSec": round(per * pl.frames), "backend": b["label"], "tiles": tiles}
        return {"outW": pl.params.out_w, "outH": pl.params.out_h, "model": pl.model, "lrW": pl.lr_w, "lrH": pl.lr_h,
                "notes": pl.notes, "frames": pl.frames, "estimate": est, "fps": round(float(pl.enc.fps), 3),
                "codec": pl.enc.codec, "hdr": pl.enc.hdr, "trc": pl.enc.trc, "crf": pl.enc.crf, "interp": pl.interp, "hdr10plus": pl.hdr10plus,
                "resumable": Path(s.output + ".hdrjob").joinpath("state.json").exists() if s.output else False}

    def preview(self, q):
        if self.job and self.job.progress["state"] in ("running", "queued"):
            raise RuntimeError("المعاينة غير متاحة أثناء التصدير")
        s = Settings.from_dict(q["settings"])
        r = self.engine.submit(lambda e: preview(e, s, float(q.get("t", 0)), float(q.get("cx", 0.5)), float(q.get("cy", 0.5)),
                                                 int(q.get("size", 448))), "preview").result()
        for k in ("before", "after"):
            r[k] = "data:image/png;base64," + base64.b64encode(r[k]).decode()
        return r

    def prepare(self, q):
        """Downloads/builds a model and benchmarks the runtimes (background)."""
        mid = q["model"]
        if self.prep.get(mid, {}).get("state") == "running":
            return self.prep[mid]
        st = self.prep[mid] = {"state": "running", "msg": "بدء", "got": 0, "total": 0}

        def work(_):
            try:
                def prog(g, t):
                    st.update(got=g, total=t, msg="تنزيل")
                spec = models.ensure(mid, prog)
                st.update(msg="اختبار خلفيات التشغيل")
                ai.benchmark(spec, self.log)
                st.update(state="done", msg="جاهز")
            except Exception as e:
                st.update(state="error", msg=str(e))
        self.engine.submit(work, "prepare")
        return st

    def prepare_status(self, q):
        return self.prep.get(q.get("model", ""), {"state": "idle"})

    def export(self, q):
        if self.job and self.job.progress["state"] in ("running", "queued"):
            raise RuntimeError("يوجد تصدير جارٍ")
        s = Settings.from_dict(q["settings"])
        if not s.input or not os.path.isfile(s.input):
            raise ValueError("اختر ملف المصدر")
        if not s.output:
            raise ValueError("اختر مكان الحفظ")
        if os.path.abspath(s.input) == os.path.abspath(s.output):
            raise ValueError("ملف الناتج لا يجوز أن يكون ملف المصدر نفسه")
        self.job = ExportJob(s, self.log)
        self.engine.submit(self.job.run, "export")
        return {"ok": True}

    def cancel(self, _):
        if self.job:
            self.job.cancel.set()
        return {"ok": True}

    def status(self, q):
        since = int(q.get("since", 0))
        j = self.job
        return {"busy": self.engine.busy, "job": j.progress if j else None, "report": j.report if j else None,
                "liveN": j.live_n if j else 0, "logs": [l for l in self.logs if l["n"] > since]}

    def reveal(self, q):
        p = q.get("path", "")
        if os.path.exists(p):
            subprocess.Popen(["explorer", "/select,", os.path.normpath(p)])
        return {"ok": True}


MAX_BODY = 1 << 20
CSP = "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
ROUTES = {"system", "pick", "probe", "plan", "preview", "prepare", "prepare_status", "export", "cancel", "status", "reveal"}


def make_handler(app, token: str):
    """Request handler. The API starts exports, opens file dialogs and reads any path it is given,
    so a web page open in the user's browser must not be able to call it:
      - Host must be this loopback server (blocks DNS rebinding);
      - Origin / Sec-Fetch-Site, when sent, must be same-origin (blocks cross-site requests);
      - every /api/ call carries the per-run token that only our own page receives (in a meta
        tag of index.html, which a cross-site page cannot read);
      - the API is POST + application/json only, which a cross-site form cannot send."""
    class H(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *a):
            pass

        def _send(self, code, body: bytes, ctype="application/json; charset=utf-8"):
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Referrer-Policy", "no-referrer")
            self.send_header("Content-Security-Policy", CSP)
            self.end_headers()
            self.wfile.write(body)

        def _local(self):
            port = self.server.server_address[1]
            own = {f"127.0.0.1:{port}", f"localhost:{port}"}
            if (self.headers.get("Host") or "") not in own:
                return False
            origin = self.headers.get("Origin")
            if origin is not None and origin not in {"http://" + h for h in own}:
                return False
            return self.headers.get("Sec-Fetch-Site") not in ("cross-site", "same-site")

        def _authed(self, given):
            return isinstance(given, str) and hmac.compare_digest(given.encode(), token.encode())

        def do_GET(self):
            if not self._local():
                return self._send(403, b"{}")
            u = urlparse(self.path)
            q = {k: v[0] for k, v in parse_qs(u.query).items()}
            try:
                if u.path.startswith("/api/"):
                    # images only (an <img> cannot send headers, so the token rides in the query)
                    if u.path not in ("/api/thumb", "/api/live") or not self._authed(q.get("k")):
                        return self._send(403, b"{}")
                if u.path == "/api/thumb":
                    return self._send(200, media.thumbnail(q["path"], float(q.get("t", 0))), "image/jpeg")
                if u.path == "/api/live":
                    j = app.job
                    return self._send(200, j.live, "image/jpeg") if j and j.live else self._send(404, b"")
                name = "index.html" if u.path in ("/", "") else u.path.lstrip("/")
                f = (UI / name).resolve()
                if UI.resolve() not in f.parents or not f.is_file():
                    return self._send(404, b"not found", "text/plain")
                body = f.read_bytes()
                if name == "index.html":
                    body = body.replace(b"__HDR_TOKEN__", token.encode())
                self._send(200, body, (mimetypes.guess_type(f.name)[0] or "application/octet-stream") + "; charset=utf-8")
            except Exception as e:
                self._send(500, json.dumps({"error": str(e)}, ensure_ascii=False).encode())

        def do_POST(self):
            if not self._local():
                return self._send(403, b"{}")
            if not self._authed(self.headers.get("X-HDR-Token")):
                return self._send(403, b"{}")
            if (self.headers.get("Content-Type") or "").split(";")[0].strip().lower() != "application/json":
                return self._send(415, b'{"error":"content type"}')
            try:
                n = int(self.headers.get("Content-Length") or 0)
            except ValueError:
                n = -1
            if not 0 <= n <= MAX_BODY:
                return self._send(413, b'{"error":"body size"}')
            try:
                q = json.loads(self.rfile.read(n) or b"{}")
            except Exception:
                return self._send(400, b'{"error":"bad json"}')
            if not isinstance(q, dict):
                return self._send(400, b'{"error":"bad json"}')
            self._api(urlparse(self.path).path[5:], q)

        def _api(self, name, q):
            if name not in ROUTES:
                return self._send(404, b'{"error":"unknown"}')
            try:
                self._send(200, json.dumps(getattr(app, name)(q), ensure_ascii=False, default=str).encode())
            except Exception as e:
                if not isinstance(e, (ValueError, RuntimeError, FileNotFoundError)):
                    app.log(traceback.format_exc())
                self._send(400, json.dumps({"error": str(e)}, ensure_ascii=False).encode())
    return H


def serve(browser=False, port=0, open_ui=True):
    app = App()
    srv = ThreadingHTTPServer(("127.0.0.1", port), make_handler(app, secrets.token_urlsafe(32)))
    url = f"http://127.0.0.1:{srv.server_address[1]}/"
    print("HDR Studio:", url, flush=True)
    if not open_ui:
        srv.serve_forever()
        return 0
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    if not browser:
        try:
            import webview
            webview.create_window("HDR Studio", url, width=1380, height=900, min_size=(1100, 720), background_color="#0b0d12")
            webview.start()
            return 0
        except Exception as e:
            print("تعذّر فتح نافذة التطبيق، يُفتح في المتصفح:", e)
    import webbrowser
    webbrowser.open(url)
    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        return 0
