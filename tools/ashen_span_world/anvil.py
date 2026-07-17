"""Fail-closed Anvil inventory, crop, and block-state helpers."""

from __future__ import annotations

from dataclasses import dataclass
import math
from pathlib import Path
import re
import struct
import zlib
import gzip

from . import nbt


SECTOR_BYTES = 4096
HEADER_BYTES = 8192
REGION_RE = re.compile(r"^r\.(-?\d+)\.(-?\d+)\.mca$")


class AnvilError(ValueError):
    pass


@dataclass(frozen=True)
class Entry:
    chunk_x: int
    chunk_z: int
    compression: int
    payload: bytes
    record: bytes


def _region_coords(path: Path) -> tuple[int, int]:
    match = REGION_RE.match(path.name)
    if not match:
        raise AnvilError(f"not an Anvil region name: {path.name}")
    return int(match.group(1)), int(match.group(2))


def read_entries(path: Path) -> dict[tuple[int, int], Entry]:
    raw = path.read_bytes()
    if len(raw) < HEADER_BYTES:
        raise AnvilError(f"invalid region size for {path}: {len(raw)}")
    rx, rz = _region_coords(path)
    result: dict[tuple[int, int], Entry] = {}
    occupied: set[int] = {0, 1}
    for index in range(1024):
        location = int.from_bytes(raw[index * 4:index * 4 + 4], "big")
        offset, sectors = location >> 8, location & 0xFF
        if offset == 0 and sectors == 0:
            continue
        available_sectors = math.ceil(len(raw) / SECTOR_BYTES)
        if offset < 2 or sectors < 1 or offset + sectors > available_sectors:
            raise AnvilError(f"out-of-range location in {path} index {index}")
        claimed = set(range(offset, offset + sectors))
        if occupied.intersection(claimed):
            raise AnvilError(f"overlapping sectors in {path} index {index}")
        occupied.update(claimed)
        start = offset * SECTOR_BYTES
        length = struct.unpack(">I", raw[start:start + 4])[0]
        if length < 1 or length + 4 > sectors * SECTOR_BYTES:
            raise AnvilError(f"invalid record length in {path} index {index}")
        if start + 4 + length > len(raw):
            raise AnvilError(f"incomplete record write in {path} index {index}")
        compression = raw[start + 4]
        if compression & 0x80:
            raise AnvilError(f"external chunk storage is forbidden: {path} index {index}")
        if compression not in (1, 2, 3):
            raise AnvilError(f"unsupported compression {compression} in {path}")
        payload = raw[start + 5:start + 4 + length]
        local_x, local_z = index & 31, index >> 5
        chunk_x, chunk_z = rx * 32 + local_x, rz * 32 + local_z
        result[(chunk_x, chunk_z)] = Entry(
            chunk_x, chunk_z, compression, payload, raw[start:start + 4 + length]
        )
    return result


def unpack_nbt(entry: Entry) -> nbt.Tag:
    if entry.compression == 1:
        raw = gzip.decompress(entry.payload)
    elif entry.compression == 2:
        raw = zlib.decompress(entry.payload)
    else:
        raw = entry.payload
    _, root = nbt.read(raw)
    if root.kind != nbt.TAG_COMPOUND:
        raise AnvilError("chunk root is not a compound")
    return root


def inventory(directory: Path) -> dict[tuple[int, int], Entry]:
    result: dict[tuple[int, int], Entry] = {}
    if not directory.exists():
        return result
    for path in sorted(directory.glob("r.*.*.mca")):
        for coord, entry in read_entries(path).items():
            if coord in result:
                raise AnvilError(f"duplicate chunk {coord} in {directory}")
            result[coord] = entry
    unknown = [p.name for p in directory.iterdir() if p.is_file() and p.suffix == ".mca" and not REGION_RE.match(p.name)]
    if unknown:
        raise AnvilError(f"unrecognized region files: {unknown}")
    return result


