# PyInstaller spec: one folder with two executables that share every library.
#   "HDR Studio.exe"      the app window (no console)
#   "hdrstudio-cli.exe"   export / probe / bench from a terminal
# Build:  python -m PyInstaller packaging/hdrstudio.spec --noconfirm
import os

from PyInstaller.utils.hooks import collect_all

ROOT = os.path.abspath(os.path.join(SPECPATH, ".."))
J = lambda *p: os.path.join(ROOT, *p)

datas = [
    (J("hdrstudio", "ui"), "hdrstudio/ui"),
    (J("hdrstudio", "shaders"), "hdrstudio/shaders"),
    # Small weights ship with the app; the large Real-ESRGAN x2plus/x4plus files are downloaded
    # on demand and the .onnx files are built on first use (the install folder is per-user, writable).
    (J("models", "srvgg_general_x4v3.bin"), "models"),
    (J("models", "srvgg_selftest.bin"), "models"),
    (J("models", "LICENSE-Real-ESRGAN.txt"), "models"),
    (J("models", "rife-v4.6", "flownet.param"), "models/rife-v4.6"),
    (J("models", "rife-v4.6", "flownet.bin"), "models/rife-v4.6"),
    (J("models", "rife-v4.6", "LICENSE-RIFE-ncnn.txt"), "models/rife-v4.6"),
]
binaries, hidden = [], ["tkinter", "tkinter.filedialog"]
for pkg in ("openvino", "onnxruntime", "onnx", "moderngl", "glcontext", "webview"):
    d, b, h = collect_all(pkg)
    datas += d
    binaries += b
    hidden += h

a = Analysis([J("packaging", "launch.py")], pathex=[ROOT], datas=datas, binaries=binaries, hiddenimports=hidden,
             excludes=["pytest", "_pytest", "PyInstaller", "pip", "setuptools", "IPython", "matplotlib", "torch", "tensorflow"],
             noarchive=False)
pyz = PYZ(a.pure)
icon = J("packaging", "hdrstudio.ico")
common = dict(exclude_binaries=True, bootloader_ignore_signals=False, strip=False, upx=False, icon=icon)
gui = EXE(pyz, a.scripts, [], name="HDR Studio", console=False, **common)
cli = EXE(pyz, a.scripts, [], name="hdrstudio-cli", console=True, **common)
coll = COLLECT(gui, cli, a.binaries, a.datas, strip=False, upx=False, name="HDR Studio")
