"""hdr_map.frag on HDR sources: output never exceeds the declared mastering peak (uPeak).

Runs the real shader through GlKit. Needs an OpenGL 3.3 context; headless machines fall back to
EGL (Mesa llvmpipe works). Skipped where no context can be created.
"""
import numpy as np
import pytest

M1, M2 = 2610 / 16384, 2523 / 4096 * 128
C1, C2, C3 = 3424 / 4096, 2413 / 4096 * 32, 2392 / 4096 * 32


def pq_encode(nits):
    y = np.clip(np.asarray(nits, np.float64) / 10000.0, 0, 1) ** M1
    return ((C1 + C2 * y) / (1 + C3 * y)) ** M2


def pq_decode(e):
    p = np.clip(np.asarray(e, np.float64), 0, 1) ** (1 / M2)
    return 10000.0 * (np.maximum(p - C1, 0) / (C2 - C3 * p)) ** (1 / M1)


@pytest.fixture(scope="module")
def kit():
    moderngl = pytest.importorskip("moderngl")
    from hdrstudio import glkit
    try:
        return glkit.GlKit()
    except Exception:
        pass
    real = moderngl.create_standalone_context
    try:
        glkit.moderngl.create_standalone_context = lambda **kw: real(backend="egl", **kw)
        return glkit.GlKit()
    except Exception as e:
        pytest.skip(f"no OpenGL 3.3 context: {e}")
    finally:
        glkit.moderngl.create_standalone_context = real


def run_hdr(kit, rgb_nits, peak, src_peak):
    """Map PQ/BT.2020 pixels (nits) from an HDR source to HDR10 output; return output nits."""
    px = np.asarray(rgb_nits, np.float32)
    data = np.concatenate([pq_encode(px), np.ones((len(px), 1))], axis=1).astype(np.float32)
    src = kit.tex(len(px), 1, nearest=True, data=data.tobytes(), dtype="f4")
    dst = kit.tex(len(px), 1, nearest=True, dtype="f4")
    kit.run("hdr_map.frag", dst, {
        "uIn": src, "uDriveRadius": 0.0, "uInDomain": 1, "uOutMode": 0, "uSdrGamma": 0,
        "uRefWhite": 203.0, "uPeak": float(peak), "uExpand": 0.8, "uKnee": 0.6, "uVibrance": 0.0,
        "uSkinProtect": 0.8, "uSrcPeak": float(src_peak), "uSdrPeakNits": 250.0})
    out = pq_decode(kit.read(dst)[0, :, :3])
    kit.delete(src)
    kit.delete(dst)
    return out


def test_processing_overshoot_is_held_under_the_declared_peak(kit):
    """The reported export: HLG source (1000 nit), sharpening pushed bright edges to 1219 nits."""
    px = [[1219, 1219, 1219], [1219, 300, 100], [1000, 1000, 1000], [500, 400, 300], [100, 100, 100]]
    out = run_hdr(kit, px, peak=1000, src_peak=1000)
    assert out.max() <= 1000 * 1.005                      # MaxCLL can no longer exceed the peak
    assert out[2].min() >= 975                            # source white barely touched (roll-off from 950)
    np.testing.assert_allclose(out[3:], px[3:], rtol=0.01)  # everything below the knee is identity
    hue = lambda c: c / c.max()
    np.testing.assert_allclose(hue(out[1])[0], 1.0)          # the coloured highlight keeps its dominant channel


def test_source_mastered_above_the_peak_is_tone_mapped_down(kit):
    px = [[4000, 4000, 4000], [6000, 6000, 6000], [2000, 1500, 800], [100, 100, 100]]
    out = run_hdr(kit, px, peak=1000, src_peak=4000)
    assert out.max() <= 1000 * 1.005
    assert out[0].min() >= 990                            # the source peak lands on the target peak
    np.testing.assert_allclose(out[3], px[3], rtol=0.01)  # dark/mid tones untouched by BT.2390
    assert out[2, 0] > out[2, 1] > out[2, 2]              # channel order (hue) preserved


def test_content_well_below_a_higher_peak_is_unchanged(kit):
    px = [[1219, 1219, 1219], [800, 300, 100]]
    out = run_hdr(kit, px, peak=4000, src_peak=1000)
    np.testing.assert_allclose(out, px, rtol=0.01)
