#!/usr/bin/env python3
"""Build the isolated Operation Ashen Span mp.25/RC6 offline candidate.

The builder consumes explicit, already-produced inputs and never downloads or
launches Minecraft.  It derives the client from the immutable mp.24 RC4
MRPack, emits one matched server overlay and one bounded world archive, and
publishes through a new sibling staging directory.  Existing candidates and
release aliases are never opened for writing.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath, PureWindowsPath
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import tomllib
import zipfile

from ashen_span_world import builder as world_builder


BASE_SHA256 = "6847C8856E821B211AEE9B0D6EF7AE45C3DF24079BC0FCCAD556A5E1BD9665CF"
BASE_BYTES = 10_766_505
BASE_VERSION_ID = "0.8.0-solo-combat-mp24-rc4"
BASE_MOD_NAME = "pomkotsmechs-forge-0.0.1-alpha.7-mp.24.jar"
BASE_MOD_PATH = f"overrides/mods/{BASE_MOD_NAME}"
BASE_MOD_SHA256 = "150E485526FC7FCC4A9BCEAE447D827E5194EBE6D7AEA5D06706D2DFC22552FA"

LOST_CITIES_NAME = "lostcities-1.20-7.4.13.jar"
LOST_CITIES_SHA256 = "DA5AE1B0C0D0C8066F2971C9ADB57D18657C4844AAD6CBA4F9F07F7946E30A95"
LOST_CITIES_BYTES = 1_254_448
LOST_CITIES_VERSION = "1.20-7.4.13"
MP25_VERSION = "0.0.1-alpha.7-mp.25"
MP25_NAME = f"pomkotsmechs-forge-{MP25_VERSION}.jar"
ASSET_NAME = "mecharena_sector01-1.0.0-mp25.jar"
ASSET_VERSION = "1.0.0-mp25"
ASSET_PROFILE_MEMBER = "config-template/lostcities/profiles/mecharena_sector01.json"
ASSET_PROFILE_BYTES = 1_861
ASSET_PROFILE_SHA256 = "836B07B6493F4381587960E592058E9C78A9C71118FAB3597796E040172F9018"

CANDIDATE_ID = "operation-ashen-span-mp25-rc6"
VERSION_ID = "0.8.0-operation-ashen-span-mp25-rc6"
DISPLAY_NAME = "Mech Arena 0.8 - Operation Ashen Span MP25 RC6"
SUMMARY = (
    "Offline Cold Ruin Sector 01 vertical slice with the authored Operation "
    "Ashen Span mission, bounded Lost Cities world, and matched mp.25 server; "
    "live qualification pending."
)
MRPACK_NAME = "mech-arena-0.8.0-operation-ashen-span-mp25-rc6.mrpack"
SERVER_NAME = "mech-arena-operation-ashen-span-mp25-rc6-server-overlay.zip"
WORLD_NAME = "cold_ruin_sector_01-mp25-world.zip"
MANIFEST_NAME = "MANIFEST.json"
RECEIPT_NAME = "OFFLINE_BUILD_RECEIPT.json"
HASHES_NAME = "SHA256SUMS.txt"
NOTICE_NAME = "THIRD_PARTY_NOTICES.md"
RUNBOOK_NAME = "RUNBOOK.md"
ROLLBACK_NAME = "ROLLBACK.md"
LIVE_VALIDATION_NAME = "ASHEN_SPAN_LIVE_VALIDATION.md"

INDEX_PATH = "modrinth.index.json"
OPTIONS_PATH = "overrides/options.txt"
CLIENT_NOTICE_PATH = "overrides/THIRD_PARTY_NOTICES.md"
CLIENT_POMKOTS_CONFIG_PATH = "overrides/config/pomkotsmechs.json"
LOST_CITIES_PROFILE_RUNTIME_PATH = "config/lostcities/profiles/mecharena_sector01.json"
CLIENT_LOST_CITIES_PROFILE_PATH = f"overrides/{LOST_CITIES_PROFILE_RUNTIME_PATH}"
SERVER_LOST_CITIES_PROFILE_PATH = LOST_CITIES_PROFILE_RUNTIME_PATH
CLIENT_SAVE_ROOT = "overrides/saves/cold_ruin_sector_01"
SERVER_SAVE_ROOT = "saves/cold_ruin_sector_01"
SERVER_PROPERTIES_PATH = "server.properties"
SERVER_POMKOTS_CONFIG_PATH = "config/pomkotsmechs.json"
CLIENT_LICENSE_ROOT = "overrides/licenses"
SERVER_LICENSE_ROOT = "licenses"

LICENSE_FILENAMES = {
    "architectury": "ARCHITECTURY-LGPL-3.0.md",
    "cloth_config": "CLOTH_CONFIG-LGPL-3.0.md",
    "geckolib": "GECKOLIB-MIT.txt",
    "mp25": "POMKOTS_MECHS-MIT.txt",
    "asset": "COLD_RUIN_SECTOR_01-MIT.txt",
    "lost_cities": "LOST_CITIES-MIT-NOTICE.md",
    "matrix": "DEPENDENCY_LICENSE_MATRIX.md",
}
FORBIDDEN_QUALIFICATION_PATH_TOKENS = (
    "qualification", "acceptance_probe", "acceptance-probe", "fakeplayer",
)
FORBIDDEN_ARCHITECTURY_INJECTION_TOKEN = b"architectury_inject_"
RC5_CANDIDATE_ID = "operation-ashen-span-mp25-rc5"
RC5_RUNTIME_SOURCE_COMMIT = "d96b7b84688e925f311849d7c40f72a4f8a691c2"
RC5_MP25_SHA256 = "29D3295D47CB3CD6744BAE98AFB404E5CFC87F94E26B0556A1120A5B42744213"
# Historical pre-erratum collision-fix artifact. It predates the deterministic checkout
# policy and is not a canonical or releasable RC6 anchor.
RC6_RUNTIME_SOURCE_COMMIT = "5a35ec3d9a69fdd4d88ed7a0b21b28bc1b18ecfb"
RC6_MP25_SHA256 = "0263191D695C2CBB136B883CC62DAF1354C1B2013FFCCEDDDE54CE9DD63600F4"
RC6_MP25_BYTES = 10_704_165
# No RC6 may be built until an authoritative spec erratum resolves the locked
# 6/6 player-ticket footprint versus the 680-chunk safety envelope. This gate
# is intentionally unconditional: a future candidate needs a new identity and
# a reviewed two-phase build -> acceptance -> publication contract, not a latch.
RC5_ASSET_SHA256 = "E1AC026BC07966803C5F3AFCF455A0B6B7F924F4131C52600355944A5CBBEFA9"
RC5_WORLD_BUILDER_ARCHIVE_SHA256 = "3EE5D52888DABA0F15CE8E9BC7A142292D46A0F3F966DCD2C1533771C89F4FA4"
RC5_PACKAGED_WORLD_SHA256 = "01FA630AAB605053CD7A7B9D8377CAE1066FDE48815D4FDF8A60049411500D87"

DEPENDENCY_PATHS = {
    "architectury": "mods/architectury-9.2.14-forge.jar",
    "cloth_config": "mods/cloth-config-11.1.136-forge.jar",
    "geckolib": "mods/geckolib-forge-1.20.1-4.4.9.jar",
}
CLIENT_MOD_PATHS = {
    "mp25": f"overrides/mods/{MP25_NAME}",
    "lost_cities": f"overrides/mods/{LOST_CITIES_NAME}",
    "asset": f"overrides/mods/{ASSET_NAME}",
}

WORLD_SEED = 4_707_185_498_036_326_465
MAX_WORLD_BYTES = 32 * 1024 * 1024
MAX_WORLD_UNCOMPRESSED_BYTES = 256 * 1024 * 1024
MAX_ARCHIVE_UNCOMPRESSED_BYTES = 512 * 1024 * 1024
MAX_ARCHIVE_ENTRY_BYTES = 256 * 1024 * 1024
MAX_ARCHIVE_ENTRIES = 100_000
MAX_CENTRAL_DIRECTORY_BYTES = 16 * 1024 * 1024
MAX_BUILDER_SOURCE_BYTES = 2 * 1024 * 1024
WORLD_BUILDER_SCHEMA = 2
WORLD_BUILDER_ID = "ashen-span-world/2.0.0"
WORLD_BUILDER_ARCHIVE_NAME = "cold_ruin_sector_01_mp25_world.zip"
WORLD_ARCHIVE_ROOT = "cold_ruin_sector_01"
WORLD_DIMENSION = "minecraft:overworld"
WORLD_DATA_VERSION = 3465
WORLD_PROFILE_NAME = "mecharena_sector01"
WORLD_PROFILE_SHA256 = "d0a3f587d39350e0c66fd59164ce500146406ce55bd75d582912905eb5c1e868"
WORLD_SOURCE_CONTRACT_NAME = "sector01-contract.json"
WORLD_SOURCE_CONTRACT_SHA256 = "60d315c55e9382cd11dd98cc1b5c4b78e97cefe7eb7f150a17724987f109f92e"
WORLD_SAFETY_CHUNK_DIGEST = "cf7c9217fe947bc7048731622db60f809d12ba4987b97074416b9439ab5ca8b1"
WORLD_FORGE_LIBRARIES_SHA256 = "1159a5bc02501e3971b397576f979b78e510f7defefd7988611e5b1e0637a0bf"
WORLD_JAVA_EXECUTABLE_SHA256 = "b3afe83e1ab067da4c56f1a7b2ba4c14ec832d694333f35b2b45178e9ac596ef"
GENERATION_DATAPACK_NAME = "ashen_span_generator"
GENERATION_DATAPACK_BIOME = "mecharena_generation:empty"
GENERATION_DATAPACK_ROOT = f"datapacks/{GENERATION_DATAPACK_NAME}"
GENERATION_DATAPACK_PATHS = frozenset({
    f"{GENERATION_DATAPACK_ROOT}/pack.mcmeta",
    f"{GENERATION_DATAPACK_ROOT}/data/minecraft/tags/worldgen/biome/is_overworld.json",
    f"{GENERATION_DATAPACK_ROOT}/data/mecharena_generation/worldgen/biome/empty.json",
})
GENERATION_DATAPACK_SHA256 = "1006df5f0363cf4c37a546fbab8199ba11b5d301d1f11116c3bc7b201e2b1fc4"
FIXED_TIMESTAMP = (1980, 1, 1, 0, 0, 0)
FIXED_MODE = stat.S_IFREG | 0o644
OUTPUT_COMPRESSION = zipfile.ZIP_DEFLATED
HEX_40 = re.compile(r"^[0-9a-fA-F]{40}$")
HEX_64 = re.compile(r"^[0-9a-fA-F]{64}$")
BUILDER_REPO_PATH = "tools/build_ashen_span_mp25.py"

SEALED_MP24 = {
    "mrpack_sha256": BASE_SHA256,
    "jar_sha256": BASE_MOD_SHA256,
    "manifest_sha256": "D05FE1929349AD6973A6F76E6D0267D423E4F37BBCC32361CF27FAE6C63F63FB",
}


class BuildError(RuntimeError):
    """A pinned input, archive, or exclusive-publication contract failed."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise BuildError(message)


