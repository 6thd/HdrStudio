"""Security and reliability hardening: model checksums, checkpoint tensor bounds, thumbnail paths,
recorded HDR assumptions."""
import collections
import hashlib
import io
import pickle
import sys
import types
import zipfile

import numpy as np
import pytest

from hdrstudio import media, models, server


# ---------------------------------------------------------------- crafted PyTorch-style checkpoints
def _checkpoint(path, size, stride, offset=0, numel=4):
    """A zip checkpoint like torch.save writes, built without PyTorch: one float32 storage of
    `numel` values and one tensor view (offset, size, stride) of it."""
    torch, utils = types.ModuleType("torch"), types.ModuleType("torch._utils")

    def _rebuild_tensor_v2(*a):
        raise AssertionError("never called while pickling")
    _rebuild_tensor_v2.__module__, _rebuild_tensor_v2.__qualname__ = "torch._utils", "_rebuild_tensor_v2"
    utils._rebuild_tensor_v2 = _rebuild_tensor_v2
    FloatStorage = type("FloatStorage", (), {"__module__": "torch"})
    torch.FloatStorage, torch._utils = FloatStorage, utils

    class Ref:                       # pickled as a persistent id, like a torch storage
        pass

    class Tensor:
        def __reduce__(self):
            return _rebuild_tensor_v2, (Ref(), offset, tuple(size), tuple(stride), False, collections.OrderedDict())

    class P(pickle.Pickler):
        def persistent_id(self, obj):
            return ("storage", FloatStorage, "0", "cpu", numel) if isinstance(obj, Ref) else None

    saved = {k: sys.modules.get(k) for k in ("torch", "torch._utils")}
    sys.modules.update({"torch": torch, "torch._utils": utils})
    try:
        buf = io.BytesIO()
        P(buf, protocol=2).dump(collections.OrderedDict(w=Tensor()))
    finally:
        for k, v in saved.items():
            if v is None:
                sys.modules.pop(k, None)
            else:
                sys.modules[k] = v
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("archive/data.pkl", buf.getvalue())
        z.writestr("archive/data/0", np.arange(numel, dtype=np.float32).tobytes())
    return path


def test_checkpoint_tensor_view_inside_its_storage_loads(tmp_path):
    sd = models.load_state_dict(_checkpoint(tmp_path / "ok.pth", size=(2, 2), stride=(2, 1)))
    np.testing.assert_array_equal(sd["w"], [[0, 1], [2, 3]])


@pytest.mark.parametrize("size,stride,offset", [
    ((1 << 20,), (1,), 0),        # far past the 4-value storage: as_strided would read foreign memory
    ((2, 2), (2, 1), 1),          # one element past the end
    ((2,), (-1,), 1),             # negative stride
])
def test_checkpoint_tensor_view_outside_its_storage_is_refused(tmp_path, size, stride, offset):
    path = _checkpoint(tmp_path / "bad.pth", size=size, stride=stride, offset=offset)
    with pytest.raises(pickle.UnpicklingError):
        models.load_state_dict(path)


# ---------------------------------------------------------------- published model checksums
def test_downloadable_models_carry_their_published_sha256():
    for spec in models.REGISTRY.values():
        if spec.url:
            assert spec.sha256 and len(spec.sha256) == 64
    readme = (models.MODEL_DIR.parent / "README.md").read_text("utf-8")
    for spec in models.REGISTRY.values():
        if spec.sha256:      # the README shows the same hash, abbreviated
            assert f"{spec.sha256[:8]}…{spec.sha256[-6:]}" in readme


class _Response(io.BytesIO):
    headers = {}

    def __enter__(self):
        return self

    def __exit__(self, *a):
        self.close()


def _fake_download(monkeypatch, tmp_path, payload):
    monkeypatch.setattr(models, "MODEL_DIR", tmp_path)
    monkeypatch.setattr(models.urllib.request, "urlopen", lambda *a, **k: _Response(payload))


def _zip_bytes():
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("archive/data.pkl", b"")
    return buf.getvalue()


def test_download_with_the_wrong_hash_is_discarded(monkeypatch, tmp_path):
    payload = _zip_bytes()
    _fake_download(monkeypatch, tmp_path, payload)
    spec = models.ModelSpec("t", "t", 2, 64, 8, "t.pth", "https://example.invalid/t.pth", "", sha256="0" * 64)
    with pytest.raises(IOError):
        models.download(spec)
    assert list(tmp_path.iterdir()) == []          # neither the file nor a .part is left


def test_download_with_the_published_hash_is_kept(monkeypatch, tmp_path):
    payload = _zip_bytes()
    _fake_download(monkeypatch, tmp_path, payload)
    spec = models.ModelSpec("t", "t", 2, 64, 8, "t.pth", "https://example.invalid/t.pth", "",
                            sha256=hashlib.sha256(payload).hexdigest())
    assert models.download(spec).read_bytes() == payload


