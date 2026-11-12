"""Model conversion checked on the CPU runtime (no GPU needed)."""
import numpy as np
import pytest

from hdrstudio import ai, models, rife


def test_general_onnx_matches_reference_vector(tmp_path, monkeypatch):
    spec = models.REGISTRY["general"]
    out = tmp_path / "general.onnx"
    models.build_srvgg(models.MODEL_DIR / spec.source, out, scale=spec.scale)
    monkeypatch.setattr(type(spec), "onnx", property(lambda self: out))
    err = ai.reference_check(spec)
    assert err is not None and err < 1e-3


def test_rife_padding():
    assert [rife.padded(n) for n in (1, 32, 33, 1080, 1700)] == [32, 32, 64, 1088, 1728]


@pytest.mark.skipif(not rife.available(), reason="RIFE weights not present")
def test_rife_onnx_static_scene_and_motion(tmp_path):
    import onnxruntime as ort
    w, h = 128, 96
    path = tmp_path / "rife.onnx"
    rife.build_onnx(path, w, h)
    s = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
    a, b = rife._test_pair(w, h)[:2]
    same = s.run(None, {"in0": a, "in1": a})[0]
    assert same.shape == a.shape
    assert float(np.abs(same - a).mean()) < 0.01                 # no motion: the middle frame is the frame
    mid = s.run(None, {"in0": a, "in1": b})[0]
    assert np.isfinite(mid).all()
    assert float(np.abs(mid - (a + b) / 2).mean()) > 1e-4        # it interpolates, it does not just average