def require_safety_envelope_resolution() -> None:
    raise BuildError(
        "RC6 packaging is blocked unconditionally: genuine view-distance 6 player "
        "tickets exceed the authoritative 680-chunk safety envelope. A future "
        "post-erratum candidate requires a new identity and reviewed two-phase "
        "acceptance/publication implementation; see "
        "docs/ashen-span-hardening/SAFETY_ENVELOPE_BLOCKER.md"
    )


def sha256_bytes(blob: bytes) -> str:
    return hashlib.sha256(blob).hexdigest().upper()


def sha512_bytes(blob: bytes) -> str:
    return hashlib.sha512(blob).hexdigest().lower()


def sha1_bytes(blob: bytes) -> str:
    return hashlib.sha1(blob).hexdigest().lower()


def canonical_json(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8")


def canonical_text(value: str) -> bytes:
    return value.replace("\r\n", "\n").replace("\r", "\n").rstrip().encode("utf-8") + b"\n"


def normalized_sha256(value: str, label: str) -> str:
    require(HEX_64.fullmatch(value) is not None, f"{label} must be exactly 64 hexadecimal characters")
    return value.upper()


def is_link_or_reparse(path: Path) -> bool:
    try:
        metadata = path.lstat()
    except OSError:
        return False
    attributes = int(getattr(metadata, "st_file_attributes", 0))
    reparse = int(getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400))
    is_junction = getattr(path, "is_junction", lambda: False)
    return stat.S_ISLNK(metadata.st_mode) or bool(attributes & reparse) or bool(is_junction())


def read_stable(path: Path, label: str, expected_sha256: str | None = None,
                expected_bytes: int | None = None, max_bytes: int | None = None) -> bytes:
    require(path.is_file() and not is_link_or_reparse(path), f"{label} is missing or unsafe: {path}")
    before = path.stat()
    if max_bytes is not None:
        require(before.st_size <= max_bytes, f"{label} exceeds the {max_bytes}-byte safety limit")
    blob = path.read_bytes()
    after = path.stat()
    require(
        (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns)
        == (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns),
        f"{label} changed while it was read",
    )
    if expected_bytes is not None:
        require(len(blob) == expected_bytes, f"{label} byte count changed: {len(blob)}")
    if expected_sha256 is not None:
        require(sha256_bytes(blob) == expected_sha256, f"{label} SHA-256 mismatch")
    return blob


def checkout_eol_mismatches(listing: str) -> list[str]:
    """Return tracked paths whose physical bytes violate their checkout policy."""
    mismatches: list[str] = []
    for record in listing.split("\0"):
        if not record:
            continue
        require("\t" in record, "cannot parse Git checkout EOL inventory")
        metadata, path = record.split("\t", 1)
        index_match = re.search(r"(?:^|\s)i/(\S+)", metadata)
        worktree_match = re.search(r"(?:^|\s)w/(\S+)", metadata)
        require(index_match is not None and worktree_match is not None,
                "Git checkout EOL inventory lacks index/worktree state")
        indexed = index_match.group(1)
        actual = worktree_match.group(1)
        if "eol=lf" in metadata and actual not in {"lf", "none"}:
            mismatches.append(f"{path} ({actual}, expected lf)")
        elif "eol=crlf" in metadata and actual not in {"crlf", "none"}:
            mismatches.append(f"{path} ({actual}, expected crlf)")
        elif "attr/-text" in metadata and indexed != actual:
            mismatches.append(
                f"{path} ({actual}, expected byte-preserved index state {indexed})"
            )
    return mismatches


def validate_checkout_eol(repo: Path) -> None:
    """Reject physical bytes that differ from canonical or byte-preserved inputs."""
    try:
        result = subprocess.run(
            ["git", "ls-files", "--eol", "-z"], cwd=repo, check=False,
            capture_output=True, text=True, encoding="utf-8",
        )
    except (OSError, UnicodeError) as exc:
        raise BuildError(f"cannot inspect physical checkout line endings: {exc}") from exc
    require(result.returncode == 0, "cannot inspect physical checkout line endings")
    mismatches = checkout_eol_mismatches(result.stdout)
    require(not mismatches,
            "physical checkout line endings violate .gitattributes: "
            + "; ".join(mismatches[:8]))


def validate_source_commit(value: str) -> str:
    """Bind release provenance to the exact clean commit running this builder."""
    require(HEX_40.fullmatch(value) is not None, "source commit must be a 40-character Git object ID")
    repo = Path(__file__).resolve().parent.parent
    try:
        resolved = subprocess.run(
            ["git", "rev-parse", "--verify", f"{value}^{{commit}}"],
            cwd=repo, check=False, capture_output=True, text=True, encoding="utf-8",
        )
        head = subprocess.run(
            ["git", "rev-parse", "--verify", "HEAD^{commit}"],
            cwd=repo, check=False, capture_output=True, text=True, encoding="utf-8",
        )
        status = subprocess.run(
            ["git", "status", "--porcelain=v1", "--untracked-files=all", "--", "."],
            cwd=repo, check=False, capture_output=True, text=True, encoding="utf-8",
        )
    except (OSError, UnicodeError) as exc:
        raise BuildError(f"cannot establish source commit identity: {exc}") from exc
    require(resolved.returncode == 0, "source commit does not resolve to a Git commit")
    require(head.returncode == 0, "cannot resolve the current Git HEAD")
    commit = resolved.stdout.strip().lower()
    require(commit == head.stdout.strip().lower(), "source commit is not the checked-out HEAD")
    require(status.returncode == 0, "cannot inspect the source working tree")
    require(not status.stdout.strip(), "source working tree is dirty; commit exact release sources first")
    validate_checkout_eol(repo)
    return commit


def read_committed_builder_blob(source_commit: str) -> bytes:
    """Read the canonical builder bytes from Git, independent of checkout EOLs."""
    require(HEX_40.fullmatch(source_commit) is not None,
            "source commit must be a 40-character Git object ID")
    repo = Path(__file__).resolve().parent.parent
    object_name = f"{source_commit.lower()}:{BUILDER_REPO_PATH}"
    try:
        result = subprocess.run(
            ["git", "cat-file", "blob", object_name],
            cwd=repo, check=False, capture_output=True,
        )
    except OSError as exc:
        raise BuildError(f"cannot read committed candidate builder: {exc}") from exc
    require(result.returncode == 0,
            "candidate builder is missing from the declared source commit")
    require(0 < len(result.stdout) <= MAX_BUILDER_SOURCE_BYTES,
            "committed candidate builder has an invalid byte count")
    return result.stdout


def _zip_directory_limits(blob: bytes, label: str) -> None:
    """Reject oversized/ZIP64 central directories before ZipFile allocates entries."""
    signature = b"PK\x05\x06"
    start = max(0, len(blob) - (65_535 + 22))
    offset = blob.rfind(signature, start)
    require(offset >= 0 and offset + 22 <= len(blob), f"{label}: missing ZIP end record")
    comment_length = int.from_bytes(blob[offset + 20:offset + 22], "little")
    require(offset + 22 + comment_length == len(blob), f"{label}: trailing or malformed ZIP data")
    disk = int.from_bytes(blob[offset + 4:offset + 6], "little")
    central_disk = int.from_bytes(blob[offset + 6:offset + 8], "little")
    disk_entries = int.from_bytes(blob[offset + 8:offset + 10], "little")
    total_entries = int.from_bytes(blob[offset + 10:offset + 12], "little")
    central_size = int.from_bytes(blob[offset + 12:offset + 16], "little")
    central_offset = int.from_bytes(blob[offset + 16:offset + 20], "little")
    require(disk == central_disk == 0 and disk_entries == total_entries,
            f"{label}: multi-disk ZIP is forbidden")
    require(total_entries != 0xFFFF and central_size != 0xFFFFFFFF and central_offset != 0xFFFFFFFF,
            f"{label}: ZIP64 input is forbidden")
    require(total_entries <= MAX_ARCHIVE_ENTRIES, f"{label}: too many archive entries")
    require(central_size <= MAX_CENTRAL_DIRECTORY_BYTES, f"{label}: central directory is too large")
    require(central_offset + central_size <= offset, f"{label}: central directory bounds are invalid")


def _read_zip_member(archive: zipfile.ZipFile, info: zipfile.ZipInfo, label: str,
                     max_entry_bytes: int) -> bytes:
    require(0 <= info.file_size <= max_entry_bytes,
            f"{label}: archive member exceeds safety limit: {info.filename}")
    chunks: list[bytes] = []
    measured = 0
    with archive.open(info, "r") as stream:
        while True:
            chunk = stream.read(min(1024 * 1024, max_entry_bytes - measured + 1))
            if not chunk:
                break
            measured += len(chunk)
            require(measured <= max_entry_bytes and measured <= info.file_size,
                    f"{label}: archive member expanded past declared limits: {info.filename}")
            chunks.append(chunk)
    require(measured == info.file_size, f"{label}: archive member size mismatch: {info.filename}")
    return b"".join(chunks)


def validate_archive_path(raw: str, label: str, *, allow_directory: bool = False) -> str:
    require(raw and "\x00" not in raw, f"{label}: empty or NUL archive path")
    require("\\" not in raw, f"{label}: archive path uses a backslash: {raw!r}")
    directory = raw.endswith("/")
    candidate = raw[:-1] if directory else raw
    require(not directory or allow_directory, f"{label}: directory entry is forbidden: {raw!r}")
    path = PurePosixPath(candidate)
    require(candidate and not path.is_absolute(), f"{label}: absolute archive path: {raw!r}")
    require(not PureWindowsPath(candidate).drive, f"{label}: drive-qualified archive path: {raw!r}")
    require("." not in path.parts and ".." not in path.parts, f"{label}: unsafe archive path: {raw!r}")
    require(path.as_posix() == candidate, f"{label}: noncanonical archive path: {raw!r}")
    return raw


def read_zip(blob: bytes, label: str, *, canonical: bool = False,
             compression: int | None = None, allow_directories: bool = False,
             max_uncompressed_bytes: int = MAX_ARCHIVE_UNCOMPRESSED_BYTES,
             max_entry_bytes: int = MAX_ARCHIVE_ENTRY_BYTES) -> dict[str, bytes]:
    try:
        _zip_directory_limits(blob, label)
        with zipfile.ZipFile(io.BytesIO(blob), "r") as archive:
            require(archive.comment == b"", f"{label}: ZIP comment is forbidden")
            infos = archive.infolist()
            names = [validate_archive_path(info.filename, label, allow_directory=allow_directories) for info in infos]
            require(len(names) == len(set(names)), f"{label}: duplicate archive path")
            require(len(names) == len({name.casefold() for name in names}), f"{label}: case-colliding paths")
            total_uncompressed = sum(info.file_size for info in infos if not info.is_dir())
            require(total_uncompressed <= max_uncompressed_bytes,
                    f"{label}: archive expands past the {max_uncompressed_bytes}-byte safety limit")
            if canonical:
                require(names == sorted(names), f"{label}: entries are not sorted")
            result: dict[str, bytes] = {}
            for info in infos:
                if info.is_dir():
                    require(allow_directories and not canonical, f"{label}: directory entry is forbidden")
                    continue
                require(not (info.flag_bits & 0x1), f"{label}: encrypted entry {info.filename}")
                require(info.compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED),
                        f"{label}: unsupported compression for {info.filename}")
                if canonical:
                    require(info.date_time == FIXED_TIMESTAMP, f"{label}: timestamp drift for {info.filename}")
                    require(info.create_system == 3, f"{label}: creator OS drift for {info.filename}")
                    require((info.external_attr >> 16) == FIXED_MODE, f"{label}: mode drift for {info.filename}")
                if compression is not None:
                    require(info.compress_type == compression, f"{label}: compression drift for {info.filename}")
                result[info.filename] = _read_zip_member(archive, info, label, max_entry_bytes)
            return result
    except (zipfile.BadZipFile, NotImplementedError, RuntimeError) as exc:
        raise BuildError(f"{label}: malformed ZIP/JAR: {exc}") from exc


