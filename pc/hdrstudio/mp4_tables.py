"""Bounded, read-only validation of unfragmented MP4 sample tables.

This is a structural check, not a complete ISO BMFF conformance validator.
Fragmented movies (moof) and QuickTime reference movies are not supported.
"""
from __future__ import annotations

import struct
from io import BytesIO
from pathlib import Path

MAX_ENTRIES = 2_000_000
CONTAINERS = {b"moov", b"trak", b"mdia", b"minf", b"stbl"}


class TableError(ValueError):
    """The tables are inconsistent: a real defect in the file."""


class LimitError(TableError):
    """The file is beyond this checker's bounds; says nothing about the file being wrong."""


def _u32(data, offset=0):
    if offset + 4 > len(data):
        raise TableError("truncated table")
    return struct.unpack_from(">I", data, offset)[0]


MAX_BOXES = 100_000
# Box types an ISO BMFF / QuickTime file may start with; anything else is another container.
FIRST_BOXES = {b"ftyp", b"styp", b"moov", b"mdat", b"free", b"skip", b"wide", b"pdin", b"uuid", b"sidx", b"pnot"}


def is_iso_bmff(path):
    with Path(path).open("rb") as file:
        head = file.read(8)
    return len(head) == 8 and head[4:8] in FIRST_BOXES


def top_level_boxes(path):
    """Top-level box list (type, offset, size). Raises TableError on a bad boundary."""
    size = Path(path).stat().st_size
    with Path(path).open("rb") as file:
        return [{"type": typ.decode("latin-1"), "offset": at, "size": end - at}
                for typ, at, _, end in _walk(file, 0, size)]


def _boxes(file, start, stop):
    for typ, _, body, end in _walk(file, start, stop):
        yield typ, body, end


def _walk(file, start, stop):
    at, n = start, 0
    while at < stop:
        n += 1
        if n > MAX_BOXES:
            raise LimitError("too many boxes")
        if stop - at < 8:
            raise TableError("truncated box header")
        file.seek(at)
        header = file.read(8)
        length, typ = struct.unpack(">I4s", header)
        head = 8
        if length == 1:
            if stop - at < 16:
                raise TableError("short extended box")
            length = struct.unpack(">Q", file.read(8))[0]
            head = 16
        elif length == 0:
            length = stop - at
        if typ == b"uuid":
            head += 16
        if length < head or length > stop - at:
            raise TableError("invalid box boundary")
        yield typ, at, at + head, at + length
        at += length


def _find(file, start, stop, path):
    if not path:
        return [(start, stop)]
    result = []
    for typ, body, end in _boxes(file, start, stop):
        if typ == path[0]:
            result.extend(_find(file, body, end, path[1:]))
    return result


def _table(file, bounds, name, required=True):
    matches = [pair for typ, *pair in _boxes(file, *bounds) if typ == name]
    if len(matches) != 1:
        if not matches and not required:
            return None
        raise TableError("expected one " + name.decode("ascii"))
    begin, end = matches[0]
    if end - begin > 12 + MAX_ENTRIES * 16:
        raise LimitError("table exceeds entry limit")
    file.seek(begin)
    return file.read(end - begin)


def _entries(data, stride):
    if data is None:
        return None
    if len(data) < 8:
        raise TableError("short table")
    count = _u32(data, 4)
    if count > MAX_ENTRIES:
        raise LimitError("table entry count exceeds limit")
    if len(data) != 8 + stride * count:
        raise TableError("table entry count/length mismatch")
    return count


def _sizes(data):
    """(count, per-sample sizes or None, constant size). A constant-size stsz is 12 bytes
    whatever its count (QuickTime LPCM has one sample per audio frame), so it has no limit
    and its sizes are never materialised."""
    if data is None or len(data) < 12:
        raise TableError("missing stsz")
    uniform, count = _u32(data, 4), _u32(data, 8)
    if uniform:
        if len(data) != 12:
            raise TableError("invalid constant stsz")
        return count, None, uniform
    if count > MAX_ENTRIES:
        raise LimitError("too many samples")
    if len(data) != 12 + 4 * count:
        raise TableError("stsz length mismatch")
    return count, list(struct.unpack_from(">" + "I" * count, data, 12)) if count else [], 0


