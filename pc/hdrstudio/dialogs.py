"""Native open/save dialogs.

Tk must own the main thread of its process, so the dialog runs in a child process of this same
program (`python -m hdrstudio _pick ...`, or the packaged executable with the same arguments).
The answer comes back through a file: a windowed executable has no usable stdout."""
from __future__ import annotations

import os
import subprocess
import sys
import tempfile

VIDEO = [("Video", "*.mp4 *.mov *.mkv *.m4v *.webm *.avi *.ts *.mts"), ("All", "*.*")]


def show(kind: str, initial: str) -> str:
    import tkinter as tk
    from tkinter import filedialog as fd
    root = tk.Tk()
    root.withdraw()
    root.attributes("-topmost", True)
    if kind == "open":
        path = fd.askopenfilename(filetypes=VIDEO)
    else:
        path = fd.asksaveasfilename(defaultextension=".mp4", filetypes=[("MP4", "*.mp4")], initialfile=os.path.basename(initial),
                                    initialdir=os.path.dirname(initial) or None)
    root.destroy()
    return path or ""


def child_main(argv) -> int:
    kind, initial, out = argv
    with open(out, "w", encoding="utf-8") as f:
        f.write(show(kind, initial))
    return 0


def pick(kind: str, initial: str = "") -> str:
    base = [sys.executable] if getattr(sys, "frozen", False) else [sys.executable, "-m", "hdrstudio"]
    fd_, out = tempfile.mkstemp(suffix=".txt", prefix="hdrstudio-pick-")
    os.close(fd_)
    try:
        subprocess.run(base + ["_pick", "save" if kind == "save" else "open", initial, out],
                       creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0), cwd=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
        with open(out, encoding="utf-8") as f:
            return f.read().strip().replace("/", os.sep)
    finally:
        try:
            os.unlink(out)
        except OSError:
            pass