def make_canonical_zip(entries: dict[str, bytes], *, compression: int = OUTPUT_COMPRESSION) -> bytes:
    names = sorted(entries)
    require(len(names) == len({name.casefold() for name in names}), "output archive paths case-collide")
    output = io.BytesIO()
    kwargs = {"compression": compression}
    if compression == zipfile.ZIP_DEFLATED:
        kwargs["compresslevel"] = 9
    with zipfile.ZipFile(output, "w", **kwargs) as archive:
        for name in names:
            validate_archive_path(name, "output archive")
            info = zipfile.ZipInfo(name, FIXED_TIMESTAMP)
            info.create_system = 3
            info.compress_type = compression
            info.external_attr = FIXED_MODE << 16
            if compression == zipfile.ZIP_DEFLATED:
                archive.writestr(info, entries[name], compress_type=compression, compresslevel=9)
            else:
                archive.writestr(info, entries[name], compress_type=compression)
    blob = output.getvalue()
    require(
        read_zip(blob, "generated archive", canonical=True, compression=compression) == entries,
        "generated archive round-trip changed entry bytes",
    )
    return blob


def parse_canonical_json(blob: bytes, label: str) -> dict[str, object]:
    try:
        value = json.loads(blob)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise BuildError(f"{label} is malformed: {exc}") from exc
    require(isinstance(value, dict), f"{label} root must be an object")
    require(blob == canonical_json(value), f"{label} is not canonical JSON")
    return value


def parse_mods_toml(entries: dict[str, bytes], label: str) -> dict[str, object]:
    require("META-INF/mods.toml" in entries, f"{label} is missing META-INF/mods.toml")
    try:
        value = tomllib.loads(entries["META-INF/mods.toml"].decode("utf-8"))
    except (UnicodeDecodeError, tomllib.TOMLDecodeError) as exc:
        raise BuildError(f"{label} has malformed mods.toml: {exc}") from exc
    require(isinstance(value, dict), f"{label} mods.toml root is not a table")
    return value


def unique_mod(metadata: dict[str, object], mod_id: str, label: str) -> dict[str, object]:
    mods = metadata.get("mods")
    require(isinstance(mods, list), f"{label} has no mod declarations")
    matches = [record for record in mods if isinstance(record, dict) and record.get("modId") == mod_id]
    require(len(matches) == 1, f"{label} must declare exactly one {mod_id} mod")
    return matches[0]


def validate_mp25_jar(blob: bytes, expected_sha: str) -> None:
    require(sha256_bytes(blob) == expected_sha, "mp.25 JAR SHA-256 mismatch")
    entries = read_zip(blob, "mp.25 JAR", allow_directories=True)
    required = {
        "LICENSE",
        "META-INF/mods.toml",
        "grcmcs/minecraft/mods/pomkotsmechs/arena/ArenaManager.class",
        "grcmcs/minecraft/mods/pomkotsmechs/arena/AshenSpanDefinition.class",
        "grcmcs/minecraft/mods/pomkotsmechs/arena/AshenSpanDirector.class",
        "grcmcs/minecraft/mods/pomkotsmechs/arena/AshenSpanMapContract.class",
        "grcmcs/minecraft/mods/pomkotsmechs/arena/MissionGateLedger.class",
        "grcmcs/minecraft/mods/pomkotsmechs/entity/vehicle/custom/ArenaRivalPmvc01Entity.class",
    }
    require(required <= set(entries), f"mp.25 JAR is missing Ashen Span runtime members: {sorted(required - set(entries))}")
    require(not any(
        token in name.casefold()
        for name in entries
        for token in FORBIDDEN_QUALIFICATION_PATH_TOKENS
    ), "mp.25 production JAR contains a qualification-only payload")
    require(not any(
        FORBIDDEN_ARCHITECTURY_INJECTION_TOKEN.decode("ascii") in name.casefold()
        or FORBIDDEN_ARCHITECTURY_INJECTION_TOKEN in payload
        for name, payload in entries.items()
    ), "mp.25 production JAR contains a path-dependent Architectury injection payload")
    metadata = parse_mods_toml(entries, "mp.25 JAR")
    require(metadata.get("modLoader") == "javafml", "mp.25 JAR uses the wrong mod loader")
    record = unique_mod(metadata, "pomkotsmechs", "mp.25 JAR")
    require(record.get("version") == MP25_VERSION, f"mp.25 metadata version is not {MP25_VERSION}")
    dependencies = metadata.get("dependencies", {}).get("pomkotsmechs", []) if isinstance(metadata.get("dependencies"), dict) else []
    ids = [item.get("modId") for item in dependencies if isinstance(item, dict)]
    require(ids.count("cloth_config") == 1, "mp.25 JAR must declare Cloth Config exactly once")


def validate_lost_cities_jar(blob: bytes) -> None:
    require(len(blob) == LOST_CITIES_BYTES, "Lost Cities byte count mismatch")
    require(sha256_bytes(blob) == LOST_CITIES_SHA256, "Lost Cities SHA-256 mismatch")
    entries = read_zip(blob, "Lost Cities JAR", allow_directories=True)
    metadata = parse_mods_toml(entries, "Lost Cities JAR")
    unique_mod(metadata, "lostcities", "Lost Cities JAR")
    manifest = entries.get("META-INF/MANIFEST.MF", b"").decode("utf-8", "replace")
    require(f"Implementation-Version: {LOST_CITIES_VERSION}" in manifest,
            f"Lost Cities implementation is not {LOST_CITIES_VERSION}")
    require("MIT" in str(metadata.get("license", "")).upper(), "Lost Cities metadata does not declare MIT")


def validate_asset_profile(blob: bytes) -> bytes:
    require(len(blob) == ASSET_PROFILE_BYTES, "embedded Lost Cities profile byte count mismatch")
    require(sha256_bytes(blob) == ASSET_PROFILE_SHA256,
            "embedded Lost Cities profile SHA-256 mismatch")
    value = parse_canonical_json(blob, "embedded Lost Cities profile")
    lostcity = value.get("lostcity")
    cities = value.get("cities")
    cityspheres = value.get("cityspheres")
    require(isinstance(lostcity, dict)
            and lostcity.get("worldStyle") == "mecharena_sector01:ashen_span_world"
            and lostcity.get("generateLoot") is False
            and lostcity.get("generateSpawners") is False,
            "embedded Lost Cities profile mission settings mismatch")
    require(isinstance(cities, dict) and cities.get("cityChance") == 0.0,
            "embedded Lost Cities profile enables random cities")
    require(cityspheres == {"citySphereChance": 0.0, "onlyPredefined": True}
            and value.get("public") is False,
            "embedded Lost Cities profile boundary mismatch")
    return blob


def validate_asset_jar(blob: bytes, expected_sha: str) -> tuple[bytes, bytes]:
    require(sha256_bytes(blob) == expected_sha, "Sector 01 asset JAR SHA-256 mismatch")
    entries = read_zip(blob, "Sector 01 asset JAR", allow_directories=True)
    required = {
        "META-INF/LICENSE",
        "META-INF/THIRD_PARTY_NOTICES.md",
        "META-INF/mecharena-sector01-build.json",
        "META-INF/mods.toml",
        ASSET_PROFILE_MEMBER,
        "data/mecharena_sector01/ashen_span/cold_ruin_sector_01.json",
        "data/mecharena_sector01/lostcities/predefinedcities/cold_ruin_sector_01.json",
    }
    require(required <= set(entries), f"asset JAR is missing required members: {sorted(required - set(entries))}")
    profile_members = {
        name for name in entries
        if name.casefold().startswith("config-template/lostcities/profiles/")
    }
    require(profile_members == {ASSET_PROFILE_MEMBER},
            f"asset JAR Lost Cities profile set mismatch: {sorted(profile_members)}")
    metadata = parse_mods_toml(entries, "Sector 01 asset JAR")
    require(metadata.get("modLoader") == "lowcodefml", "asset JAR is not low-code Forge data")
    record = unique_mod(metadata, "mecharena_sector01", "Sector 01 asset JAR")
    require(record.get("version") == ASSET_VERSION, f"asset version is not {ASSET_VERSION}")
    dependencies = metadata.get("dependencies", {}).get("mecharena_sector01", []) if isinstance(metadata.get("dependencies"), dict) else []
    lost = [item for item in dependencies if isinstance(item, dict) and item.get("modId") == "lostcities"]
    require(len(lost) == 1 and lost[0].get("versionRange") == "[1.20-7.4.13]",
            "asset JAR does not pin exact Lost Cities 1.20-7.4.13")
    notice = entries["META-INF/THIRD_PARTY_NOTICES.md"]
    for phrase in (b"MIT License", b"Lost Cities", b"no downloaded city"):
        require(phrase.lower() in notice.lower(), f"asset notice lost {phrase!r}")
    return notice, validate_asset_profile(entries[ASSET_PROFILE_MEMBER])


def validate_asset_receipt(blob: bytes, asset_sha: str) -> dict[str, object]:
    value = parse_canonical_json(blob, "asset build receipt")
    artifact = value.get("artifact")
    require(isinstance(artifact, dict), "asset receipt has no artifact record")
    require(str(artifact.get("sha256", "")).upper() == asset_sha, "asset receipt SHA does not match asset input")
    require(artifact.get("name") == ASSET_NAME, "asset receipt has the wrong artifact name")
    require(value.get("map_id") == "cold_ruin_sector_01", "asset receipt map ID mismatch")
    require(value.get("mission_id") == "operation_ashen_span", "asset receipt mission ID mismatch")
    require(value.get("authored_chunk_count") == 308, "asset receipt authored chunk count mismatch")
    require(value.get("predefined_building_count") == 308, "asset receipt predefined city count mismatch")
    require(value.get("lost_cities_version_range") == "[1.20-7.4.13]", "asset receipt Lost Cities range mismatch")
    require(value.get("mode") == "full", "asset receipt is not a full build")
    require(value.get("profile") == WORLD_PROFILE_NAME, "asset receipt Lost Cities profile name mismatch")
    source_sha = value.get("source_sha256")
    require(isinstance(source_sha, dict)
            and source_sha.get("lostcities-profile.json") == WORLD_PROFILE_SHA256,
            "asset receipt Lost Cities profile source hash mismatch")
    return value


def generation_datapack_sha256(entries: dict[str, bytes]) -> str:
    actual = {name for name in entries if PurePosixPath(name).parts[0] == "datapacks"}
    require(
        actual == GENERATION_DATAPACK_PATHS,
        "world input generation datapack file set drifted: "
        f"extra={sorted(actual - GENERATION_DATAPACK_PATHS)}, "
        f"missing={sorted(GENERATION_DATAPACK_PATHS - actual)}",
    )
    digest = hashlib.sha256()
    for name in sorted(actual):
        relative = PurePosixPath(name).relative_to(GENERATION_DATAPACK_ROOT).as_posix()
        digest.update(f"{relative}\0{sha256_bytes(entries[name]).lower()}\n".encode("utf-8"))
    measured = digest.hexdigest()
    require(measured == GENERATION_DATAPACK_SHA256, "world input generation datapack hash mismatch")
    return measured


