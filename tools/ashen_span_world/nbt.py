"""Small, dependency-free NBT reader/writer used by the offline world verifier.

The builder deliberately avoids installing a mutable Python dependency during a
release build.  This module supports every vanilla NBT tag so it can inspect
level.dat and 1.20.1 Anvil chunks and write the one SavedData contract file.
"""

from __future__ import annotations

from dataclasses import dataclass
import gzip
import io
import struct
import zlib
from typing import BinaryIO, Iterable


TAG_END = 0
TAG_BYTE = 1
TAG_SHORT = 2
TAG_INT = 3
TAG_LONG = 4
TAG_FLOAT = 5
TAG_DOUBLE = 6
TAG_BYTE_ARRAY = 7
TAG_STRING = 8
TAG_LIST = 9
TAG_COMPOUND = 10
TAG_INT_ARRAY = 11
TAG_LONG_ARRAY = 12


@dataclass(frozen=True)
class Tag:
    kind: int
    value: object


class NbtError(ValueError):
    pass


def _read_exact(stream: BinaryIO, size: int) -> bytes:
    value = stream.read(size)
    if len(value) != size:
        raise NbtError(f"truncated NBT: wanted {size} bytes, got {len(value)}")
    return value


def _unpack(stream: BinaryIO, fmt: str):
    return struct.unpack(">" + fmt, _read_exact(stream, struct.calcsize(">" + fmt)))[0]


def _read_string(stream: BinaryIO) -> str:
    length = _unpack(stream, "H")
    return _read_exact(stream, length).decode("utf-8")


def _read_payload(stream: BinaryIO, kind: int) -> Tag:
    if kind == TAG_BYTE:
        return Tag(kind, _unpack(stream, "b"))
    if kind == TAG_SHORT:
        return Tag(kind, _unpack(stream, "h"))
    if kind == TAG_INT:
        return Tag(kind, _unpack(stream, "i"))
    if kind == TAG_LONG:
        return Tag(kind, _unpack(stream, "q"))
    if kind == TAG_FLOAT:
        return Tag(kind, _unpack(stream, "f"))
    if kind == TAG_DOUBLE:
        return Tag(kind, _unpack(stream, "d"))
    if kind == TAG_BYTE_ARRAY:
        length = _unpack(stream, "i")
        if length < 0:
            raise NbtError("negative byte-array length")
        return Tag(kind, _read_exact(stream, length))
    if kind == TAG_STRING:
        return Tag(kind, _read_string(stream))
    if kind == TAG_LIST:
        child_kind = _unpack(stream, "B")
        length = _unpack(stream, "i")
        if length < 0:
            raise NbtError("negative list length")
        return Tag(kind, (child_kind, [_read_payload(stream, child_kind) for _ in range(length)]))
    if kind == TAG_COMPOUND:
        result: dict[str, Tag] = {}
        while True:
            child_kind = _unpack(stream, "B")
            if child_kind == TAG_END:
                return Tag(kind, result)
            name = _read_string(stream)
            if name in result:
                raise NbtError(f"duplicate compound key {name!r}")
            result[name] = _read_payload(stream, child_kind)
    if kind == TAG_INT_ARRAY:
        length = _unpack(stream, "i")
        if length < 0:
            raise NbtError("negative int-array length")
        return Tag(kind, [_unpack(stream, "i") for _ in range(length)])
    if kind == TAG_LONG_ARRAY:
        length = _unpack(stream, "i")
        if length < 0:
            raise NbtError("negative long-array length")
        return Tag(kind, [_unpack(stream, "q") for _ in range(length)])
    raise NbtError(f"unsupported NBT tag {kind}")


def read(data: bytes, compression: str | None = None) -> tuple[str, Tag]:
    if compression == "gzip":
        data = gzip.decompress(data)
    elif compression == "zlib":
        data = zlib.decompress(data)
    elif compression not in (None, "raw"):
        raise NbtError(f"unsupported NBT compression {compression!r}")
    stream = io.BytesIO(data)
    kind = _unpack(stream, "B")
    if kind == TAG_END:
        raise NbtError("root tag cannot be TAG_End")
    name = _read_string(stream)
    root = _read_payload(stream, kind)
    if stream.read(1):
        raise NbtError("trailing bytes after root NBT tag")
    return name, root


