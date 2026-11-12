from fractions import Fraction

from hdrstudio import media


def test_cll_values_never_need_emulation_prevention():
    for cll in (1, 255, 256, 512, 1000, 4000, 65535):
        for fall in (1, 2, 3, 4, 400):
            b = media._cll_bytes(cll, fall)
            assert len(b) == 4
            for bad in (b"\x00\x00\x00", b"\x00\x00\x01", b"\x00\x00\x02", b"\x00\x00\x03"):
                assert bad not in b
            assert media.safe_cll(cll, fall) == ((b[0] << 8) | b[1], (b[2] << 8) | b[3])


def test_cll_clamped():
    assert media.safe_cll(0, 0) == (1, 1)
    assert media.safe_cll(10 ** 6, 10 ** 6) == (65535, 65535)


def test_patch_cll_in_place(tmp_path):
    sei = media._CLL_HEAD + media._cll_bytes(*media.PLACEHOLDER_CLL) + bytes([0x80])
    other = media._CLL_HEAD + media._cll_bytes(1234, 99) + bytes([0x80])       # not the placeholder: untouched
    data = b"A" * 100 + sei + b"B" * 50 + other + b"C" * 7 + sei
    f = tmp_path / "x.bin"
    f.write_bytes(data)
    assert media.patch_cll(f, 728, 84) == 2
    out = f.read_bytes()
    assert len(out) == len(data)
    new = media._CLL_HEAD + bytes([728 >> 8, 728 & 255, 0, 84, 0x80])
    assert out.count(new) == 2 and out.count(other) == 1 and sei not in out
    assert media.patch_cll(f, 500, 50) == 0                                    # nothing left to patch


def _cfg(**kw):
    base = dict(width=1920, height=1080, fps=Fraction(30), codec="hevc", hdr=True, bit_depth=10, crf=15, preset="slow", peak=1000.0)
    base.update(kw)
    return media.EncodeConfig(**base)


def test_hdr10_encoder_arguments():
    a = media.encoder_args(_cfg(), "out.mp4")
    x = a[a.index("-x265-params") + 1].split(":")
    for want in ("hdr10=1", "colorprim=bt2020", "transfer=smpte2084", "colormatrix=bt2020nc", "open-gop=0", "repeat-headers=1", "keyint=60"):
        assert want in x
    assert any(p.startswith("master-display=") for p in x)
    assert "max-cll=%d,%d" % media.PLACEHOLDER_CLL in x
    assert a[a.index("-profile:v") + 1] == "main10" and a[a.index("-tag:v") + 1] == "hvc1"
    assert a[a.index("-color_trc") + 1] == "smpte2084"


def test_sdr_h264_encoder_arguments():
    a = media.encoder_args(_cfg(codec="h264", hdr=False, bit_depth=8), "out.mp4")
    assert "libx264" in a and "-x265-params" not in a
    assert "colorprim=bt709" in a[a.index("-x264-params") + 1]
    assert a[a.index("-color_primaries") + 1] == "bt709"