def read_world_tree(root: Path) -> dict[str, bytes]:
    require(root.is_dir() and not is_link_or_reparse(root), f"world input is missing or unsafe: {root}")
    result: dict[str, bytes] = {}
    measured_bytes = 0
    for path in sorted(root.rglob("*")):
        require(not is_link_or_reparse(path), f"world input contains a link/reparse point: {path}")
        if path.is_dir():
            continue
        require(path.is_file(), f"world input contains a non-file: {path}")
        relative = path.relative_to(root).as_posix()
        validate_archive_path(relative, "world input")
        lowered = relative.casefold().replace("_", " ").replace("-", " ")
        require("mosslorn" not in lowered and "future city" not in lowered and "downloaded city" not in lowered,
                f"world input contains forbidden downloaded-city payload: {relative}")
        blob = read_stable(
            path, f"world file {relative}",
            max_bytes=MAX_WORLD_UNCOMPRESSED_BYTES - measured_bytes,
        )
        measured_bytes += len(blob)
        result[relative] = blob
    require(result, "world input is empty")
    required = {
        "level.dat",
        "data/mecharena_sector01_contract.dat",
        "serverconfig/lostcities-server.toml",
    }
    require(required <= set(result), f"world input is missing required files: {sorted(required - set(result))}")
    require(any(name.startswith("region/") and name.endswith(".mca") for name in result),
            "world input contains no terrain region files")
    allowed_top = {"level.dat", "data", "region", "serverconfig", "datapacks"}
    require(all(PurePosixPath(name).parts[0] in allowed_top for name in result),
            "world input contains player state, extra dimensions, or uncurated indices")
    require(len(result) == len({name.casefold() for name in result}), "world input paths case-collide")
    generation_datapack_sha256(result)
    return result


def verify_bounded_world(root: Path, asset_sha: str,
                         world_tree: dict[str, bytes]) -> dict[str, object]:
    """Measure the physical 680-chunk world; a receipt is not evidence here."""
    try:
        facts = world_builder.verify_world(
            root,
            WORLD_PROFILE_SHA256,
            asset_sha.lower(),
            WORLD_SOURCE_CONTRACT_SHA256,
        )
    except (world_builder.BuildError, OSError, ValueError) as exc:
        raise BuildError(f"bounded world verification failed: {exc}") from exc
    require(facts.get("terrain_chunk_count") == 680, "bounded world does not contain exactly 680 chunks")
    require(facts.get("authored_chunk_count") == 308, "bounded world does not contain 308 authored chunks")
    require(
        str(facts.get("curated_tree_sha256", "")).upper()
        == world_builder_curated_tree_sha256(world_tree),
        "bounded world changed between tree read and physical verification",
    )
    return facts


def world_tree_sha256(entries: dict[str, bytes]) -> str:
    digest = hashlib.sha256(b"operation-ashen-span-world-tree-v1\0")
    for name in sorted(entries):
        encoded = name.encode("utf-8")
        blob = entries[name]
        digest.update(len(encoded).to_bytes(4, "big"))
        digest.update(encoded)
        digest.update(len(blob).to_bytes(8, "big"))
        digest.update(hashlib.sha256(blob).digest())
    return digest.hexdigest().upper()


def world_builder_curated_tree_sha256(entries: dict[str, bytes]) -> str:
    digest = hashlib.sha256()
    for name in sorted(entries):
        blob = entries[name]
        digest.update(f"{name}\0{len(blob)}\0{sha256_bytes(blob).lower()}\n".encode("utf-8"))
    return digest.hexdigest().upper()


def validate_world_archive(blob: bytes, tree: dict[str, bytes]) -> None:
    require(len(blob) <= MAX_WORLD_BYTES, "world-builder archive exceeds the 32 MiB budget")
    entries = read_zip(
        blob, "world-builder archive", canonical=True, compression=zipfile.ZIP_DEFLATED,
        max_uncompressed_bytes=MAX_WORLD_UNCOMPRESSED_BYTES,
    )
    roots = {PurePosixPath(name).parts[0] for name in entries}
    require(len(roots) == 1, "world-builder archive must have one root")
    root = next(iter(roots))
    require(root == WORLD_ARCHIVE_ROOT, "world-builder archive has the wrong root")
    stripped = {PurePosixPath(name).relative_to(root).as_posix(): data for name, data in entries.items()}
    require(stripped == tree, "world-builder archive does not exactly match the curated world directory")


def validate_world_receipt(blob: bytes, world_blob: bytes, world_sha: str,
                           asset_sha: str, world_tree: dict[str, bytes],
                           world_facts: dict[str, object]) -> dict[str, object]:
    value = parse_canonical_json(blob, "world build receipt")
    require(value.get("schema") == WORLD_BUILDER_SCHEMA and value.get("builder") == WORLD_BUILDER_ID,
            "world receipt builder identity mismatch")
    require(value.get("map_id") == "cold_ruin_sector_01", "world receipt map ID mismatch")
    require(value.get("mission_id") == "operation_ashen_span", "world receipt mission ID mismatch")
    require(value.get("map_version") == 1, "world receipt map version mismatch")
    require(value.get("seed") == WORLD_SEED, "world receipt seed mismatch")
    require(value.get("minecraft") == "1.20.1", "world receipt Minecraft version mismatch")
    require(value.get("forge") == "47.3.3", "world receipt Forge version mismatch")
    lost = value.get("lost_cities")
    require(isinstance(lost, dict) and lost.get("version") == "7.4.13"
            and str(lost.get("sha256", "")).upper() == LOST_CITIES_SHA256,
            "world receipt Lost Cities identity mismatch")
    asset = value.get("asset")
    require(isinstance(asset, dict) and asset.get("name") == ASSET_NAME
            and str(asset.get("sha256", "")).upper() == asset_sha,
            "world receipt asset identity mismatch")
    require(value.get("profile") == {"name": WORLD_PROFILE_NAME, "sha256": WORLD_PROFILE_SHA256},
            "world receipt Lost Cities profile mismatch")
    require(value.get("source_contract") == {
        "name": WORLD_SOURCE_CONTRACT_NAME,
        "sha256": WORLD_SOURCE_CONTRACT_SHA256,
    }, "world receipt source contract mismatch")
    measured_datapack_sha = generation_datapack_sha256(world_tree)
    require(value.get("generation_datapack") == {
        "name": GENERATION_DATAPACK_NAME,
        "biome": GENERATION_DATAPACK_BIOME,
        "sha256": measured_datapack_sha,
    }, "world receipt generation datapack mismatch")
    require(value.get("generation_runtime") == {
        "forge_libraries_sha256": WORLD_FORGE_LIBRARIES_SHA256,
        "java_executable_sha256": WORLD_JAVA_EXECUTABLE_SHA256,
    }, "world receipt generation runtime mismatch")
    world = value.get("world")
    require(isinstance(world, dict), "world receipt has no world record")
    require(str(world.get("sha256", "")).upper() == world_sha == sha256_bytes(world_blob),
            "world receipt archive SHA mismatch")
    require(world.get("bytes") == len(world_blob), "world receipt archive byte count mismatch")
    require(world.get("name") == WORLD_BUILDER_ARCHIVE_NAME
            and world.get("archive_root") == WORLD_ARCHIVE_ROOT,
            "world receipt archive identity mismatch")
    require(world.get("uncompressed_bytes") == sum(len(item) for item in world_tree.values()),
            "world receipt archive expansion mismatch")
    require(str(world.get("curated_tree_sha256", "")).upper() == world_builder_curated_tree_sha256(world_tree),
            "world receipt curated tree hash mismatch")
    require(len(world_blob) <= MAX_WORLD_BYTES, "world-builder archive exceeds the 32 MiB budget")
    contract = value.get("contract")
    expected = {
        "dimension": WORLD_DIMENSION,
        "data_version": WORLD_DATA_VERSION,
        "content_chunk_count": 308,
        "safety_chunk_count": 680,
        "safety_chunk_digest": WORLD_SAFETY_CHUNK_DIGEST,
        "view_distance": 6,
        "simulation_distance": 6,
        "compressed_budget_bytes": MAX_WORLD_BYTES,
    }
    require(contract == expected, "world receipt contract mismatch")
    require(value.get("frozen_tree_verification") == world_facts,
            "world receipt frozen-tree facts differ from independent measurement")
    archive_verification = value.get("verification")
    require(isinstance(archive_verification, dict), "world receipt has no archive verification")
    for key, measured in world_facts.items():
        require(archive_verification.get(key) == measured,
                f"world receipt verification differs from independent measurement: {key}")
    require(value.get("determinism") == {
        "scope": "canonical archive bytes for this verified frozen curated world tree",
        "independent_forge_generation_byte_equality_claimed": False,
    }, "world receipt determinism scope mismatch")
    require(value.get("canonical_zip_timestamp") == "1980-01-01T00:00:00Z",
            "world receipt canonical ZIP timestamp mismatch")
    return value


def validate_boundary(names: set[str], *, client: bool) -> None:
    save_root = CLIENT_SAVE_ROOT if client else SERVER_SAVE_ROOT
    save_names = [name for name in names if name.startswith(("overrides/saves/" if client else "saves/"))]
    require(save_names, "archive is missing the authored save")
    require(all(name.startswith(save_root + "/") for name in save_names), "archive contains an extra or renamed save")
    profile_path = CLIENT_LOST_CITIES_PROFILE_PATH if client else SERVER_LOST_CITIES_PROFILE_PATH
    profile_root = PurePosixPath(profile_path).parent.as_posix().casefold() + "/"
    profile_names = {name for name in names if name.casefold().startswith(profile_root)}
    require(profile_names == {profile_path},
            f"archive Lost Cities profile set is not exact: {sorted(profile_names)}")
    license_root = CLIENT_LICENSE_ROOT if client else SERVER_LICENSE_ROOT
    actual_licenses = {
        name for name in names if name.casefold().startswith(license_root.casefold() + "/")
    }
    require(actual_licenses == archive_license_paths(client=client),
            f"archive license set is not exact: {sorted(actual_licenses)}")
    for name in names:
        lowered = name.casefold().replace("_", " ").replace("-", " ")
        require("mosslorn" not in lowered and "future city" not in lowered and "downloaded city" not in lowered,
                f"archive contains forbidden downloaded-city payload: {name}")
        require(not any(token in name.casefold() for token in FORBIDDEN_QUALIFICATION_PATH_TOKENS),
                f"archive contains forbidden qualification-only payload: {name}")
        parts = {part.casefold() for part in PurePosixPath(name).parts}
        require(not ({"world", "worlds", "maps"} & parts), f"archive contains an extra world/map path: {name}")