def read_file(path) -> tuple[str, Tag]:
    with open(path, "rb") as stream:
        raw = stream.read()
    compression = "gzip" if raw[:2] == b"\x1f\x8b" else None
    return read(raw, compression)


def _write_string(stream: BinaryIO, value: str) -> None:
    raw = value.encode("utf-8")
    if len(raw) > 65535:
        raise NbtError("NBT string exceeds 65535 encoded bytes")
    stream.write(struct.pack(">H", len(raw)))
    stream.write(raw)


def _write_payload(stream: BinaryIO, tag: Tag) -> None:
    kind, value = tag.kind, tag.value
    if kind == TAG_BYTE:
        stream.write(struct.pack(">b", int(value)))
    elif kind == TAG_SHORT:
        stream.write(struct.pack(">h", int(value)))
    elif kind == TAG_INT:
        stream.write(struct.pack(">i", int(value)))
    elif kind == TAG_LONG:
        stream.write(struct.pack(">q", int(value)))
    elif kind == TAG_FLOAT:
        stream.write(struct.pack(">f", float(value)))
    elif kind == TAG_DOUBLE:
        stream.write(struct.pack(">d", float(value)))
    elif kind == TAG_BYTE_ARRAY:
        raw = bytes(value)
        stream.write(struct.pack(">i", len(raw)))
        stream.write(raw)
    elif kind == TAG_STRING:
        _write_string(stream, str(value))
    elif kind == TAG_LIST:
        child_kind, children = value
        children = list(children)
        stream.write(struct.pack(">Bi", child_kind, len(children)))
        for child in children:
            if child.kind != child_kind:
                raise NbtError("heterogeneous NBT list")
            _write_payload(stream, child)
    elif kind == TAG_COMPOUND:
        for name in sorted(value):
            child = value[name]
            stream.write(struct.pack(">B", child.kind))
            _write_string(stream, name)
            _write_payload(stream, child)
        stream.write(b"\x00")
    elif kind == TAG_INT_ARRAY:
        values = list(value)
        stream.write(struct.pack(">i", len(values)))
        for entry in values:
            stream.write(struct.pack(">i", int(entry)))
    elif kind == TAG_LONG_ARRAY:
        values = list(value)
        stream.write(struct.pack(">i", len(values)))
        for entry in values:
            stream.write(struct.pack(">q", int(entry)))
    else:
        raise NbtError(f"cannot write NBT tag {kind}")


def encode(name: str, root: Tag, compression: str | None = None) -> bytes:
    stream = io.BytesIO()
    stream.write(struct.pack(">B", root.kind))
    _write_string(stream, name)
    _write_payload(stream, root)
    raw = stream.getvalue()
    if compression == "gzip":
        # mtime=0 makes SavedData output reproducible.
        return gzip.compress(raw, compresslevel=9, mtime=0)
    if compression == "zlib":
        return zlib.compress(raw, level=9)
    if compression not in (None, "raw"):
        raise NbtError(f"unsupported NBT compression {compression!r}")
    return raw


def compound(values: dict[str, Tag]) -> Tag:
    return Tag(TAG_COMPOUND, values)


def integer(value: int) -> Tag:
    return Tag(TAG_INT, value)


def long(value: int) -> Tag:
    return Tag(TAG_LONG, value)


def string(value: str) -> Tag:
    return Tag(TAG_STRING, value)


def value_at(root: Tag, *keys: str) -> Tag:
    current = root
    for key in keys:
        if current.kind != TAG_COMPOUND:
            raise NbtError(f"{key!r} traverses non-compound tag")
        try:
            current = current.value[key]
        except KeyError as exc:
            raise NbtError(f"missing NBT key {key!r}") from exc
    return current
