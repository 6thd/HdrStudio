import pytest
from hdrstudio.hevc_sei import nal_units, parse_sei, unescape_rbsp


def test_nal_type_and_sei_content_light():
    sei = b"\x90\x04\x00\xc8\x00\x64\x80"
    encoded = b"\x00\x00\x00\x01" + bytes([39 << 1, 1]) + sei
    nal = list(nal_units(encoded))
    assert len(nal) == 1
    assert (nal[0][0] >> 1) & 63 == 39
    assert parse_sei(nal[0][2:]) == [
        {"type": "content_light_level", "max_cll": 200, "max_fall": 100}]


def test_mastering_display_and_t35_candidate():
    mastering = bytes([137, 24]) + bytes(24)
    dynamic = bytes([4, 3, 0xb5, 0, 0x3c])
    events = parse_sei(mastering + dynamic + b"\x80")
    assert events[0]["type"] == "mastering_display_colour_volume"
    assert events[1]["hdr10plus"] == "CANDIDATE_NOT_VALIDATED"


def test_unescape_prevention():
    assert unescape_rbsp(b"\x00\x00\x03\x01") == b"\x00\x00\x01"


def test_truncated_sei_rejected():
    with pytest.raises(ValueError, match="truncated"):
        parse_sei(bytes([144, 10, 0, 1]))