def parse_index(blob: bytes, label: str) -> dict[str, object]:
    value = parse_canonical_json(blob, label)
    require(value.get("formatVersion") == 1 and value.get("game") == "minecraft", f"{label} format mismatch")
    dependencies = value.get("dependencies")
    require(dependencies == {"forge": "47.3.3", "minecraft": "1.20.1"}, f"{label} loader identity mismatch")
    files = value.get("files")
    require(isinstance(files, list) and all(isinstance(record, dict) for record in files), f"{label} files list malformed")
    paths = [record.get("path") for record in files]
    require(all(isinstance(path, str) for path in paths), f"{label} has a non-string file path")
    require(len(paths) == len(set(paths)) == len({str(path).casefold() for path in paths}), f"{label} file paths collide")
    cloth = [path for path in paths if "cloth-config" in str(path).casefold()]
    require(cloth == [DEPENDENCY_PATHS["cloth_config"]], f"{label} must contain one exact Cloth Config input")
    for expected in DEPENDENCY_PATHS.values():
        require(paths.count(expected) == 1, f"{label} must contain exactly one {expected}")
    return value


def dependency_records(index: dict[str, object]) -> dict[str, dict[str, object]]:
    files = index["files"]
    assert isinstance(files, list)
    by_path = {record["path"]: record for record in files if isinstance(record, dict)}
    return {key: by_path[path] for key, path in DEPENDENCY_PATHS.items()}


def validate_dependency_jar(path: Path, blob: bytes, record: dict[str, object], label: str) -> None:
    expected_name = PurePosixPath(str(record.get("path"))).name
    require(path.name == expected_name, f"{label} filename must be {expected_name}")
    require(record.get("fileSize") == len(blob), f"{label} byte count differs from MRPack index")
    hashes = record.get("hashes")
    require(isinstance(hashes, dict), f"{label} index hashes are missing")
    require(hashes.get("sha1") == sha1_bytes(blob), f"{label} SHA-1 differs from MRPack index")
    require(hashes.get("sha512") == sha512_bytes(blob), f"{label} SHA-512 differs from MRPack index")
    read_zip(blob, label, allow_directories=True)


def required_jar_member(entries: dict[str, bytes], member: str, label: str,
                        required_phrases: tuple[bytes, ...]) -> bytes:
    require(member in entries, f"{label} is missing required license member {member}")
    blob = entries[member]
    for phrase in required_phrases:
        require(phrase in blob, f"{label} license member {member} is not the expected license text")
    return blob


def make_license_matrix(license_blobs: dict[str, bytes]) -> bytes:
    return canonical_text(f"""# Operation Ashen Span embedded dependency license matrix

This matrix covers every JAR embedded directly in the RC6 client or matched
server overlay. Hosted MRPack dependencies remain governed by their indexed
project records and the inherited third-party notice.

| Runtime artifact | Pinned version | Declared license | Included copy | License SHA-256 |
| --- | --- | --- | --- | --- |
| Architectury API | 9.2.14 Forge | GNU LGPLv3 / LGPL-3 | `{LICENSE_FILENAMES['architectury']}` | `{sha256_bytes(license_blobs['architectury'])}` |
| Cloth Config | 11.1.136 Forge | GNU LGPLv3 | `{LICENSE_FILENAMES['cloth_config']}` | `{sha256_bytes(license_blobs['cloth_config'])}` |
| GeckoLib | 4.4.9 Forge | MIT | `{LICENSE_FILENAMES['geckolib']}` | `{sha256_bytes(license_blobs['geckolib'])}` |
| Pomkots Mechs mp.25 | {MP25_VERSION} | MIT | `{LICENSE_FILENAMES['mp25']}` | `{sha256_bytes(license_blobs['mp25'])}` |
| Cold Ruin Sector 01 asset | {ASSET_VERSION} | MIT | `{LICENSE_FILENAMES['asset']}` | `{sha256_bytes(license_blobs['asset'])}` |
| The Lost Cities | {LOST_CITIES_VERSION} | MIT | `{LICENSE_FILENAMES['lost_cities']}` | `{sha256_bytes(license_blobs['lost_cities'])}` |

The exact Architectury JAR contains no license member. Its Forge metadata
declares GNU LGPLv3; the included full LGPLv3 text is byte-identical to the
`LICENSE.md` carried by the exact Cloth Config JAR. The Lost Cities notice is
the exact asset-JAR notice and contains the full MIT grant plus upstream source.

This is reproducible archive evidence, not a claim that public-platform
permission review or live gameplay qualification has been completed.
""")


def make_license_bundle(mp25: bytes, lost: bytes, asset: bytes, asset_notice: bytes,
                        dependencies: dict[str, bytes]) -> dict[str, bytes]:
    dependency_entries = {
        key: read_zip(blob, f"{key} license source", allow_directories=True)
        for key, blob in dependencies.items()
    }
    expected_mod_ids = {
        "architectury": "architectury",
        "cloth_config": "cloth_config",
        "geckolib": "geckolib",
    }
    for key, mod_id in expected_mod_ids.items():
        metadata = parse_mods_toml(dependency_entries[key], f"{key} dependency")
        unique_mod(metadata, mod_id, f"{key} dependency")
        declared = str(metadata.get("license", "")).upper()
        if key in {"architectury", "cloth_config"}:
            require("LGPL" in declared, f"{key} dependency does not declare LGPL")
        else:
            require("MIT" in declared, "GeckoLib dependency does not declare MIT")

    cloth_license = required_jar_member(
        dependency_entries["cloth_config"], "LICENSE.md", "Cloth Config",
        (b"GNU Lesser General Public License", b"Version 3"),
    )
    gecko_license = required_jar_member(
        dependency_entries["geckolib"], "LICENSE", "GeckoLib",
        (b"MIT License", b"Permission is hereby granted"),
    )
    mp25_entries = read_zip(mp25, "mp.25 license source", allow_directories=True)
    mp25_license = required_jar_member(
        mp25_entries, "LICENSE", "Pomkots Mechs mp.25",
        (b"MIT License", b"Permission is hereby granted"),
    )
    asset_entries = read_zip(asset, "asset license source", allow_directories=True)
    asset_license = required_jar_member(
        asset_entries, "META-INF/LICENSE", "Cold Ruin Sector 01 asset",
        (b"MIT License", b"Permission is hereby granted"),
    )
    require(asset_entries.get("META-INF/THIRD_PARTY_NOTICES.md") == asset_notice,
            "asset third-party notice changed between validation and license extraction")
    require(b"The Lost Cities" in asset_notice and b"Permission is hereby granted" in asset_notice,
            "asset notice does not contain the complete Lost Cities MIT notice")
    lost_metadata = parse_mods_toml(
        read_zip(lost, "Lost Cities license source", allow_directories=True),
        "Lost Cities JAR",
    )
    require("MIT" in str(lost_metadata.get("license", "")).upper(),
            "Lost Cities metadata does not declare MIT")

    license_blobs = {
        "architectury": cloth_license,
        "cloth_config": cloth_license,
        "geckolib": gecko_license,
        "mp25": mp25_license,
        "asset": asset_license,
        "lost_cities": asset_notice,
    }
    license_blobs["matrix"] = make_license_matrix(license_blobs)
    require(set(license_blobs) == set(LICENSE_FILENAMES), "license bundle component set drifted")
    return license_blobs


def archive_license_paths(*, client: bool) -> set[str]:
    root = CLIENT_LICENSE_ROOT if client else SERVER_LICENSE_ROOT
    return {f"{root}/{filename}" for filename in LICENSE_FILENAMES.values()}


def add_license_bundle(entries: dict[str, bytes], license_blobs: dict[str, bytes],
                       *, client: bool) -> None:
    root = CLIENT_LICENSE_ROOT if client else SERVER_LICENSE_ROOT
    require(not any(name.casefold().startswith(root.casefold() + "/") for name in entries),
            f"input archive already contains the reserved {root} tree")
    for key, filename in LICENSE_FILENAMES.items():
        entries[f"{root}/{filename}"] = license_blobs[key]


def replace_options(blob: bytes) -> bytes:
    try:
        text = blob.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise BuildError(f"base options.txt is not UTF-8: {exc}") from exc
    lines = text.splitlines()
    keys = [line.split(":", 1)[0] for line in lines if ":" in line]
    require(keys.count("renderDistance") == 1, "base options must contain one renderDistance")
    require(keys.count("simulationDistance") == 1, "base options must contain one simulationDistance")
    output = []
    for line in lines:
        if line.startswith("renderDistance:"):
            output.append("renderDistance:6")
        elif line.startswith("simulationDistance:"):
            output.append("simulationDistance:6")
        else:
            output.append(line)
    result = canonical_text("\n".join(output))
    rendered = result.decode("utf-8")
    require(rendered.count("renderDistance:6\n") == 1, "client render distance is not exactly 6")
    require(rendered.count("simulationDistance:6\n") == 1, "client simulation distance is not exactly 6")
    return result


def replace_index(blob: bytes) -> bytes:
    value = parse_index(blob, "base Modrinth index")
    require(value.get("versionId") == BASE_VERSION_ID, "base MRPack is not sealed mp.24 RC4")
    value["versionId"] = VERSION_ID
    value["name"] = DISPLAY_NAME
    value["summary"] = SUMMARY
    return canonical_json(value)


def server_properties() -> bytes:
    return canonical_text("""allow-flight=true
difficulty=normal
enable-command-block=false
enable-query=false
enable-rcon=false
generate-structures=false
level-name=saves/cold_ruin_sector_01
max-players=1
motd=Operation Ashen Span MP25 RC6 - offline candidate
online-mode=true
simulation-distance=6
spawn-animals=false
spawn-monsters=false
spawn-npcs=false
view-distance=6
white-list=true""")


def pomkots_safety_config() -> bytes:
    """Exact common config required by the mission's no-block-destruction gate."""
    return canonical_json({
        "consumeBlocksWhenPlacing": True,
        "debugModeEnabled": False,
        "dropItemsWhenDestroyBlock": False,
        "enableEntityBlockDestruction": False,
        "enableHudHealthBar": True,
        "enablePartsLevelCompatibility": False,
        "enablePlayerVehicleBlockDestruction": False,
        "nonDestructiveBlocks": (
            "minecraft:bedrock,minecraft:structure_void,minecraft:structure_block"
        ),
    })


def artifact_record(name: str, blob: bytes) -> dict[str, object]:
    return {"file": name, "bytes": len(blob), "sha256": sha256_bytes(blob)}


@dataclass(frozen=True)
class CandidateInputs:
    base_mrpack: Path
    mp25_jar: Path
    mp25_sha256: str
    lost_cities_jar: Path
    asset_jar: Path
    asset_sha256: str
    asset_receipt: Path
    world_dir: Path
    world_archive: Path
    world_sha256: str
    world_receipt: Path
    architectury_jar: Path
    cloth_config_jar: Path
    geckolib_jar: Path
    source_commit: str


@dataclass(frozen=True)
class LoadedInputs:
    base_blob: bytes
    base_entries: dict[str, bytes]
    base_index: dict[str, object]
    mp25_blob: bytes
    lost_blob: bytes
    asset_blob: bytes
    asset_notice: bytes
    asset_profile: bytes
    asset_receipt_blob: bytes
    world_tree: dict[str, bytes]
    world_input_blob: bytes
    world_receipt_blob: bytes
    dependency_blobs: dict[str, bytes]
    license_blobs: dict[str, bytes]


