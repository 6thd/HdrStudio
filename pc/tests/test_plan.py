from fractions import Fraction

import pytest

from hdrstudio import media, rife
from hdrstudio.job import PRESETS, QUALITY, Settings, plan


def src(**kw):
    base = dict(path="in.mp4", width=478, height=850, fps=Fraction(30), frames=270, duration=9.0, codec="h264",
                pix_fmt="yuv420p", audio_codec="aac")
    base.update(kw)
    return media.SourceInfo(**base)


def test_x2_hdr_plan():
    p = plan(Settings(input="in.mp4", output="out.mp4", resolution="x2", quality="off"), src())
    assert (p.params.out_w, p.params.out_h) == (956, 1700)
    assert p.enc.hdr and p.enc.codec == "hevc" and p.enc.crf == PRESETS["tiktok_hdr"]["crf"]
    assert p.model is None and not p.interp


def test_output_dimensions_are_even():
    for w, h, res in ((479, 851, "source"), (641, 361, "1080"), (1001, 563, "x2")):
        p = plan(Settings(resolution=res, quality="off"), src(width=w, height=h))
        assert p.params.out_w % 2 == 0 and p.params.out_h % 2 == 0


def test_sdr_preset_caps_resolution():
    p = plan(Settings(preset="tiktok_sdr", resolution="2160", quality="off"), src(width=1920, height=1080))
    assert min(p.params.out_w, p.params.out_h) == 1080 and not p.enc.hdr and p.notes


def test_ai_disabled_for_hdr_source():
    p = plan(Settings(quality="fast"), src(transfer=1, bit_depth=10, pix_fmt="yuv420p10le"))
    assert p.model is None and any("HDR" in n for n in p.notes)


def test_ai_model_per_tier():
    for q, spec in QUALITY.items():
        assert plan(Settings(quality=q), src()).model == spec["model"]


@pytest.mark.skipif(not rife.available(), reason="RIFE weights not present")
def test_frame_doubling_only_up_to_60():
    assert plan(Settings(quality="off", fps_mode="x2"), src()).interp
    p = plan(Settings(quality="off", fps_mode="x2"), src(fps=Fraction(60)))
    assert not p.interp and p.notes


def test_hlg_preset_and_peak_limit():
    p = plan(Settings(preset="tiktok_hlg", quality="off", peak=4000.0), src())
    assert p.enc.hdr and p.enc.trc == "hlg" and p.enc.peak == p.params.peak == 1000.0 and p.notes


def test_hdr10plus_only_with_pq_output():
    assert plan(Settings(preset="tiktok_hdr", quality="off", hdr10plus=True), src()).hdr10plus
    for preset in ("tiktok_hlg", "tiktok_sdr"):
        p = plan(Settings(preset=preset, quality="off", hdr10plus=True), src())
        assert not p.hdr10plus and any("HDR10+" in n for n in p.notes)


def test_settings_ignore_unknown_keys():
    s = Settings.from_dict({"quality": "off", "no_such_key": 1})
    assert s.quality == "off"
