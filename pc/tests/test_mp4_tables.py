"""Bounded MP4 sample-table acceptance and negative controls."""
import shutil
import struct
import subprocess

import pytest

from hdrstudio.mp4_tables import TableError, validate


def box(tag, body=b""):
    return struct.pack(">I4s", len(body) + 8, tag) + body


def sample_mp4(*, offset=8, size=4, time_count=1, first_chunk=1,
               fragmented=False, extra_trak=b"", description_count=1,
               sample_entries=(b"avc1",), description_index=1):
    full = b"\x00" * 4
    stsd = box(b"stsd", full + struct.pack(">I", description_count) +
               b"".join(box(kind) for kind in sample_entries))
    stsz = box(b"stsz", full + struct.pack(">II", size, 1))
    stsc = box(b"stsc", full + struct.pack(">I", 1) +
               struct.pack(">III", first_chunk, 1, description_index))
    stco = box(b"stco", full + struct.pack(">II", 1, offset))
    stts = box(b"stts", full + struct.pack(">I", 1) +
               struct.pack(">II", time_count, 100))
    stbl = box(b"stbl", stsd + stsz + stsc + stco + stts)
    moov = box(b"moov", box(b"trak", box(b"mdia", box(b"minf", stbl))) + extra_trak)
    return box(b"mdat", b"ABCD") + (box(b"moof") if fragmented else b"") + moov


def test_synthetic_sample_table(tmp_path):
    path = tmp_path / "valid.mp4"
    path.write_bytes(sample_mp4())
    result = validate(path)
    assert result["status"] == "PASS"
    assert result["tracks"][0] == {"track": 1, "samples": 1, "chunks": 1}
    assert result["full_iso_bmff_conformance"] == "NOT_VERIFIED"


@pytest.mark.parametrize("alteration", [
    {"offset": 200}, {"offset": 6}, {"size": 500},
    {"time_count": 2}, {"first_chunk": 2}, {"first_chunk": 0},
])
def test_invalid_sample_tables(tmp_path, alteration):
    path = tmp_path / "invalid.mp4"
    path.write_bytes(sample_mp4(**alteration))
    with pytest.raises(TableError):
        validate(path)


def test_fragmented_is_not_claimed_valid(tmp_path):
    path = tmp_path / "fragmented.mp4"
    path.write_bytes(sample_mp4(fragmented=True))
    assert validate(path)["status"] == "NOT_VERIFIED"


def test_actual_ffmpeg_mp4(tmp_path):
    if not shutil.which("ffmpeg"):
        pytest.skip("FFmpeg not installed")
    path = tmp_path / "real.mp4"
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error",
                    "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=10:duration=1",
                    "-c:v", "mpeg4", "-y", str(path)], check=True, timeout=60)
    assert validate(path)["status"] == "PASS"


def test_empty_track_is_valid(tmp_path):
    """A track with zero samples has empty stsz/stsc/stco/stts tables (review of PR #8)."""
    full = b"\x00" * 4
    zero = struct.pack(">I", 0)
    empty = box(b"stbl", box(b"stsd", full + struct.pack(">I", 1) + box(b"mp4a")) +
                box(b"stsz", full + struct.pack(">II", 0, 0)) + box(b"stsc", full + zero) +
                box(b"stco", full + zero) + box(b"stts", full + zero))
    path = tmp_path / "empty_track.mp4"
    path.write_bytes(sample_mp4(extra_trak=box(b"trak", box(b"mdia", box(b"minf", empty)))))
    result = validate(path)
    assert result["status"] == "PASS"
    assert result["tracks"][-1] == {"track": 2, "samples": 0, "chunks": 0}


# Codex P2: validate the physical sample entries, not just stsd.entry_count.
def test_stsd_two_actual_entries_allow_second_description(tmp_path):
    path = tmp_path / "two_descriptions.mp4"
    path.write_bytes(sample_mp4(description_count=2,
                                sample_entries=(b"avc1", b"hvc1"),
                                description_index=2))
    assert validate(path)["status"] == "PASS"


@pytest.mark.parametrize("count,entries,reference", [
    (2, (b"avc1",), 2),              # advertised second description is nonexistent
    (2, (b"avc1",), 1),              # even a reference to entry 1 must not mask the bad stsd
    (1, (b"avc1", b"hvc1"), 1),     # undeclared trailing sample entry
])
def test_stsd_declared_entries_must_match_physical_boxes(tmp_path, count, entries, reference):
    path = tmp_path / "bad_stsd.mp4"
    path.write_bytes(sample_mp4(description_count=count, sample_entries=entries,
                                description_index=reference))
    with pytest.raises(TableError, match="stsd entry count"):
        validate(path)


def test_stsd_sample_entry_box_must_be_complete(tmp_path):
    path = tmp_path / "short_sample_entry.mp4"
    data = bytearray(sample_mp4())
    pos = data.index(b"avc1")
    data[pos - 4:pos] = struct.pack(">I", 6)
    path.write_bytes(data)
    with pytest.raises(TableError, match="invalid box boundary"):
        validate(path)