def load_inputs(inputs: CandidateInputs) -> LoadedInputs:
    require_safety_envelope_resolution()
    mp25_sha = normalized_sha256(inputs.mp25_sha256, "mp.25 SHA-256")
    asset_sha = normalized_sha256(inputs.asset_sha256, "asset SHA-256")
    world_sha = normalized_sha256(inputs.world_sha256, "world SHA-256")
    require(mp25_sha == RC6_MP25_SHA256,
            "mp.25 input is not the exact RC6 production JAR")
    require(asset_sha == RC5_ASSET_SHA256,
            "asset input is not the immutable RC5 Sector 01 JAR")
    require(world_sha == RC5_WORLD_BUILDER_ARCHIVE_SHA256,
            "world input is not the immutable RC5 world-builder archive")
    validate_source_commit(inputs.source_commit)
    file_paths = [
        inputs.base_mrpack, inputs.mp25_jar, inputs.lost_cities_jar, inputs.asset_jar,
        inputs.asset_receipt, inputs.world_archive, inputs.world_receipt,
        inputs.architectury_jar, inputs.cloth_config_jar, inputs.geckolib_jar,
    ]
    resolved = [path.resolve(strict=False) for path in file_paths]
    require(len(resolved) == len(set(resolved)), "candidate input files must be distinct")
    require(inputs.mp25_jar.name == MP25_NAME, f"mp.25 input filename must be {MP25_NAME}")
    require(inputs.lost_cities_jar.name == LOST_CITIES_NAME, f"Lost Cities input filename must be {LOST_CITIES_NAME}")
    require(inputs.asset_jar.name == ASSET_NAME, f"asset input filename must be {ASSET_NAME}")
    require(inputs.world_archive.name == WORLD_BUILDER_ARCHIVE_NAME,
            f"world-builder archive input filename must be {WORLD_BUILDER_ARCHIVE_NAME}")

    base_blob = read_stable(inputs.base_mrpack, "sealed mp.24 RC4 MRPack", BASE_SHA256, BASE_BYTES)
    base_entries = read_zip(base_blob, "sealed mp.24 RC4 MRPack", canonical=True, compression=zipfile.ZIP_STORED)
    require(BASE_MOD_PATH in base_entries, "sealed base is missing its mp.24 JAR")
    require(sha256_bytes(base_entries[BASE_MOD_PATH]) == BASE_MOD_SHA256, "sealed base embedded mp.24 JAR changed")
    require(INDEX_PATH in base_entries and OPTIONS_PATH in base_entries and CLIENT_NOTICE_PATH in base_entries,
            "sealed base is missing required MRPack members")
    require(not any(name.startswith("overrides/saves/") for name in base_entries), "sealed base unexpectedly contains a save")
    base_index = parse_index(base_entries[INDEX_PATH], "sealed mp.24 index")
    require(base_index.get("versionId") == BASE_VERSION_ID, "sealed base version identity changed")

    mp25_blob = read_stable(inputs.mp25_jar, "mp.25 JAR", mp25_sha, RC6_MP25_BYTES)
    validate_mp25_jar(mp25_blob, mp25_sha)
    lost_blob = read_stable(inputs.lost_cities_jar, "Lost Cities JAR", LOST_CITIES_SHA256, LOST_CITIES_BYTES)
    validate_lost_cities_jar(lost_blob)
    asset_blob = read_stable(inputs.asset_jar, "Sector 01 asset JAR", asset_sha)
    asset_notice, asset_profile = validate_asset_jar(asset_blob, asset_sha)
    asset_receipt_blob = read_stable(inputs.asset_receipt, "asset build receipt")
    validate_asset_receipt(asset_receipt_blob, asset_sha)

    world_tree = read_world_tree(inputs.world_dir)
    world_facts = verify_bounded_world(inputs.world_dir, asset_sha, world_tree)
    world_input_blob = read_stable(
        inputs.world_archive, "world-builder archive", world_sha,
        max_bytes=MAX_WORLD_BYTES,
    )
    validate_world_archive(world_input_blob, world_tree)
    world_receipt_blob = read_stable(inputs.world_receipt, "world build receipt")
    validate_world_receipt(
        world_receipt_blob, world_input_blob, world_sha, asset_sha, world_tree, world_facts,
    )

    records = dependency_records(base_index)
    dependency_paths = {
        "architectury": inputs.architectury_jar,
        "cloth_config": inputs.cloth_config_jar,
        "geckolib": inputs.geckolib_jar,
    }
    dependency_blobs: dict[str, bytes] = {}
    for key in ("architectury", "cloth_config", "geckolib"):
        blob = read_stable(dependency_paths[key], f"{key} server dependency")
        validate_dependency_jar(dependency_paths[key], blob, records[key], f"{key} server dependency")
        dependency_blobs[key] = blob
    require(sum("cloth-config" in Path(DEPENDENCY_PATHS[key]).name.casefold() for key in dependency_blobs) == 1,
            "server inputs must contain exactly one Cloth Config JAR")
    license_blobs = make_license_bundle(
        mp25_blob, lost_blob, asset_blob, asset_notice, dependency_blobs,
    )

    return LoadedInputs(
        base_blob, base_entries, base_index, mp25_blob, lost_blob, asset_blob,
        asset_notice, asset_profile, asset_receipt_blob, world_tree, world_input_blob,
        world_receipt_blob, dependency_blobs, license_blobs,
    )


def make_notices(loaded: LoadedInputs) -> bytes:
    base_notice = loaded.base_entries[CLIENT_NOTICE_PATH].decode("utf-8")
    asset_notice = loaded.asset_notice.decode("utf-8")
    return canonical_text(f"""# Operation Ashen Span — third-party notices

Status: offline mp.25/RC6 candidate; not live-qualified or released.

## Inherited mp.24 notices

{base_notice.rstrip()}

## Cold Ruin Sector 01 and The Lost Cities

Exact Lost Cities binary: `{LOST_CITIES_NAME}`
SHA-256: `{LOST_CITIES_SHA256}`

{asset_notice.rstrip()}

## Embedded license copies

The client MRPack carries the exact fail-closed license set under
`overrides/licenses/`; the matched server overlay carries the same bytes under
`licenses/`. `DEPENDENCY_LICENSE_MATRIX.md` explains the source of every copy.
""")


def make_client_entries(loaded: LoadedInputs, notice: bytes) -> dict[str, bytes]:
    result = dict(loaded.base_entries)
    del result[BASE_MOD_PATH]
    result[INDEX_PATH] = replace_index(loaded.base_entries[INDEX_PATH])
    result[OPTIONS_PATH] = replace_options(loaded.base_entries[OPTIONS_PATH])
    result[CLIENT_NOTICE_PATH] = notice
    result[CLIENT_POMKOTS_CONFIG_PATH] = pomkots_safety_config()
    result[CLIENT_LOST_CITIES_PROFILE_PATH] = loaded.asset_profile
    result[CLIENT_MOD_PATHS["mp25"]] = loaded.mp25_blob
    result[CLIENT_MOD_PATHS["lost_cities"]] = loaded.lost_blob
    result[CLIENT_MOD_PATHS["asset"]] = loaded.asset_blob
    for relative, blob in loaded.world_tree.items():
        result[f"{CLIENT_SAVE_ROOT}/{relative}"] = blob
    add_license_bundle(result, loaded.license_blobs, client=True)
    validate_boundary(set(result), client=True)
    require(sum("cloth-config" in str(record.get("path", "")).casefold()
                for record in parse_index(result[INDEX_PATH], "generated client index")["files"]) == 1,
            "generated client has more than one Cloth Config input")
    require(result[OPTIONS_PATH].decode("utf-8").count("renderDistance:6\n") == 1,
            "generated client does not pin render distance 6")
    require(result[CLIENT_LOST_CITIES_PROFILE_PATH] == loaded.asset_profile,
            "generated client lost the exact embedded Lost Cities profile")
    return result


def make_world_entries(world_tree: dict[str, bytes]) -> dict[str, bytes]:
    return {f"{SERVER_SAVE_ROOT}/{relative}": blob for relative, blob in world_tree.items()}


def make_server_entries(loaded: LoadedInputs, notice: bytes) -> dict[str, bytes]:
    result = make_world_entries(loaded.world_tree)
    result.update({
        SERVER_PROPERTIES_PATH: server_properties(),
        SERVER_POMKOTS_CONFIG_PATH: pomkots_safety_config(),
        SERVER_LOST_CITIES_PROFILE_PATH: loaded.asset_profile,
        NOTICE_NAME: notice,
        f"mods/{MP25_NAME}": loaded.mp25_blob,
        f"mods/{LOST_CITIES_NAME}": loaded.lost_blob,
        f"mods/{ASSET_NAME}": loaded.asset_blob,
    })
    for key, blob in loaded.dependency_blobs.items():
        result[f"mods/{PurePosixPath(DEPENDENCY_PATHS[key]).name}"] = blob
    add_license_bundle(result, loaded.license_blobs, client=False)
    validate_boundary(set(result), client=False)
    mod_names = [PurePosixPath(name).name for name in result if name.startswith("mods/")]
    require(sum("cloth-config" in name.casefold() for name in mod_names) == 1,
            "server overlay must contain exactly one Cloth Config JAR")
    require(set(name for name in result if name.startswith("mods/")) == {
        f"mods/{MP25_NAME}", f"mods/{LOST_CITIES_NAME}", f"mods/{ASSET_NAME}",
        *(f"mods/{PurePosixPath(path).name}" for path in DEPENDENCY_PATHS.values()),
    }, "server overlay mod set differs from the exact matched runtime")
    require(result[SERVER_POMKOTS_CONFIG_PATH] == pomkots_safety_config(),
            "server overlay lost the exact no-block-destruction config")
    require(result[SERVER_LOST_CITIES_PROFILE_PATH] == loaded.asset_profile,
            "server overlay lost the exact embedded Lost Cities profile")
    return result