def crop_directory(source: Path, destination: Path, allowed: set[tuple[int, int]]) -> None:
    destination.mkdir(parents=True, exist_ok=True)
    grouped: dict[tuple[int, int], list[Entry]] = {}
    source_entries = inventory(source)
    missing = allowed.difference(source_entries)
    if source.name == "region" and missing:
        sample = sorted(missing)[:8]
        raise AnvilError(f"terrain pre-generation missing {len(missing)} chunks, including {sample}")
    for coord in sorted(allowed.intersection(source_entries)):
        entry = source_entries[coord]
        grouped.setdefault((entry.chunk_x // 32, entry.chunk_z // 32), []).append(entry)
    for (rx, rz), entries in sorted(grouped.items()):
        _write_region(destination / f"r.{rx}.{rz}.mca", rx, rz, entries)


def _write_region(path: Path, rx: int, rz: int, entries: list[Entry]) -> None:
    header = bytearray(HEADER_BYTES)  # timestamps intentionally canonicalized to zero
    body = bytearray()
    next_sector = 2
    for entry in sorted(entries, key=lambda e: ((e.chunk_z & 31) << 5) | (e.chunk_x & 31)):
        index = ((entry.chunk_z - rz * 32) << 5) | (entry.chunk_x - rx * 32)
        if not 0 <= index < 1024:
            raise AnvilError(f"chunk {(entry.chunk_x, entry.chunk_z)} does not belong to region {(rx, rz)}")
        sectors = math.ceil(len(entry.record) / SECTOR_BYTES)
        if sectors > 255:
            raise AnvilError(f"oversized chunk {(entry.chunk_x, entry.chunk_z)}")
        header[index * 4:index * 4 + 4] = ((next_sector << 8) | sectors).to_bytes(4, "big")
        body.extend(entry.record)
        body.extend(b"\x00" * (sectors * SECTOR_BYTES - len(entry.record)))
        next_sector += sectors
    path.write_bytes(bytes(header + body))


def status(entry: Entry) -> str:
    root = unpack_nbt(entry)
    tag = root.value.get("Status")
    if tag is None or tag.kind != nbt.TAG_STRING:
        return ""
    return str(tag.value).removeprefix("minecraft:")


def chunk_metadata(entry: Entry) -> tuple[int, int, int, str]:
    """Return the baked chunk identity needed by the release verifier."""
    root = unpack_nbt(entry)
    try:
        data_version = root.value["DataVersion"]
        x_pos = root.value["xPos"]
        z_pos = root.value["zPos"]
        status_tag = root.value["Status"]
    except KeyError as exc:
        raise AnvilError(f"chunk {(entry.chunk_x, entry.chunk_z)} is missing {exc.args[0]}") from exc
    if data_version.kind != nbt.TAG_INT or x_pos.kind != nbt.TAG_INT or z_pos.kind != nbt.TAG_INT:
        raise AnvilError(f"chunk {(entry.chunk_x, entry.chunk_z)} has malformed identity tags")
    if status_tag.kind != nbt.TAG_STRING:
        raise AnvilError(f"chunk {(entry.chunk_x, entry.chunk_z)} has malformed Status")
    return (
        int(data_version.value),
        int(x_pos.value),
        int(z_pos.value),
        str(status_tag.value).removeprefix("minecraft:"),
    )


def palette_names_from_root(root: nbt.Tag) -> set[str]:
    if root.kind != nbt.TAG_COMPOUND:
        raise AnvilError("chunk root is not a compound")
    sections_tag = root.value.get("sections")
    if sections_tag is None or sections_tag.kind != nbt.TAG_LIST:
        raise AnvilError("chunk has no sections list")
    names: set[str] = set()
    for section in sections_tag.value[1]:
        if section.kind != nbt.TAG_COMPOUND:
            raise AnvilError("chunk section is not a compound")
        states = section.value.get("block_states")
        if states is None:
            continue
        if states.kind != nbt.TAG_COMPOUND:
            raise AnvilError("section block_states is not a compound")
        palette = states.value.get("palette")
        if palette is None or palette.kind != nbt.TAG_LIST:
            raise AnvilError("section block_states has no palette")
        for entry in palette.value[1]:
            if entry.kind != nbt.TAG_COMPOUND:
                raise AnvilError("block palette entry is not a compound")
            name = entry.value.get("Name")
            if name is None or name.kind != nbt.TAG_STRING:
                raise AnvilError("block palette entry has no Name")
            names.add(str(name.value))
    return names


def block_state_from_root(root: nbt.Tag, x: int, y: int, z: int) -> tuple[str, dict[str, str]]:
    """Read a block state from an already-decoded chunk root.

    Release validation checks thousands of authored gate and route cells.  Keeping
    decoding separate lets the verifier cache one NBT tree per chunk instead of
    repeatedly inflating the same Anvil payload.
    """
    if root.kind != nbt.TAG_COMPOUND:
        raise AnvilError("chunk root is not a compound")
    sections_tag = root.value.get("sections")
    if sections_tag is None or sections_tag.kind != nbt.TAG_LIST:
        raise AnvilError("chunk has no sections list")
    _, sections = sections_tag.value
    section_y = y >> 4
    section = None
    for candidate in sections:
        if candidate.kind != nbt.TAG_COMPOUND:
            continue
        y_tag = candidate.value.get("Y")
        if y_tag is not None and int(y_tag.value) == section_y:
            section = candidate
            break
    if section is None:
        return "minecraft:air", {}
    states = section.value.get("block_states")
    if states is None or states.kind != nbt.TAG_COMPOUND:
        return "minecraft:air", {}
    palette_tag = states.value.get("palette")
    if palette_tag is None or palette_tag.kind != nbt.TAG_LIST:
        raise AnvilError("section block_states has no palette")
    _, palette = palette_tag.value
    if not palette:
        raise AnvilError("empty block palette")
    palette_index = 0
    if len(palette) > 1:
        data_tag = states.value.get("data")
        if data_tag is None or data_tag.kind != nbt.TAG_LONG_ARRAY:
            raise AnvilError("multi-value block palette has no packed data")
        bits = max(4, (len(palette) - 1).bit_length())
        per_long = 64 // bits
        local_index = ((y & 15) * 16 + (z & 15)) * 16 + (x & 15)
        packed_index, shift = divmod(local_index, per_long)
        values = data_tag.value
        if packed_index >= len(values):
            raise AnvilError("packed block-state data is truncated")
        unsigned = int(values[packed_index]) & ((1 << 64) - 1)
        palette_index = (unsigned >> (shift * bits)) & ((1 << bits) - 1)
        if palette_index >= len(palette):
            raise AnvilError("packed block-state palette index is invalid")
    selected = palette[palette_index]
    if selected.kind != nbt.TAG_COMPOUND:
        raise AnvilError("block palette entry is not a compound")
    name_tag = selected.value.get("Name")
    if name_tag is None or name_tag.kind != nbt.TAG_STRING:
        raise AnvilError("block palette entry has no Name")
    props: dict[str, str] = {}
    properties = selected.value.get("Properties")
    if properties is not None:
        if properties.kind != nbt.TAG_COMPOUND:
            raise AnvilError("block Properties is not a compound")
        props = {key: str(value.value) for key, value in properties.value.items()}
    return str(name_tag.value), props


def block_state(entry: Entry, x: int, y: int, z: int) -> tuple[str, dict[str, str]]:
    return block_state_from_root(unpack_nbt(entry), x, y, z)