def _compact_sizes(data):
    if len(data) < 12:
        raise TableError("short stz2")
    field, count = data[7], _u32(data, 8)
    if field not in (4, 8, 16):
        raise TableError("invalid stz2 field size")
    if count > MAX_ENTRIES:
        raise LimitError("too many samples")
    expected = 12 + (count * field + 7) // 8
    if len(data) != expected:
        raise TableError("stz2 length mismatch")
    raw = data[12:]
    if field == 4:
        # Expand nibbles in on-disk order.
        sizes = [raw[i // 2] >> 4 if i % 2 == 0 else raw[i // 2] & 15 for i in range(count)]
    elif field == 8:
        sizes = list(raw)
    else:
        sizes = list(struct.unpack(">" + "H" * count, raw)) if count else []
    return count, sizes, 0




def _sample_descriptions(data):
    """Count complete, bounded stsd sample-entry boxes, not just the declared entry_count.

    Entries are opaque here: this verifies their framing and number, without claiming
    to validate every codec-specific sample-entry field.
    """
    if data is None or len(data) < 8:
        raise TableError("missing sample descriptions")
    declared = _u32(data, 4)
    if not declared:
        raise TableError("missing sample descriptions")
    if declared > MAX_BOXES:
        raise LimitError("sample descriptions exceed checker limit")
    actual = 0
    for _typ, _body, _end in _boxes(BytesIO(data), 8, len(data)):
        actual += 1
        if actual > declared:
            raise TableError("stsd entry count does not match sample entries")
    if actual != declared:
        raise TableError("stsd entry count does not match sample entries")
    return declared


def _offsets(data, width):
    count = _entries(data, width)
    return [struct.unpack_from(">I" if width == 4 else ">Q", data, 8 + i * width)[0]
            for i in range(count)]


def validate(path):
    """Return PASS or NOT_VERIFIED for unsupported layout; raise on malformed tables."""
    path = Path(path)
    size = path.stat().st_size
    with path.open("rb") as file:
        roots = list(_boxes(file, 0, size))
        if any(typ == b"moof" for typ, _, _ in roots):
            return {"status": "NOT_VERIFIED", "reason": "fragmented_mp4_unsupported"}
        mdat = [(begin, end) for typ, begin, end in roots if typ == b"mdat"]
        if not mdat:
            return {"status": "NOT_VERIFIED", "reason": "no_mdat"}
        tracks = _find(file, 0, size, [b"moov", b"trak", b"mdia", b"minf", b"stbl"])
        if not tracks:
            raise TableError("no sample tables")
        totals = []
        for idx, bounds in enumerate(tracks):
            sz = _table(file, bounds, b"stsz", False)
            z2 = _table(file, bounds, b"stz2", False)
            if (sz is None) == (z2 is None):
                raise TableError("exactly one stsz/stz2 required")
            n, sizes, uniform = _sizes(sz) if sz is not None else _compact_sizes(z2)
            stsd = _table(file, bounds, b"stsd")
            descriptions = _sample_descriptions(stsd)
            sc = _table(file, bounds, b"stsc")
            nsc = _entries(sc, 12)
            mapping = [struct.unpack_from(">III", sc, 8 + 12*i) for i in range(nsc)]
            if not mapping:
                if n:  # an empty track (no samples, no chunks) legitimately has no stsc entries
                    raise TableError("samples without stsc entries")
            elif mapping[0][0] != 1:
                raise TableError("stsc must start at chunk 1")
            if any(a >= b or spc < 1 or desc < 1 for (a, _, _), (b, spc, desc)
                   in zip(mapping, mapping[1:])):
                raise TableError("invalid stsc")
            if any(spc < 1 or not 1 <= desc <= descriptions for _, spc, desc in mapping):
                raise TableError("empty chunk description")
            co = _table(file, bounds, b"stco", False)
            c64 = _table(file, bounds, b"co64", False)
            if (co is None) == (c64 is None):
                raise TableError("exactly one stco/co64 required")
            offsets = _offsets(co if co is not None else c64, 4 if co is not None else 8)
            if n and not offsets:
                raise TableError("samples without chunks")
            if not n and offsets:
                raise TableError("chunks without samples")
            if mapping and offsets and mapping[-1][0] > len(offsets):
                raise TableError("stsc beyond chunk table")
            stts = _table(file, bounds, b"stts")
            nt = _entries(stts, 8)
            count_time = sum(_u32(stts, 8 + i * 8) for i in range(nt))
            if count_time != n:
                raise TableError("stts total does not match stsz")
            ctts = _table(file, bounds, b"ctts", False)
            if ctts is not None:
                nc = _entries(ctts, 8)
                if sum(_u32(ctts, 8 + i * 8) for i in range(nc)) != n:
                    raise TableError("ctts total does not match stsz")
            stss = _table(file, bounds, b"stss", False)
            if stss is not None:
                ns = _entries(stss, 4)
                for i in range(ns):
                    sample = _u32(stss, 8 + 4*i)
                    if not 1 <= sample <= n:
                        raise TableError("stss sample index out of range")
            cursor = 0
            run = 0
            for chunk_no, offset in enumerate(offsets, 1):
                while run + 1 < len(mapping) and mapping[run+1][0] <= chunk_no:
                    run += 1
                take = mapping[run][1]
                if cursor + take > n:
                    raise TableError("chunk/sample count mismatch")
                end = offset + (uniform * take if sizes is None else sum(sizes[cursor:cursor+take]))
                if not any(start <= offset <= end <= stop for start, stop in mdat):
                    raise TableError("chunk references bytes outside mdat")
                cursor += take
            if cursor != n:
                raise TableError("sample count mismatch")
            totals.append({"track": idx + 1, "samples": n, "chunks": len(offsets)})
    return {"status": "PASS", "scope": "unfragmented table structure and mdat bounds",
            "tracks": totals, "full_iso_bmff_conformance": "NOT_VERIFIED"}