def make_runbook(records: dict[str, dict[str, object]]) -> bytes:
    return canonical_text(f"""# Operation Ashen Span mp.25/RC6 runbook

Status: **finished offline candidate only; live validation is pending**.

This is one authored 10–13 minute single-player level, Cold Ruin Sector 01. It
is not a campaign, endless mode, progression system, or public release.

## Candidate payloads

- Client MRPack: `{MRPACK_NAME}` — `{records['mrpack']['sha256']}`
- Server overlay: `{SERVER_NAME}` — `{records['server']['sha256']}`
- World archive: `{WORLD_NAME}` — `{records['world']['sha256']}`
- Matched Pomkots server/client JAR: `{MP25_NAME}` — `{records['mp25']['sha256']}`

Verify these four hashes against `SHA256SUMS.txt` before importing or copying
anything. RC6 replaces the production Pomkots JAR solely to admit the starting
player at the exact authored Garage deployment pad. The bounded world and asset
JAR remain byte-identical to RC5, and mission content, roster, tactics, and
balance are unchanged.

## Client requirements and clean import

- 64-bit Java 17; Minecraft 1.20.1; Forge 47.3.3.
- A Modrinth-compatible launcher such as Modrinth App or Prism Launcher.
- Network access during first import for the nine indexed hosted dependencies.
  The MRPack is reproducible offline evidence, not a fully offline installer.

Create a new empty launcher profile by importing `{MRPACK_NAME}`. Do not merge
it into an older Mech Arena instance and do not add, remove, or upgrade mods.
The imported instance must contain one save named `cold_ruin_sector_01`. Select
that world, then use the clickable Garage Fleet card or type
`/arena solo start <1-6>` to deploy one of Vanguard, Siege, Skirmisher,
Artillery, Duelist, or Trooper. A missing card or rejected command is a failed
validation boundary; do not force-start the mission.

Default mech controls are W/A/S/D to move, Space to jump/boost, Left Ctrl to
dash, left mouse for the right weapon, right mouse for the left weapon, P/O for
right/left shoulder weapons, Y to switch mode, and hold U for lock-on. All are
rebindable in Controls; `/mechhelp` repeats the card.

## Dedicated server installation

Use an empty, private Forge 47.3.3 server root with Java 17. Extract
`{SERVER_NAME}` into that root without flattening paths. Keep `online-mode=true`,
whitelist exactly the intended player, accept Mojang's EULA in the normal server
workflow, and launch Forge with `nogui`. Do not expose this offline candidate to
the public internet. The overlay pins `level-name=saves/cold_ruin_sector_01` and
contains the exact six-mod runtime; do not mix it with mp.24 or another mod set.

The client contains exactly one save at `saves/cold_ruin_sector_01` after import.
The server overlay uses `level-name=saves/cold_ruin_sector_01`. Client render and
simulation distance and dedicated-server view and simulation distance are all 6.
Both payloads pin the Pomkots common config with entity and player-vehicle block
destruction disabled. Both also materialize the exact asset-embedded Lost Cities
profile `{LOST_CITIES_PROFILE_RUNTIME_PATH}` (SHA-256 `{ASSET_PROFILE_SHA256}`);
mission startup independently rejects any other values.

During the first minute, wait for the Garage Fleet card, choose exactly one
build, mount the spawned mech, and follow the authored bridge route. There are
no random waves or drops. The service gantry is a one-shot mid-mission restore;
Gatekeeper R-01 and PMB04 Span Warden are the two authored boss encounters.

## Offline verification

Run `python tools/verify_ashen_span_mp25.py` with the same explicit input paths,
expected mp.25/asset/world SHA-256 values, and `--candidate-dir` pointing at this
directory. The verifier performs an independent byte reconstruction; a receipt
alone is not accepted as proof.

On the matched noninteractive dedicated server, console command
`arena solo validate` performs the read-only runtime preflight over all 680 stored
chunks and every authored physical marker without ticketing or generating chunks.

Do not launch an interactive client during this offline milestone. Import, play,
balance, FPS, compatibility, and soak checks remain in `{LIVE_VALIDATION_NAME}`.

## Known limitations and recovery

- All live feel, timing, readability, audio, controls, FPS, compatibility, and
  soak statements remain unmeasured until the separate protocol is signed.
- Public Modrinth publication permission review remains pending; do not upload.
- If startup validation fails, preserve `logs/latest.log`, stop the instance,
  verify hashes, and retry from a fresh profile or fresh server root. Never edit
  the frozen save to bypass a marker or gate.
- For rollback, follow `ROLLBACK.md`; never overwrite RC5 or sealed mp.24.
""")


def make_rollback() -> bytes:
    return canonical_text(f"""# Operation Ashen Span rollback

This mp.25/RC6 candidate is isolated. It does not replace a release alias,
overwrite immutable RC5, or mutate the sealed mp.24 RC4 directory.

Sealed mp.24 anchors:

- MRPack SHA-256: `{SEALED_MP24['mrpack_sha256']}`
- Forge JAR SHA-256: `{SEALED_MP24['jar_sha256']}`
- Manifest SHA-256: `{SEALED_MP24['manifest_sha256']}`

Rollback means stop the mp.25 server, restore the byte-matched mp.24 client/server
pair from its sealed directory, and select its prior world/config snapshot. Never
mix mp.24 and mp.25 JARs, never overwrite either candidate, and never rename or
retarget a release alias as part of rollback.
""")


def make_live_validation() -> bytes:
    return canonical_text(f"""# Operation Ashen Span live validation

Status: **AWAITING SEPARATE LIVE VALIDATION — no live claims are made here.**

Candidate ID: `{CANDIDATE_ID}`

Before each run, record the MRPack/server/world SHA-256 from `SHA256SUMS.txt`,
machine, OS, Java, launcher, input device, resolution, graphics settings, and a
SHA-256 for every retained log or capture. A run belongs to exactly one Garage
Fleet build. Never carry a save forward between builds.

For builds 1–6, capture: fresh-profile/import result; mission start and finish
times; total duration; R-01 and PMB04 phase timings; service use; death/retry or
disconnect/restore outcome; minimum/typical FPS and frame-pacing observations;
visual/audio/control findings; final exact-zero cleanup; and pass/fail with a
specific reason. Record the world archive byte count and stored chunk inventory
before and after the soak run.

Offline evidence may prove archive identity, bounded-world structure, automated
mission behavior, and cleanup invariants. It does not prove the following gates:

- [ ] Fresh Modrinth/Prism profile import and interactive client startup
- [ ] Full 10–13 minute mission completion with each of the six locked Garage Fleet builds
- [ ] Gatekeeper R-01 and Span Warden live balance/timing
- [ ] Visual readability, audio cues, and controller/keyboard accessibility
- [ ] FPS and frame pacing on the target machine
- [ ] Interactive integrated-client and dedicated-multiplayer compatibility smoke
- [ ] Retry, disconnect, restore, and exact-zero cleanup observed live
- [ ] Extended soak with no world growth or cleanup residue

Pass only when every checkbox is supported by hash-bound evidence, all six build
records pass, mission duration is 10–13 minutes for the intended evaluation
window, no P0/P1 defect remains, and before/after world/chunk evidence is clean.
Any missing field, artifact hash, build, or failure-path observation is PENDING,
not PASS. Do not convert unchecked items into measured facts in the offline
receipt or release copy.
""")


def make_manifest(inputs: CandidateInputs, loaded: LoadedInputs,
                  payloads: dict[str, bytes], archive_entries: dict[str, list[str]]) -> bytes:
    builder_blob = read_committed_builder_blob(inputs.source_commit)
    records = {name: artifact_record(name, blob) for name, blob in sorted(payloads.items())}
    value = {
        "schema_version": 1,
        "candidate_id": CANDIDATE_ID,
        "status": "offline-candidate-not-live-qualified",
        "identity": {
            "version_id": VERSION_ID,
            "display_name": DISPLAY_NAME,
            "minecraft": "1.20.1",
            "forge": "47.3.3",
            "map_id": "cold_ruin_sector_01",
            "mission_id": "operation_ashen_span",
            "source_commit": inputs.source_commit.lower(),
            "runtime_source_commit": RC6_RUNTIME_SOURCE_COMMIT,
        },
        "lineage": {
            "predecessor_candidate_id": RC5_CANDIDATE_ID,
            "production_jar_changed": True,
            "authored_world_changed": False,
            "mission_content_roster_tactics_balance_changed": False,
            "solo_start_player_pad_collision_fixed": True,
            "packaging_bytes_changed": True,
            "packaging_hardening_only": False,
            "runtime_anchors": {
                "runtime_source_commit": RC6_RUNTIME_SOURCE_COMMIT,
                "mp25_sha256": RC6_MP25_SHA256,
                "asset_sha256": RC5_ASSET_SHA256,
                "world_builder_archive_sha256": RC5_WORLD_BUILDER_ARCHIVE_SHA256,
                "packaged_world_sha256": RC5_PACKAGED_WORLD_SHA256,
            },
            "predecessor_runtime_anchors": {
                "runtime_source_commit": RC5_RUNTIME_SOURCE_COMMIT,
                "mp25_sha256": RC5_MP25_SHA256,
            },
        },
        "artifacts": records,
        "inputs": {
            "sealed_mp24_mrpack": artifact_record(inputs.base_mrpack.name, loaded.base_blob),
            "mp25_jar": artifact_record(MP25_NAME, loaded.mp25_blob),
            "lost_cities": artifact_record(LOST_CITIES_NAME, loaded.lost_blob),
            "asset": artifact_record(ASSET_NAME, loaded.asset_blob),
            "asset_lost_cities_profile": artifact_record(
                ASSET_PROFILE_MEMBER, loaded.asset_profile
            ),
            "asset_receipt": artifact_record(inputs.asset_receipt.name, loaded.asset_receipt_blob),
            "world_builder_archive": artifact_record(inputs.world_archive.name, loaded.world_input_blob),
            "world_receipt": artifact_record(inputs.world_receipt.name, loaded.world_receipt_blob),
            "world_tree_sha256": world_tree_sha256(loaded.world_tree),
            "server_dependencies": {
                key: artifact_record(PurePosixPath(DEPENDENCY_PATHS[key]).name, blob)
                for key, blob in sorted(loaded.dependency_blobs.items())
            },
        },
        "client_contract": {
            "save_roots": ["saves/cold_ruin_sector_01"],
            "view_distance": 6,
            "simulation_distance": 6,
            "cloth_config_inputs": 1,
            "entity_block_destruction": False,
            "player_vehicle_block_destruction": False,
            "embedded_mods": [MP25_NAME, LOST_CITIES_NAME, ASSET_NAME],
            "embedded_license_paths": sorted(archive_license_paths(client=True)),
            "qualification_payload_present": False,
            "lost_cities_profile": {
                "archive_path": CLIENT_LOST_CITIES_PROFILE_PATH,
                "runtime_path": LOST_CITIES_PROFILE_RUNTIME_PATH,
                "bytes": len(loaded.asset_profile),
                "sha256": sha256_bytes(loaded.asset_profile),
            },
        },
        "server_contract": {
            "level_name": "saves/cold_ruin_sector_01",
            "view_distance": 6,
            "simulation_distance": 6,
            "cloth_config_jars": 1,
            "natural_spawning": False,
            "entity_block_destruction": False,
            "player_vehicle_block_destruction": False,
            "embedded_license_paths": sorted(archive_license_paths(client=False)),
            "qualification_payload_present": False,
            "lost_cities_profile": {
                "archive_path": SERVER_LOST_CITIES_PROFILE_PATH,
                "runtime_path": LOST_CITIES_PROFILE_RUNTIME_PATH,
                "bytes": len(loaded.asset_profile),
                "sha256": sha256_bytes(loaded.asset_profile),
            },
        },
        "content_boundary": {
            "world_count": 1,
            "contains_mosslorn": False,
            "contains_downloaded_city": False,
            "client_lost_cities_profile_count": 1,
            "server_lost_cities_profile_count": 1,
            "compressed_world_budget_bytes": MAX_WORLD_BYTES,
            "world_builder_archive_bytes": len(loaded.world_input_blob),
        },
        "archive_contract": {
            "compression": "ZIP_DEFLATED-9",
            "timestamp": "1980-01-01T00:00:00Z",
            "unix_mode": "0644",
            "entries": archive_entries,
        },
        "distribution_hardening": {
            "embedded_license_bundle": {
                key: artifact_record(LICENSE_FILENAMES[key], blob)
                for key, blob in sorted(loaded.license_blobs.items())
            },
            "client_license_root": CLIENT_LICENSE_ROOT,
            "server_license_root": SERVER_LICENSE_ROOT,
            "qualification_probe_shipped": False,
            "public_platform_permission_review": "manual-pending",
            "fully_offline_installer": False,
        },
        "evidence_status": {
            "measured_offline": [
                "input hashes and JAR metadata",
                "independent archive reconstruction",
                "one bounded save and exact server overlay",
                "exact asset-embedded Lost Cities profile on client and server",
                "6/6 package settings",
                "canonical two-build byte equality",
            ],
            "awaiting_live_validation": True,
            "live_validation_file": LIVE_VALIDATION_NAME,
        },
        "builder": {
            "file": "tools/build_ashen_span_mp25.py",
            "bytes": len(builder_blob),
            "sha256": sha256_bytes(builder_blob),
        },
    }
    return canonical_json(value)