def test_a_replaced_checkpoint_is_not_converted(monkeypatch, tmp_path):
    monkeypatch.setattr(models, "MODEL_DIR", tmp_path)
    spec = models.REGISTRY["x2plus"]
    monkeypatch.setitem(models.REGISTRY, "x2plus", spec)
    (tmp_path / spec.source).write_bytes(_zip_bytes())   # not the published file
    with pytest.raises(IOError):
        models.ensure("x2plus", allow_download=False)
    assert not spec.onnx.exists()


# ---------------------------------------------------------------- thumbnail endpoint
def test_thumbnail_paths_must_be_existing_video_files(tmp_path):
    video, other = tmp_path / "clip.MP4", tmp_path / "secrets.txt"
    video.write_bytes(b"x")
    other.write_bytes(b"x")
    assert server.video_file(video) == str(video)
    for bad in (other, tmp_path / "missing.mp4", tmp_path, ""):
        with pytest.raises(ValueError):
            server.video_file(bad)


# ---------------------------------------------------------------- HDR source assumptions
def _probe_hdr(monkeypatch, side_data_json):
    import shutil
    if not shutil.which("ffprobe"):
        pytest.skip("ffprobe not installed")
    real_run = media.run
    calls = []

    def fake_run(args, **kw):
        if "frame=side_data_list" in args:
            calls.append(args)
            return types.SimpleNamespace(returncode=0, stdout=side_data_json, stderr=b"")
        return real_run(args, **kw)
    monkeypatch.setattr(media, "run", fake_run)
    return calls


@pytest.fixture
def hlg_clip(tmp_path):
    import shutil
    import subprocess
    if not shutil.which("ffmpeg"):
        pytest.skip("FFmpeg not installed")
    path = tmp_path / "hlg.mkv"
    subprocess.run(["ffmpeg", "-v", "error", "-f", "lavfi", "-i", "color=size=64x64:duration=0.2",
                    "-pix_fmt", "yuv420p10le", "-color_primaries", "bt2020", "-color_trc", "arib-std-b67",
                    "-colorspace", "bt2020nc", "-c:v", "ffv1", str(path)], check=True, timeout=60)
    return path


def test_missing_hdr_peak_is_recorded_as_an_assumption(monkeypatch, hlg_clip):
    _probe_hdr(monkeypatch, b'{"frames": [{}]}')
    si = media.probe(str(hlg_clip))
    assert si.is_hdr and "قمة سطوع المصدر: 1000 nit" in si.assumed


def test_unreadable_hdr_metadata_is_recorded_not_swallowed(monkeypatch, hlg_clip):
    _probe_hdr(monkeypatch, b"not json")
    si = media.probe(str(hlg_clip))
    assert any(a.startswith("بيانات HDR الوصفية: تعذّرت قراءتها") for a in si.assumed)


def test_declared_hdr_peak_is_not_an_assumption(monkeypatch, hlg_clip):
    _probe_hdr(monkeypatch, b'{"frames": [{"side_data_list": [{"max_content": 1200, "max_average": 300}]}]}')
    si = media.probe(str(hlg_clip))
    assert si.max_cll == 1200 and not any("قمة" in a for a in si.assumed)


# ---------------------------------------------------------------- job-folder caches never fail an export
@pytest.mark.parametrize("content,logged", [
    (None, False),                 # missing file: silent
    ("null", True), ("[]", True), ('"x"', True), ("{broken", True), (b"\xff\xfe", True),
    ('{"key": "other job"}', False),
])
def test_unusable_job_cache_means_recompute(tmp_path, content, logged):
    from hdrstudio.job import read_cache
    path = tmp_path / "state.json"
    if content is not None:
        path.write_bytes(content if isinstance(content, bytes) else content.encode())
    log = []
    assert read_cache(path, "this job", log.append) is None
    assert bool(log) == logged


def test_matching_job_cache_is_used(tmp_path):
    from hdrstudio.job import read_cache
    path = tmp_path / "hdr10plus.json"
    path.write_text('{"key": "k", "meta": {"scenes": []}}', encoding="utf-8")
    assert read_cache(path, "k")["meta"] == {"scenes": []}


@pytest.mark.parametrize("state,ok", [
    ({"key": "k", "chunks": [{"file": "chunk_0001.mp4", "frames": 48}], "maxCll": 900.0, "maxFall": 120, "pipe": {}}, True),
    ({"key": "k", "chunks": [], "maxCll": 0.0, "maxFall": 0.0, "pipe": {}}, True),
    ({"key": "k", "chunks": None, "maxCll": 0.0, "maxFall": 0.0, "pipe": {}}, False),
    ({"key": "k", "chunks": ["chunk_0001.mp4"], "maxCll": 0.0, "maxFall": 0.0, "pipe": {}}, False),
    ({"key": "k", "chunks": [{"file": "..\\..\\x.mp4", "frames": 1}], "maxCll": 0.0, "maxFall": 0.0, "pipe": {}}, False),
    ({"key": "k", "chunks": [], "maxFall": 0.0, "pipe": {}}, False),
    ({"key": "k", "chunks": [], "maxCll": 0.0, "maxFall": 0.0, "pipe": None}, False),
])
def test_resume_state_shape_is_checked_before_use(state, ok):
    from hdrstudio.job import _valid_state
    assert _valid_state(state) is ok
