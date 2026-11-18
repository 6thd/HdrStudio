import struct

import pytest

from hdrstudio.platform_lab import compare, inspect_boxes


def test_boxes(tmp_path):
    path = tmp_path / "test.mp4"
    path.write_bytes(struct.pack(">I4s", 8, b"ftyp") +
                     struct.pack(">I4sQ", 1, b"moov", 16) +
                     struct.pack(">I4s", 0, b"mdat") + b"data")
    assert [b["size"] for b in inspect_boxes(path)] == [8, 16, 12]


@pytest.mark.parametrize("data", [b"x", struct.pack(">I4s", 4, b"mdat"),
                                  struct.pack(">I4s", 99, b"mdat"),
                                  struct.pack(">I4s", 1, b"mdat"),
                                  struct.pack(">I4s", 8, b"uuid")])
def test_invalid_boxes(tmp_path, data):
    path = tmp_path / "bad.mp4"
    path.write_bytes(data)
    with pytest.raises(ValueError):
        inspect_boxes(path)


def test_compare_does_not_claim_hdr_preservation():
    before = {"streams": [{"codec_type": "video", "color_transfer": "arib-std-b67"}]}
    after = {"streams": [{"codec_type": "video", "color_transfer": "bt709"}]}
    assert compare(before, after)["signals_equal"] is False
    assert compare(before, before)["hdr_preservation"] == "NOT_VERIFIED"
    assert compare({}, {})["signals_equal"] is None