def make_receipt(inputs: CandidateInputs, loaded: LoadedInputs,
                 output_before_receipt: dict[str, bytes]) -> bytes:
    return canonical_json({
        "schema_version": 1,
        "builder": "operation-ashen-span-packager/1.1.0",
        "candidate_id": CANDIDATE_ID,
        "status": "offline-only-not-live-qualified",
        "source_commit": inputs.source_commit.lower(),
        "runtime_source_commit": RC6_RUNTIME_SOURCE_COMMIT,
        "runtime_anchors": {
            "mp25_sha256": RC6_MP25_SHA256,
            "asset_sha256": RC5_ASSET_SHA256,
            "world_builder_archive_sha256": RC5_WORLD_BUILDER_ARCHIVE_SHA256,
            "packaged_world_sha256": RC5_PACKAGED_WORLD_SHA256,
        },
        "predecessor_runtime_anchors": {
            "runtime_source_commit": RC5_RUNTIME_SOURCE_COMMIT,
            "mp25_sha256": RC5_MP25_SHA256,
        },
        "fixed_inputs": {
            "base_mp24_sha256": sha256_bytes(loaded.base_blob),
            "mp25_sha256": sha256_bytes(loaded.mp25_blob),
            "lost_cities_sha256": sha256_bytes(loaded.lost_blob),
            "asset_sha256": sha256_bytes(loaded.asset_blob),
            "asset_lost_cities_profile_sha256": sha256_bytes(loaded.asset_profile),
            "world_builder_archive_sha256": sha256_bytes(loaded.world_input_blob),
            "world_tree_sha256": world_tree_sha256(loaded.world_tree),
            "embedded_license_sha256": {
                key: sha256_bytes(blob) for key, blob in sorted(loaded.license_blobs.items())
            },
        },
        "offline_gates": {
            "exclusive_new_staging": True,
            "two_builds_byte_equal": True,
            "one_save": "cold_ruin_sector_01",
            "client_view_distance": 6,
            "client_simulation_distance": 6,
            "server_view_distance": 6,
            "server_simulation_distance": 6,
            "cloth_config_inputs": 1,
            "entity_block_destruction": False,
            "player_vehicle_block_destruction": False,
            "client_lost_cities_profile": CLIENT_LOST_CITIES_PROFILE_PATH,
            "server_lost_cities_profile": SERVER_LOST_CITIES_PROFILE_PATH,
            "lost_cities_profile_byte_identical": True,
            "mosslorn_present": False,
            "downloaded_city_present": False,
            "world_within_32_mib": len(loaded.world_input_blob) <= MAX_WORLD_BYTES,
            "embedded_license_bundle_complete": True,
            "qualification_probe_shipped": False,
            "production_jar_changed_from_rc5": True,
            "solo_start_player_pad_collision_fixed": True,
            "authored_world_changed_from_rc5": False,
            "mission_content_roster_tactics_balance_changed_from_rc5": False,
            "package_bytes_changed_from_rc5": True,
        },
        "outputs": {
            name: artifact_record(name, blob)
            for name, blob in sorted(output_before_receipt.items())
        },
        "deferred": [
            "interactive profile import",
            "10–13 minute gameplay and balance",
            "FPS and compatibility",
            "live retry/restore observation",
            "soak",
        ],
    })


def make_hashes(files: dict[str, bytes]) -> bytes:
    return canonical_text("\n".join(f"{sha256_bytes(files[name])} *{name}" for name in sorted(files)))


def build_candidate(inputs: CandidateInputs) -> dict[str, bytes]:
    loaded = load_inputs(inputs)
    notice = make_notices(loaded)
    client_entries = make_client_entries(loaded, notice)
    server_entries = make_server_entries(loaded, notice)
    world_entries = make_world_entries(loaded.world_tree)
    mrpack = make_canonical_zip(client_entries)
    server = make_canonical_zip(server_entries)
    world = make_canonical_zip(world_entries)
    require(len(world) <= MAX_WORLD_BYTES, "packaged world archive exceeds 32 MiB")
    require(sha256_bytes(world) == RC5_PACKAGED_WORLD_SHA256,
            "packaged world differs from the immutable RC5 world payload")

    core = {
        MRPACK_NAME: mrpack,
        SERVER_NAME: server,
        WORLD_NAME: world,
        MP25_NAME: loaded.mp25_blob,
        NOTICE_NAME: notice,
    }
    summary_records = {
        "mrpack": artifact_record(MRPACK_NAME, mrpack),
        "server": artifact_record(SERVER_NAME, server),
        "world": artifact_record(WORLD_NAME, world),
        "mp25": artifact_record(MP25_NAME, loaded.mp25_blob),
    }
    core[RUNBOOK_NAME] = make_runbook(summary_records)
    core[ROLLBACK_NAME] = make_rollback()
    core[LIVE_VALIDATION_NAME] = make_live_validation()
    archive_entries = {
        MRPACK_NAME: sorted(client_entries),
        SERVER_NAME: sorted(server_entries),
        WORLD_NAME: sorted(world_entries),
    }
    core[MANIFEST_NAME] = make_manifest(inputs, loaded, core, archive_entries)
    core[RECEIPT_NAME] = make_receipt(inputs, loaded, core)
    core[HASHES_NAME] = make_hashes(core)
    return core


def _write_exclusive(path: Path, blob: bytes) -> None:
    with path.open("xb") as stream:
        stream.write(blob)
        stream.flush()
        os.fsync(stream.fileno())


def _safe_directory_components(path: Path) -> tuple[Path, ...]:
    absolute = Path(os.path.abspath(path))
    require(bool(absolute.anchor), f"candidate output parent has no filesystem anchor: {path}")
    current = Path(absolute.anchor)
    components = [current]
    for part in absolute.parts[1:]:
        current /= part
        components.append(current)
    return tuple(components)


def _validate_safe_directory_chain(path: Path) -> Path:
    components = _safe_directory_components(path)
    for component in components:
        require(
            component.is_dir() and not is_link_or_reparse(component),
            f"candidate output parent chain has an unsafe component: {component}",
        )
    return components[-1].resolve(strict=True)


def _ensure_safe_directory_chain(path: Path) -> Path:
    components = _safe_directory_components(path)
    for component in components:
        if is_link_or_reparse(component):
            raise BuildError(f"candidate output parent chain has an unsafe component: {component}")
        if component.exists():
            require(component.is_dir(), f"candidate output parent chain has a non-directory: {component}")
            continue
        try:
            component.mkdir()
        except FileExistsError:
            pass
        require(
            component.is_dir() and not is_link_or_reparse(component),
            f"candidate output parent chain became unsafe while it was created: {component}",
        )
    return _validate_safe_directory_chain(path)


def publish_atomic(output_dir: Path, files: dict[str, bytes]) -> None:
    output_dir = Path(os.path.abspath(output_dir))
    require(
        not output_dir.exists() and not is_link_or_reparse(output_dir),
        f"refusing to overwrite existing or unsafe candidate output: {output_dir}",
    )
    parent = output_dir.parent
    resolved_parent = _ensure_safe_directory_chain(parent)
    output_dir = resolved_parent / output_dir.name
    require(
        not output_dir.exists() and not is_link_or_reparse(output_dir),
        f"refusing to overwrite existing or unsafe candidate output: {output_dir}",
    )
    stage = Path(tempfile.mkdtemp(prefix=f".{output_dir.name}.", suffix=".stage", dir=resolved_parent))
    published = False
    try:
        require(stage.resolve(strict=True).parent == resolved_parent, "staging directory escaped the output parent")
        for name in sorted(files):
            validate_archive_path(name, "candidate filename")
            _write_exclusive(stage / name, files[name])
        require({path.name for path in stage.iterdir()} == set(files), "staging directory file set changed")
        require(all((stage / name).read_bytes() == blob for name, blob in files.items()), "staged candidate bytes changed")
        require(_validate_safe_directory_chain(parent) == resolved_parent,
                "candidate output parent changed during publication")
        require(
            not output_dir.exists() and not is_link_or_reparse(output_dir),
            f"refusing to overwrite existing or unsafe candidate output: {output_dir}",
        )
        os.rename(stage, output_dir)
        published = True
    finally:
        if not published and stage.exists():
            require(stage.resolve(strict=True).parent == resolved_parent, "unsafe staging cleanup target")
            shutil.rmtree(stage)


def verify_existing(output_dir: Path, files: dict[str, bytes]) -> None:
    require(output_dir.is_dir() and not is_link_or_reparse(output_dir), f"candidate output is missing or unsafe: {output_dir}")
    children = list(output_dir.iterdir())
    require(all(path.is_file() and not is_link_or_reparse(path) for path in children), "candidate output contains a directory/link")
    require({path.name for path in children} == set(files), "candidate output file set differs from deterministic rebuild")
    for name, expected in files.items():
        require((output_dir / name).read_bytes() == expected, f"candidate output differs from rebuild: {name}")


def add_input_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--base-mrpack", type=Path, required=True)
    parser.add_argument("--mp25-jar", type=Path, required=True)
    parser.add_argument("--mp25-sha256", required=True)
    parser.add_argument("--lost-cities-jar", type=Path, required=True)
    parser.add_argument("--asset-jar", type=Path, required=True)
    parser.add_argument("--asset-sha256", required=True)
    parser.add_argument("--asset-receipt", type=Path, required=True)
    parser.add_argument("--world-dir", type=Path, required=True)
    parser.add_argument("--world-archive", type=Path, required=True)
    parser.add_argument("--world-sha256", required=True)
    parser.add_argument("--world-receipt", type=Path, required=True)
    parser.add_argument("--architectury-jar", type=Path, required=True)
    parser.add_argument("--cloth-config-jar", type=Path, required=True)
    parser.add_argument("--geckolib-jar", type=Path, required=True)
    parser.add_argument("--source-commit", required=True)


def inputs_from_args(args: argparse.Namespace) -> CandidateInputs:
    return CandidateInputs(
        args.base_mrpack, args.mp25_jar, args.mp25_sha256, args.lost_cities_jar,
        args.asset_jar, args.asset_sha256, args.asset_receipt, args.world_dir,
        args.world_archive, args.world_sha256, args.world_receipt,
        args.architectury_jar, args.cloth_config_jar, args.geckolib_jar,
        args.source_commit,
    )


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    add_input_arguments(parser)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--check", action="store_true", help="rebuild twice and compare an existing directory")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    try:
        inputs = inputs_from_args(args)
        first = build_candidate(inputs)
        second = build_candidate(inputs)
        require(first == second, "two complete candidate builds differ")
        if args.check:
            verify_existing(args.output_dir, first)
            action = "VERIFIED"
        else:
            publish_atomic(args.output_dir, first)
            action = "WROTE"
        print(f"{action} {args.output_dir}")
        print(f"MRPACK SHA-256 {sha256_bytes(first[MRPACK_NAME])}")
        print(f"SERVER SHA-256 {sha256_bytes(first[SERVER_NAME])}")
        print(f"WORLD SHA-256 {sha256_bytes(first[WORLD_NAME])}")
        return 0
    except (BuildError, OSError, UnicodeError, json.JSONDecodeError,
            tomllib.TOMLDecodeError, zipfile.BadZipFile) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
