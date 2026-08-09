#!/usr/bin/env python3
"""Independently verify an Operation Ashen Span mp.25/RC6 candidate.

This module imports neither the candidate builder nor a shared packaging
module.  Given the original explicit inputs, it reconstructs every expected
file and inspects the actual client, server, and world archives.  The generated
manifest and offline receipt are evidence outputs, never trusted inputs.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import io
import json
from pathlib import Path, PurePosixPath, PureWindowsPath
import re
import stat
import subprocess
import sys
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
CANDIDATE_TREE_DOMAIN = b"operation-ashen-span-candidate-tree-v1\0"
NOTICE_NAME = "THIRD_PARTY_NOTICES.md"
RUNBOOK_NAME = "RUNBOOK.md"
ROLLBACK_NAME = "ROLLBACK.md"
LIVE_VALIDATION_NAME = "ASHEN_SPAN_LIVE_VALIDATION.md"
OUTPUT_NAMES = {
    MRPACK_NAME, SERVER_NAME, WORLD_NAME, MP25_NAME, MANIFEST_NAME, RECEIPT_NAME,
    HASHES_NAME, NOTICE_NAME, RUNBOOK_NAME, ROLLBACK_NAME, LIVE_VALIDATION_NAME,
}

INDEX_PATH = "modrinth.index.json"
OPTIONS_PATH = "overrides/options.txt"
CLIENT_NOTICE_PATH = "overrides/THIRD_PARTY_NOTICES.md"
CLIENT_POMKOTS_CONFIG_PATH = "overrides/config/pomkotsmechs.json"
LOST_CITIES_PROFILE_RUNTIME_PATH = "config/lostcities/profiles/mecharena_sector01.json"
CLIENT_LOST_CITIES_PROFILE_PATH = f"overrides/{LOST_CITIES_PROFILE_RUNTIME_PATH}"
SERVER_LOST_CITIES_PROFILE_PATH = LOST_CITIES_PROFILE_RUNTIME_PATH
CLIENT_SAVE_ROOT = "overrides/saves/cold_ruin_sector_01"
SERVER_SAVE_ROOT = "saves/cold_ruin_sector_01"
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
# This proposed-RC6 verifier is intentionally hard-disabled. A future
# post-erratum candidate requires a new identity and receipt-bound verifier,
# not a mutable authorization label in this historical implementation.
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
HEX_40 = re.compile(r"^[0-9a-fA-F]{40}$")
HEX_64 = re.compile(r"^[0-9a-fA-F]{64}$")
BUILDER_REPO_PATH = "tools/build_ashen_span_mp25.py"
SEALED_MP24 = {
    "mrpack_sha256": BASE_SHA256,
    "jar_sha256": BASE_MOD_SHA256,
    "manifest_sha256": "D05FE1929349AD6973A6F76E6D0267D423E4F37BBCC32361CF27FAE6C63F63FB",
}


class VerifyError(RuntimeError):
    """The candidate differs from the independently reconstructed contract."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise VerifyError(message)


def require_safety_envelope_resolution() -> None:
    raise VerifyError(
        "RC6 verification is blocked unconditionally: genuine view-distance 6 player "
        "tickets exceed the authoritative 680-chunk safety envelope. A future "
        "post-erratum candidate requires a new identity and receipt-bound verifier; "
        "see docs/ashen-span-hardening/SAFETY_ENVELOPE_BLOCKER.md"
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


def normalize_sha(value: str, label: str) -> str:
    require(HEX_64.fullmatch(value) is not None, f"{label} is not a SHA-256")
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


def read_stable(path: Path, label: str, expected_sha: str | None = None,
                expected_bytes: int | None = None, max_bytes: int | None = None) -> bytes:
    require(path.is_file() and not is_link_or_reparse(path), f"{label} is missing or unsafe: {path}")
    before = path.stat()
    if max_bytes is not None:
        require(before.st_size <= max_bytes, f"{label} exceeds the {max_bytes}-byte safety limit")
    blob = path.read_bytes()
    after = path.stat()
    require((before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns)
            == (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns),
            f"{label} changed while read")
    if expected_bytes is not None:
        require(len(blob) == expected_bytes, f"{label} byte count mismatch")
    if expected_sha is not None:
        require(sha256_bytes(blob) == expected_sha, f"{label} SHA-256 mismatch")
    return blob


def checkout_eol_mismatches(listing: str) -> list[str]:
    """Independently detect physical bytes that violate tracked attributes."""
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
    """Reject clean-filter-equivalent bytes that change reconstructed artifacts."""
    try:
        result = subprocess.run(
            ["git", "ls-files", "--eol", "-z"], cwd=repo, check=False,
            capture_output=True, text=True, encoding="utf-8",
        )
    except (OSError, UnicodeError) as exc:
        raise VerifyError(f"cannot inspect physical checkout line endings: {exc}") from exc
    require(result.returncode == 0, "cannot inspect physical checkout line endings")
    mismatches = checkout_eol_mismatches(result.stdout)
    require(not mismatches,
            "physical checkout line endings violate .gitattributes: "
            + "; ".join(mismatches[:8]))


def validate_source_commit(value: str) -> str:
    """Require verification to run from the exact clean commit named by the candidate."""
    require(HEX_40.fullmatch(value) is not None, "source commit is not a Git object ID")
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
        raise VerifyError(f"cannot establish source commit identity: {exc}") from exc
    require(resolved.returncode == 0, "source commit does not resolve to a Git commit")
    require(head.returncode == 0, "cannot resolve current Git HEAD")
    commit = resolved.stdout.strip().lower()
    require(commit == head.stdout.strip().lower(), "source commit is not the checked-out HEAD")
    require(status.returncode == 0, "cannot inspect source working tree")
    require(not status.stdout.strip(), "source working tree is dirty; verify from the exact release commit")
    validate_checkout_eol(repo)
    return commit


def read_committed_builder_blob(source_commit: str) -> bytes:
    """Independently read canonical builder bytes from the declared Git tree."""
    require(HEX_40.fullmatch(source_commit) is not None,
            "source commit is not a Git object ID")
    repo = Path(__file__).resolve().parent.parent
    object_name = f"{source_commit.lower()}:{BUILDER_REPO_PATH}"
    try:
        result = subprocess.run(
            ["git", "cat-file", "blob", object_name],
            cwd=repo, check=False, capture_output=True,
        )
    except OSError as exc:
        raise VerifyError(f"cannot read committed candidate builder: {exc}") from exc
    require(result.returncode == 0,
            "candidate builder is missing from the declared source commit")
    require(0 < len(result.stdout) <= MAX_BUILDER_SOURCE_BYTES,
            "committed candidate builder has an invalid byte count")
    return result.stdout


def _zip_directory_limits(blob: bytes, label: str) -> None:
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
    require(central_size <= MAX_CENTRAL_DIRECTORY_BYTES, f"{label}: central directory too large")
    require(central_offset + central_size <= offset, f"{label}: central directory bounds invalid")


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
    require(raw and "\x00" not in raw and "\\" not in raw, f"{label}: unsafe archive path {raw!r}")
    directory = raw.endswith("/")
    candidate = raw[:-1] if directory else raw
    require(not directory or allow_directory, f"{label}: directory entry forbidden: {raw!r}")
    posix = PurePosixPath(candidate)
    require(candidate and not posix.is_absolute() and not PureWindowsPath(candidate).drive,
            f"{label}: absolute/drive path: {raw!r}")
    require("." not in posix.parts and ".." not in posix.parts and posix.as_posix() == candidate,
            f"{label}: noncanonical path: {raw!r}")
    return raw


def read_zip(blob: bytes, label: str, *, canonical: bool = False,
             compression: int | None = None, allow_directories: bool = False,
             include_directories: bool = False,
             max_uncompressed_bytes: int = MAX_ARCHIVE_UNCOMPRESSED_BYTES,
             max_entry_bytes: int = MAX_ARCHIVE_ENTRY_BYTES) -> dict[str, bytes]:
    try:
        _zip_directory_limits(blob, label)
        with zipfile.ZipFile(io.BytesIO(blob), "r") as archive:
            require(archive.comment == b"", f"{label}: ZIP comment forbidden")
            infos = archive.infolist()
            names = [validate_archive_path(info.filename, label, allow_directory=allow_directories) for info in infos]
            require(len(names) == len(set(names)) == len({name.casefold() for name in names}),
                    f"{label}: duplicate/case-colliding paths")
            total_uncompressed = sum(info.file_size for info in infos if not info.is_dir())
            require(total_uncompressed <= max_uncompressed_bytes,
                    f"{label}: archive expands past the {max_uncompressed_bytes}-byte safety limit")
            if canonical:
                require(names == sorted(names), f"{label}: entries not sorted")
            result: dict[str, bytes] = {}
            for info in infos:
                if info.is_dir():
                    require(allow_directories and not canonical, f"{label}: directory entry forbidden")
                    require(info.file_size == 0, f"{label}: directory entry must be empty: {info.filename}")
                    if include_directories:
                        result[info.filename] = b""
                    continue
                require(not info.flag_bits & 1, f"{label}: encrypted member: {info.filename}")
                require(info.compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED),
                        f"{label}: unsupported compression: {info.filename}")
                if canonical:
                    require(info.date_time == FIXED_TIMESTAMP, f"{label}: timestamp drift: {info.filename}")
                    require(info.create_system == 3, f"{label}: creator drift: {info.filename}")
                    require((info.external_attr >> 16) == FIXED_MODE, f"{label}: mode drift: {info.filename}")
                if compression is not None:
                    require(info.compress_type == compression, f"{label}: compression drift: {info.filename}")
                result[info.filename] = _read_zip_member(archive, info, label, max_entry_bytes)
            return result
    except (zipfile.BadZipFile, NotImplementedError, RuntimeError) as exc:
        raise VerifyError(f"{label}: malformed ZIP/JAR: {exc}") from exc


def make_zip(entries: dict[str, bytes], compression: int = zipfile.ZIP_DEFLATED) -> bytes:
    names = sorted(entries)
    require(len(names) == len({name.casefold() for name in names}), "expected archive paths case-collide")
    output = io.BytesIO()
    kwargs = {"compression": compression}
    if compression == zipfile.ZIP_DEFLATED:
        kwargs["compresslevel"] = 9
    with zipfile.ZipFile(output, "w", **kwargs) as archive:
        for name in names:
            validate_archive_path(name, "expected archive")
            info = zipfile.ZipInfo(name, FIXED_TIMESTAMP)
            info.create_system = 3
            info.compress_type = compression
            info.external_attr = FIXED_MODE << 16
            if compression == zipfile.ZIP_DEFLATED:
                archive.writestr(info, entries[name], compress_type=compression, compresslevel=9)
            else:
                archive.writestr(info, entries[name], compress_type=compression)
    return output.getvalue()


def parse_json(blob: bytes, label: str) -> dict[str, object]:
    try:
        value = json.loads(blob)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise VerifyError(f"{label} malformed: {exc}") from exc
    require(isinstance(value, dict) and blob == canonical_json(value), f"{label} is not canonical JSON")
    return value


def mods_metadata(entries: dict[str, bytes], label: str) -> dict[str, object]:
    require("META-INF/mods.toml" in entries, f"{label} lacks mods.toml")
    try:
        result = tomllib.loads(entries["META-INF/mods.toml"].decode("utf-8"))
    except (UnicodeDecodeError, tomllib.TOMLDecodeError) as exc:
        raise VerifyError(f"{label} mods.toml malformed: {exc}") from exc
    require(isinstance(result, dict), f"{label} mods.toml root invalid")
    return result


def exact_mod(metadata: dict[str, object], mod_id: str, label: str) -> dict[str, object]:
    mods = metadata.get("mods")
    require(isinstance(mods, list), f"{label} lacks mod declarations")
    found = [record for record in mods if isinstance(record, dict) and record.get("modId") == mod_id]
    require(len(found) == 1, f"{label} must declare exactly one {mod_id}")
    return found[0]


def inspect_mp25(blob: bytes, expected_sha: str) -> None:
    require(sha256_bytes(blob) == expected_sha, "mp.25 input hash mismatch")
    entries = read_zip(blob, "mp.25 JAR", allow_directories=True, include_directories=True)
    required = {
        "LICENSE", "META-INF/mods.toml",
        "grcmcs/minecraft/mods/pomkotsmechs/arena/ArenaManager.class",
        "grcmcs/minecraft/mods/pomkotsmechs/arena/AshenSpanDefinition.class",
        "grcmcs/minecraft/mods/pomkotsmechs/arena/AshenSpanDirector.class",
        "grcmcs/minecraft/mods/pomkotsmechs/arena/AshenSpanMapContract.class",
        "grcmcs/minecraft/mods/pomkotsmechs/arena/MissionGateLedger.class",
        "grcmcs/minecraft/mods/pomkotsmechs/entity/vehicle/custom/ArenaRivalPmvc01Entity.class",
    }
    require(required <= set(entries), f"mp.25 JAR missing runtime members: {sorted(required-set(entries))}")
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
    metadata = mods_metadata(entries, "mp.25 JAR")
    require(metadata.get("modLoader") == "javafml", "mp.25 is not javafml")
    require(exact_mod(metadata, "pomkotsmechs", "mp.25 JAR").get("version") == MP25_VERSION,
            "mp.25 metadata version mismatch")
    deps = metadata.get("dependencies", {}).get("pomkotsmechs", []) if isinstance(metadata.get("dependencies"), dict) else []
    require(sum(isinstance(item, dict) and item.get("modId") == "cloth_config" for item in deps) == 1,
            "mp.25 metadata does not contain exactly one Cloth Config dependency")


def inspect_lost_cities(blob: bytes) -> None:
    require(len(blob) == LOST_CITIES_BYTES and sha256_bytes(blob) == LOST_CITIES_SHA256,
            "Lost Cities exact binary identity mismatch")
    entries = read_zip(blob, "Lost Cities JAR", allow_directories=True)
    metadata = mods_metadata(entries, "Lost Cities JAR")
    exact_mod(metadata, "lostcities", "Lost Cities JAR")
    require("MIT" in str(metadata.get("license", "")).upper(), "Lost Cities license metadata mismatch")
    manifest = entries.get("META-INF/MANIFEST.MF", b"").decode("utf-8", "replace")
    require(f"Implementation-Version: {LOST_CITIES_VERSION}" in manifest, "Lost Cities version mismatch")


def inspect_asset_profile(blob: bytes) -> bytes:
    require(len(blob) == ASSET_PROFILE_BYTES and sha256_bytes(blob) == ASSET_PROFILE_SHA256,
            "embedded Lost Cities profile exact identity mismatch")
    value = parse_json(blob, "embedded Lost Cities profile")
    lostcity = value.get("lostcity")
    cities = value.get("cities")
    require(isinstance(lostcity, dict)
            and lostcity.get("worldStyle") == "mecharena_sector01:ashen_span_world"
            and lostcity.get("generateLoot") is False
            and lostcity.get("generateSpawners") is False,
            "embedded Lost Cities profile mission settings mismatch")
    require(isinstance(cities, dict) and cities.get("cityChance") == 0.0
            and value.get("cityspheres") == {"citySphereChance": 0.0, "onlyPredefined": True}
            and value.get("public") is False,
            "embedded Lost Cities profile boundary mismatch")
    return blob


def inspect_asset(blob: bytes, expected_sha: str) -> tuple[bytes, bytes]:
    require(sha256_bytes(blob) == expected_sha, "asset exact binary identity mismatch")
    entries = read_zip(blob, "Sector 01 asset JAR", allow_directories=True)
    required = {
        "META-INF/LICENSE", "META-INF/THIRD_PARTY_NOTICES.md", "META-INF/mecharena-sector01-build.json",
        "META-INF/mods.toml", ASSET_PROFILE_MEMBER,
        "data/mecharena_sector01/ashen_span/cold_ruin_sector_01.json",
        "data/mecharena_sector01/lostcities/predefinedcities/cold_ruin_sector_01.json",
    }
    require(required <= set(entries), f"asset JAR missing members: {sorted(required-set(entries))}")
    profile_members = {
        name for name in entries
        if name.casefold().startswith("config-template/lostcities/profiles/")
    }
    require(profile_members == {ASSET_PROFILE_MEMBER},
            f"asset JAR Lost Cities profile set mismatch: {sorted(profile_members)}")
    metadata = mods_metadata(entries, "asset JAR")
    require(metadata.get("modLoader") == "lowcodefml", "asset JAR is not low-code Forge")
    require(exact_mod(metadata, "mecharena_sector01", "asset JAR").get("version") == ASSET_VERSION,
            "asset version mismatch")
    deps = metadata.get("dependencies", {}).get("mecharena_sector01", []) if isinstance(metadata.get("dependencies"), dict) else []
    lost = [item for item in deps if isinstance(item, dict) and item.get("modId") == "lostcities"]
    require(len(lost) == 1 and lost[0].get("versionRange") == "[1.20-7.4.13]",
            "asset Lost Cities dependency mismatch")
    notice = entries["META-INF/THIRD_PARTY_NOTICES.md"]
    for phrase in (b"MIT License", b"Lost Cities", b"no downloaded city"):
        require(phrase.lower() in notice.lower(), f"asset notice missing {phrase!r}")
    return notice, inspect_asset_profile(entries[ASSET_PROFILE_MEMBER])


def generation_datapack_digest(entries: dict[str, bytes]) -> str:
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
    require(root.is_dir() and not is_link_or_reparse(root), f"world input missing/unsafe: {root}")
    result: dict[str, bytes] = {}
    measured_bytes = 0
    for path in sorted(root.rglob("*")):
        require(not is_link_or_reparse(path), f"world input link/reparse: {path}")
        if path.is_dir():
            continue
        require(path.is_file(), f"world input non-file: {path}")
        name = path.relative_to(root).as_posix()
        validate_archive_path(name, "world input")
        normalized = name.casefold().replace("_", " ").replace("-", " ")
        require(not any(term in normalized for term in ("mosslorn", "future city", "downloaded city")),
                f"world input forbidden city payload: {name}")
        blob = read_stable(
            path, f"world file {name}",
            max_bytes=MAX_WORLD_UNCOMPRESSED_BYTES - measured_bytes,
        )
        measured_bytes += len(blob)
        result[name] = blob
    required = {"level.dat", "data/mecharena_sector01_contract.dat", "serverconfig/lostcities-server.toml"}
    require(required <= set(result), f"world input missing required files: {sorted(required-set(result))}")
    require(any(name.startswith("region/") and name.endswith(".mca") for name in result), "world input lacks region terrain")
    require(all(PurePosixPath(name).parts[0] in {"level.dat", "data", "region", "serverconfig", "datapacks"}
                for name in result),
            "world input contains player state/extra dimensions/unbounded indices")
    require(len(result) == len({name.casefold() for name in result}), "world paths case-collide")
    generation_datapack_digest(result)
    return result


def verify_bounded_world(root: Path, asset_sha: str,
                         world_tree: dict[str, bytes]) -> dict[str, object]:
    try:
        facts = world_builder.verify_world(
            root,
            WORLD_PROFILE_SHA256,
            asset_sha.lower(),
            WORLD_SOURCE_CONTRACT_SHA256,
        )
    except (world_builder.BuildError, OSError, ValueError) as exc:
        raise VerifyError(f"bounded world verification failed: {exc}") from exc
    require(facts.get("terrain_chunk_count") == 680, "bounded world does not contain exactly 680 chunks")
    require(facts.get("authored_chunk_count") == 308, "bounded world does not contain 308 authored chunks")
    require(
        str(facts.get("curated_tree_sha256", "")).upper()
        == world_builder_curated_tree_hash(world_tree),
        "bounded world changed between tree read and physical verification",
    )
    return facts


def tree_hash(entries: dict[str, bytes]) -> str:
    digest = hashlib.sha256(b"operation-ashen-span-world-tree-v1\0")
    for name in sorted(entries):
        encoded = name.encode()
        blob = entries[name]
        digest.update(len(encoded).to_bytes(4, "big")); digest.update(encoded)
        digest.update(len(blob).to_bytes(8, "big")); digest.update(hashlib.sha256(blob).digest())
    return digest.hexdigest().upper()


def world_builder_curated_tree_hash(entries: dict[str, bytes]) -> str:
    digest = hashlib.sha256()
    for name in sorted(entries):
        blob = entries[name]
        digest.update(f"{name}\0{len(blob)}\0{sha256_bytes(blob).lower()}\n".encode("utf-8"))
    return digest.hexdigest().upper()


def inspect_world_builder_archive(blob: bytes, tree: dict[str, bytes]) -> None:
    require(len(blob) <= MAX_WORLD_BYTES, "world-builder archive exceeds 32 MiB")
    entries = read_zip(
        blob, "world-builder archive", canonical=True, compression=zipfile.ZIP_DEFLATED,
        max_uncompressed_bytes=MAX_WORLD_UNCOMPRESSED_BYTES,
    )
    roots = {PurePosixPath(name).parts[0] for name in entries}
    require(len(roots) == 1, "world-builder archive has multiple roots")
    root = next(iter(roots))
    require(root == WORLD_ARCHIVE_ROOT, "world-builder archive root mismatch")
    stripped = {PurePosixPath(name).relative_to(root).as_posix(): data for name, data in entries.items()}
    require(stripped == tree, "world-builder archive differs from curated directory")


def inspect_asset_receipt(blob: bytes, asset_sha: str) -> None:
    value = parse_json(blob, "asset receipt")
    artifact = value.get("artifact")
    require(isinstance(artifact, dict) and artifact.get("name") == ASSET_NAME
            and str(artifact.get("sha256", "")).upper() == asset_sha, "asset receipt identity mismatch")
    require(value.get("map_id") == "cold_ruin_sector_01" and value.get("mission_id") == "operation_ashen_span",
            "asset receipt map/mission mismatch")
    require(value.get("authored_chunk_count") == 308 and value.get("predefined_building_count") == 308,
            "asset receipt chunk/building count mismatch")
    require(value.get("lost_cities_version_range") == "[1.20-7.4.13]" and value.get("mode") == "full",
            "asset receipt version/mode mismatch")
    source_sha = value.get("source_sha256")
    require(value.get("profile") == WORLD_PROFILE_NAME and isinstance(source_sha, dict)
            and source_sha.get("lostcities-profile.json") == WORLD_PROFILE_SHA256,
            "asset receipt Lost Cities profile provenance mismatch")


def inspect_world_receipt(blob: bytes, world_blob: bytes, world_sha: str, asset_sha: str,
                          world_tree: dict[str, bytes], world_facts: dict[str, object]) -> None:
    value = parse_json(blob, "world receipt")
    require(value.get("schema") == WORLD_BUILDER_SCHEMA and value.get("builder") == WORLD_BUILDER_ID,
            "world receipt builder mismatch")
    require(value.get("map_id") == "cold_ruin_sector_01" and value.get("mission_id") == "operation_ashen_span"
            and value.get("map_version") == 1 and value.get("seed") == WORLD_SEED
            and value.get("minecraft") == "1.20.1" and value.get("forge") == "47.3.3",
            "world receipt identity mismatch")
    lost = value.get("lost_cities"); asset = value.get("asset"); world = value.get("world"); contract = value.get("contract")
    require(isinstance(lost, dict) and lost.get("version") == "7.4.13"
            and str(lost.get("sha256", "")).upper() == LOST_CITIES_SHA256, "world receipt Lost Cities mismatch")
    require(isinstance(asset, dict) and asset.get("name") == ASSET_NAME
            and str(asset.get("sha256", "")).upper() == asset_sha, "world receipt asset mismatch")
    require(value.get("profile") == {"name": WORLD_PROFILE_NAME, "sha256": WORLD_PROFILE_SHA256},
            "world receipt profile mismatch")
    require(value.get("source_contract") == {
        "name": WORLD_SOURCE_CONTRACT_NAME,
        "sha256": WORLD_SOURCE_CONTRACT_SHA256,
    }, "world receipt source contract mismatch")
    measured_datapack_sha = generation_datapack_digest(world_tree)
    require(value.get("generation_datapack") == {
        "name": GENERATION_DATAPACK_NAME,
        "biome": GENERATION_DATAPACK_BIOME,
        "sha256": measured_datapack_sha,
    }, "world receipt generation datapack mismatch")
    require(value.get("generation_runtime") == {
        "forge_libraries_sha256": WORLD_FORGE_LIBRARIES_SHA256,
        "java_executable_sha256": WORLD_JAVA_EXECUTABLE_SHA256,
    }, "world receipt generation runtime mismatch")
    require(isinstance(world, dict) and str(world.get("sha256", "")).upper() == world_sha == sha256_bytes(world_blob)
            and world.get("bytes") == len(world_blob), "world receipt archive mismatch")
    require(world.get("name") == WORLD_BUILDER_ARCHIVE_NAME and world.get("archive_root") == WORLD_ARCHIVE_ROOT,
            "world receipt archive identity mismatch")
    require(world.get("uncompressed_bytes") == sum(len(item) for item in world_tree.values()),
            "world receipt archive expansion mismatch")
    require(str(world.get("curated_tree_sha256", "")).upper() == world_builder_curated_tree_hash(world_tree),
            "world receipt curated tree mismatch")
    expected = {"dimension": WORLD_DIMENSION, "data_version": WORLD_DATA_VERSION,
                "content_chunk_count": 308, "safety_chunk_count": 680,
                "safety_chunk_digest": WORLD_SAFETY_CHUNK_DIGEST, "view_distance": 6,
                "simulation_distance": 6, "compressed_budget_bytes": MAX_WORLD_BYTES}
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
    }, "world receipt determinism mismatch")
    require(value.get("canonical_zip_timestamp") == "1980-01-01T00:00:00Z",
            "world receipt canonical ZIP timestamp mismatch")
    require(len(world_blob) <= MAX_WORLD_BYTES, "world-builder archive exceeds 32 MiB")


def parse_index(blob: bytes, label: str) -> dict[str, object]:
    value = parse_json(blob, label)
    require(value.get("formatVersion") == 1 and value.get("game") == "minecraft", f"{label} format mismatch")
    require(value.get("dependencies") == {"forge": "47.3.3", "minecraft": "1.20.1"}, f"{label} loader mismatch")
    files = value.get("files")
    require(isinstance(files, list) and all(isinstance(item, dict) for item in files), f"{label} files malformed")
    paths = [item.get("path") for item in files]
    require(all(isinstance(path, str) for path in paths), f"{label} has non-string path")
    require(len(paths) == len(set(paths)) == len({str(path).casefold() for path in paths}), f"{label} paths collide")
    require([path for path in paths if "cloth-config" in str(path).casefold()] == [DEPENDENCY_PATHS["cloth_config"]],
            f"{label} does not contain one exact Cloth Config input")
    require(all(paths.count(path) == 1 for path in DEPENDENCY_PATHS.values()), f"{label} dependency records mismatch")
    return value


def dependency_records(index: dict[str, object]) -> dict[str, dict[str, object]]:
    files = index["files"]
    assert isinstance(files, list)
    by_path = {item["path"]: item for item in files if isinstance(item, dict)}
    return {key: by_path[path] for key, path in DEPENDENCY_PATHS.items()}


def inspect_dependency(path: Path, blob: bytes, record: dict[str, object], label: str) -> None:
    require(path.name == PurePosixPath(str(record.get("path"))).name, f"{label} filename mismatch")
    hashes = record.get("hashes")
    require(record.get("fileSize") == len(blob) and isinstance(hashes, dict), f"{label} index identity malformed")
    require(hashes.get("sha1") == sha1_bytes(blob) and hashes.get("sha512") == sha512_bytes(blob),
            f"{label} hashes differ from client index")
    read_zip(blob, label, allow_directories=True)


def exact_license_member(entries: dict[str, bytes], member: str, label: str,
                         phrases: tuple[bytes, ...]) -> bytes:
    require(member in entries, f"{label} is missing required license member {member}")
    blob = entries[member]
    require(all(phrase in blob for phrase in phrases),
            f"{label} license member {member} is not the expected license text")
    return blob


def license_matrix(blobs: dict[str, bytes]) -> bytes:
    return canonical_text(f"""# Operation Ashen Span embedded dependency license matrix

This matrix covers every JAR embedded directly in the RC6 client or matched
server overlay. Hosted MRPack dependencies remain governed by their indexed
project records and the inherited third-party notice.

| Runtime artifact | Pinned version | Declared license | Included copy | License SHA-256 |
| --- | --- | --- | --- | --- |
| Architectury API | 9.2.14 Forge | GNU LGPLv3 / LGPL-3 | `{LICENSE_FILENAMES['architectury']}` | `{sha256_bytes(blobs['architectury'])}` |
| Cloth Config | 11.1.136 Forge | GNU LGPLv3 | `{LICENSE_FILENAMES['cloth_config']}` | `{sha256_bytes(blobs['cloth_config'])}` |
| GeckoLib | 4.4.9 Forge | MIT | `{LICENSE_FILENAMES['geckolib']}` | `{sha256_bytes(blobs['geckolib'])}` |
| Pomkots Mechs mp.25 | {MP25_VERSION} | MIT | `{LICENSE_FILENAMES['mp25']}` | `{sha256_bytes(blobs['mp25'])}` |
| Cold Ruin Sector 01 asset | {ASSET_VERSION} | MIT | `{LICENSE_FILENAMES['asset']}` | `{sha256_bytes(blobs['asset'])}` |
| The Lost Cities | {LOST_CITIES_VERSION} | MIT | `{LICENSE_FILENAMES['lost_cities']}` | `{sha256_bytes(blobs['lost_cities'])}` |

The exact Architectury JAR contains no license member. Its Forge metadata
declares GNU LGPLv3; the included full LGPLv3 text is byte-identical to the
`LICENSE.md` carried by the exact Cloth Config JAR. The Lost Cities notice is
the exact asset-JAR notice and contains the full MIT grant plus upstream source.

This is reproducible archive evidence, not a claim that public-platform
permission review or live gameplay qualification has been completed.
""")


def reconstruct_license_bundle(mp25: bytes, lost: bytes, asset: bytes,
                               asset_notice: bytes,
                               dependencies: dict[str, bytes]) -> dict[str, bytes]:
    dep_entries = {
        key: read_zip(blob, f"{key} license source", allow_directories=True)
        for key, blob in dependencies.items()
    }
    mod_ids = {"architectury": "architectury", "cloth_config": "cloth_config", "geckolib": "geckolib"}
    for key, mod_id in mod_ids.items():
        metadata = mods_metadata(dep_entries[key], f"{key} dependency")
        exact_mod(metadata, mod_id, f"{key} dependency")
        declared = str(metadata.get("license", "")).upper()
        if key in {"architectury", "cloth_config"}:
            require("LGPL" in declared, f"{key} dependency does not declare LGPL")
        else:
            require("MIT" in declared, "GeckoLib dependency does not declare MIT")
    cloth = exact_license_member(
        dep_entries["cloth_config"], "LICENSE.md", "Cloth Config",
        (b"GNU Lesser General Public License", b"Version 3"),
    )
    gecko = exact_license_member(
        dep_entries["geckolib"], "LICENSE", "GeckoLib",
        (b"MIT License", b"Permission is hereby granted"),
    )
    pomkots = exact_license_member(
        read_zip(mp25, "mp.25 license source", allow_directories=True),
        "LICENSE", "Pomkots Mechs mp.25",
        (b"MIT License", b"Permission is hereby granted"),
    )
    asset_entries = read_zip(asset, "asset license source", allow_directories=True)
    sector = exact_license_member(
        asset_entries, "META-INF/LICENSE", "Cold Ruin Sector 01 asset",
        (b"MIT License", b"Permission is hereby granted"),
    )
    require(asset_entries.get("META-INF/THIRD_PARTY_NOTICES.md") == asset_notice,
            "asset notice changed between inspection and license reconstruction")
    require(b"The Lost Cities" in asset_notice and b"Permission is hereby granted" in asset_notice,
            "asset notice does not contain the complete Lost Cities MIT notice")
    lost_meta = mods_metadata(
        read_zip(lost, "Lost Cities license source", allow_directories=True),
        "Lost Cities JAR",
    )
    require("MIT" in str(lost_meta.get("license", "")).upper(),
            "Lost Cities metadata does not declare MIT")
    blobs = {
        "architectury": cloth, "cloth_config": cloth, "geckolib": gecko,
        "mp25": pomkots, "asset": sector, "lost_cities": asset_notice,
    }
    blobs["matrix"] = license_matrix(blobs)
    require(set(blobs) == set(LICENSE_FILENAMES), "license bundle component set drifted")
    return blobs


def expected_license_paths(client: bool) -> set[str]:
    root = CLIENT_LICENSE_ROOT if client else SERVER_LICENSE_ROOT
    return {f"{root}/{filename}" for filename in LICENSE_FILENAMES.values()}


def install_license_bundle(entries: dict[str, bytes], blobs: dict[str, bytes], client: bool) -> None:
    root = CLIENT_LICENSE_ROOT if client else SERVER_LICENSE_ROOT
    require(not any(name.casefold().startswith(root.casefold() + "/") for name in entries),
            f"input archive already contains reserved {root} tree")
    for key, filename in LICENSE_FILENAMES.items():
        entries[f"{root}/{filename}"] = blobs[key]


def replace_index(blob: bytes) -> bytes:
    value = parse_index(blob, "base index")
    require(value.get("versionId") == BASE_VERSION_ID, "base index is not mp.24 RC4")
    value["versionId"] = VERSION_ID; value["name"] = DISPLAY_NAME; value["summary"] = SUMMARY
    return canonical_json(value)


def replace_options(blob: bytes) -> bytes:
    try:
        lines = blob.decode("utf-8").splitlines()
    except UnicodeDecodeError as exc:
        raise VerifyError(f"base options invalid UTF-8: {exc}") from exc
    keys = [line.split(":", 1)[0] for line in lines if ":" in line]
    require(keys.count("renderDistance") == keys.count("simulationDistance") == 1,
            "base options distance keys are not unique")
    result = canonical_text("\n".join(
        "renderDistance:6" if line.startswith("renderDistance:") else
        "simulationDistance:6" if line.startswith("simulationDistance:") else line
        for line in lines
    ))
    text = result.decode()
    require(text.count("renderDistance:6\n") == text.count("simulationDistance:6\n") == 1,
            "expected client options are not exact 6/6")
    return result


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


def boundary(names: set[str], client: bool) -> None:
    base = "overrides/saves/" if client else "saves/"
    required = CLIENT_SAVE_ROOT if client else SERVER_SAVE_ROOT
    saves = [name for name in names if name.startswith(base)]
    require(saves and all(name.startswith(required + "/") for name in saves), "archive save set is not exactly Cold Ruin Sector 01")
    profile_path = CLIENT_LOST_CITIES_PROFILE_PATH if client else SERVER_LOST_CITIES_PROFILE_PATH
    profile_root = PurePosixPath(profile_path).parent.as_posix().casefold() + "/"
    profiles = {name for name in names if name.casefold().startswith(profile_root)}
    require(profiles == {profile_path}, f"archive Lost Cities profile set is not exact: {sorted(profiles)}")
    license_root = CLIENT_LICENSE_ROOT if client else SERVER_LICENSE_ROOT
    licenses = {name for name in names if name.casefold().startswith(license_root.casefold() + "/")}
    require(licenses == expected_license_paths(client),
            f"archive license set is not exact: {sorted(licenses)}")
    for name in names:
        normalized = name.casefold().replace("_", " ").replace("-", " ")
        require(not any(term in normalized for term in ("mosslorn", "future city", "downloaded city")),
                f"archive forbidden city payload: {name}")
        require(not any(token in name.casefold() for token in FORBIDDEN_QUALIFICATION_PATH_TOKENS),
                f"archive contains forbidden qualification-only payload: {name}")
        require(not ({"world", "worlds", "maps"} & {part.casefold() for part in PurePosixPath(name).parts}),
                f"archive extra world/map path: {name}")


@dataclass(frozen=True)
class Inputs:
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
class Loaded:
    base_blob: bytes
    base_entries: dict[str, bytes]
    mp25: bytes
    lost: bytes
    asset: bytes
    asset_notice: bytes
    asset_profile: bytes
    asset_receipt: bytes
    world_tree: dict[str, bytes]
    world_input: bytes
    world_receipt: bytes
    dependencies: dict[str, bytes]
    license_blobs: dict[str, bytes]


def load(inputs: Inputs) -> Loaded:
    require_safety_envelope_resolution()
    mp25_sha = normalize_sha(inputs.mp25_sha256, "mp.25 SHA")
    asset_sha = normalize_sha(inputs.asset_sha256, "asset SHA")
    world_sha = normalize_sha(inputs.world_sha256, "world SHA")
    require(mp25_sha == RC6_MP25_SHA256, "mp.25 input is not the exact RC6 production JAR")
    require(asset_sha == RC5_ASSET_SHA256, "asset input is not the immutable RC5 Sector 01 JAR")
    require(world_sha == RC5_WORLD_BUILDER_ARCHIVE_SHA256,
            "world input is not the immutable RC5 world-builder archive")
    validate_source_commit(inputs.source_commit)
    require(inputs.mp25_jar.name == MP25_NAME and inputs.lost_cities_jar.name == LOST_CITIES_NAME
            and inputs.asset_jar.name == ASSET_NAME, "canonical input JAR filename mismatch")
    require(inputs.world_archive.name == WORLD_BUILDER_ARCHIVE_NAME,
            "canonical world-builder archive filename mismatch")
    paths = [inputs.base_mrpack, inputs.mp25_jar, inputs.lost_cities_jar, inputs.asset_jar,
             inputs.asset_receipt, inputs.world_archive, inputs.world_receipt,
             inputs.architectury_jar, inputs.cloth_config_jar, inputs.geckolib_jar]
    require(len(paths) == len({path.resolve(strict=False) for path in paths}), "input files are not distinct")

    base_blob = read_stable(inputs.base_mrpack, "sealed mp.24 MRPack", BASE_SHA256, BASE_BYTES)
    base_entries = read_zip(base_blob, "sealed mp.24 MRPack", canonical=True, compression=zipfile.ZIP_STORED)
    require(BASE_MOD_PATH in base_entries and sha256_bytes(base_entries[BASE_MOD_PATH]) == BASE_MOD_SHA256,
            "sealed base mp.24 member mismatch")
    require(not any(name.startswith("overrides/saves/") for name in base_entries), "sealed base contains an unexpected save")
    index = parse_index(base_entries[INDEX_PATH], "sealed mp.24 index")
    require(index.get("versionId") == BASE_VERSION_ID, "sealed base version mismatch")

    mp25 = read_stable(inputs.mp25_jar, "mp.25 JAR", mp25_sha, RC6_MP25_BYTES); inspect_mp25(mp25, mp25_sha)
    lost = read_stable(inputs.lost_cities_jar, "Lost Cities JAR", LOST_CITIES_SHA256, LOST_CITIES_BYTES); inspect_lost_cities(lost)
    asset = read_stable(inputs.asset_jar, "asset JAR", asset_sha)
    asset_notice, asset_profile = inspect_asset(asset, asset_sha)
    asset_receipt = read_stable(inputs.asset_receipt, "asset receipt"); inspect_asset_receipt(asset_receipt, asset_sha)
    world_tree = read_world_tree(inputs.world_dir)
    world_facts = verify_bounded_world(inputs.world_dir, asset_sha, world_tree)
    world_input = read_stable(
        inputs.world_archive, "world-builder archive", world_sha,
        max_bytes=MAX_WORLD_BYTES,
    ); inspect_world_builder_archive(world_input, world_tree)
    world_receipt = read_stable(inputs.world_receipt, "world receipt")
    inspect_world_receipt(world_receipt, world_input, world_sha, asset_sha, world_tree, world_facts)

    records = dependency_records(index)
    dep_paths = {"architectury": inputs.architectury_jar, "cloth_config": inputs.cloth_config_jar, "geckolib": inputs.geckolib_jar}
    dependencies: dict[str, bytes] = {}
    for key in ("architectury", "cloth_config", "geckolib"):
        blob = read_stable(dep_paths[key], f"{key} dependency")
        inspect_dependency(dep_paths[key], blob, records[key], f"{key} dependency")
        dependencies[key] = blob
    require(sum("cloth-config" in PurePosixPath(DEPENDENCY_PATHS[key]).name.casefold() for key in dependencies) == 1,
            "physical server inputs do not contain exactly one Cloth Config JAR")
    license_blobs = reconstruct_license_bundle(mp25, lost, asset, asset_notice, dependencies)
    return Loaded(base_blob, base_entries, mp25, lost, asset, asset_notice, asset_profile, asset_receipt,
                  world_tree, world_input, world_receipt, dependencies, license_blobs)


def notices(loaded: Loaded) -> bytes:
    return canonical_text(f"""# Operation Ashen Span — third-party notices

Status: offline mp.25/RC6 candidate; not live-qualified or released.

## Inherited mp.24 notices

{loaded.base_entries[CLIENT_NOTICE_PATH].decode('utf-8').rstrip()}

## Cold Ruin Sector 01 and The Lost Cities

Exact Lost Cities binary: `{LOST_CITIES_NAME}`
SHA-256: `{LOST_CITIES_SHA256}`

{loaded.asset_notice.decode('utf-8').rstrip()}

## Embedded license copies

The client MRPack carries the exact fail-closed license set under
`overrides/licenses/`; the matched server overlay carries the same bytes under
`licenses/`. `DEPENDENCY_LICENSE_MATRIX.md` explains the source of every copy.
""")


def client_entries(loaded: Loaded, notice: bytes) -> dict[str, bytes]:
    result = dict(loaded.base_entries); del result[BASE_MOD_PATH]
    result[INDEX_PATH] = replace_index(loaded.base_entries[INDEX_PATH])
    result[OPTIONS_PATH] = replace_options(loaded.base_entries[OPTIONS_PATH])
    result[CLIENT_NOTICE_PATH] = notice
    result[CLIENT_POMKOTS_CONFIG_PATH] = pomkots_safety_config()
    result[CLIENT_LOST_CITIES_PROFILE_PATH] = loaded.asset_profile
    result[CLIENT_MOD_PATHS["mp25"]] = loaded.mp25
    result[CLIENT_MOD_PATHS["lost_cities"]] = loaded.lost
    result[CLIENT_MOD_PATHS["asset"]] = loaded.asset
    result.update({f"{CLIENT_SAVE_ROOT}/{name}": blob for name, blob in loaded.world_tree.items()})
    install_license_bundle(result, loaded.license_blobs, True)
    boundary(set(result), True)
    require(result[CLIENT_LOST_CITIES_PROFILE_PATH] == loaded.asset_profile,
            "expected client lost the exact embedded Lost Cities profile")
    return result


def world_entries(loaded: Loaded) -> dict[str, bytes]:
    return {f"{SERVER_SAVE_ROOT}/{name}": blob for name, blob in loaded.world_tree.items()}


def server_entries(loaded: Loaded, notice: bytes) -> dict[str, bytes]:
    result = world_entries(loaded)
    result.update({
        "server.properties": server_properties(),
        SERVER_POMKOTS_CONFIG_PATH: pomkots_safety_config(),
        SERVER_LOST_CITIES_PROFILE_PATH: loaded.asset_profile,
        NOTICE_NAME: notice,
        f"mods/{MP25_NAME}": loaded.mp25, f"mods/{LOST_CITIES_NAME}": loaded.lost,
        f"mods/{ASSET_NAME}": loaded.asset,
    })
    result.update({f"mods/{PurePosixPath(DEPENDENCY_PATHS[key]).name}": blob for key, blob in loaded.dependencies.items()})
    install_license_bundle(result, loaded.license_blobs, False)
    boundary(set(result), False)
    expected_mods = {f"mods/{MP25_NAME}", f"mods/{LOST_CITIES_NAME}", f"mods/{ASSET_NAME}",
                     *(f"mods/{PurePosixPath(path).name}" for path in DEPENDENCY_PATHS.values())}
    require({name for name in result if name.startswith("mods/")} == expected_mods, "expected server mod set mismatch")
    require(sum("cloth-config" in name.casefold() for name in expected_mods) == 1, "expected server has duplicate Cloth Config")
    require(result[SERVER_LOST_CITIES_PROFILE_PATH] == loaded.asset_profile,
            "expected server lost the exact embedded Lost Cities profile")
    return result


def record(name: str, blob: bytes) -> dict[str, object]:
    return {"file": name, "bytes": len(blob), "sha256": sha256_bytes(blob)}


def candidate_tree_sha256(files: dict[str, bytes]) -> str:
    digest = hashlib.sha256()
    digest.update(CANDIDATE_TREE_DOMAIN)
    for name in sorted(files):
        blob = files[name]
        digest.update(name.encode("utf-8"))
        digest.update(b"\0")
        digest.update(str(len(blob)).encode("ascii"))
        digest.update(b"\0")
        digest.update(sha256_bytes(blob).encode("ascii"))
        digest.update(b"\n")
    return digest.hexdigest().upper()


def runbook(records: dict[str, dict[str, object]]) -> bytes:
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


def rollback() -> bytes:
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


def live_validation() -> bytes:
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


def manifest(inputs: Inputs, loaded: Loaded, payloads: dict[str, bytes],
             archive_entry_names: dict[str, list[str]]) -> bytes:
    builder_blob = read_committed_builder_blob(inputs.source_commit)
    value = {
        "schema_version": 1, "candidate_id": CANDIDATE_ID,
        "status": "offline-candidate-not-live-qualified",
        "identity": {"version_id": VERSION_ID, "display_name": DISPLAY_NAME, "minecraft": "1.20.1",
                     "forge": "47.3.3", "map_id": "cold_ruin_sector_01",
                     "mission_id": "operation_ashen_span", "source_commit": inputs.source_commit.lower(),
                     "runtime_source_commit": RC6_RUNTIME_SOURCE_COMMIT},
        "lineage": {"predecessor_candidate_id": RC5_CANDIDATE_ID,
                    "production_jar_changed": True,
                    "authored_world_changed": False,
                    "mission_content_roster_tactics_balance_changed": False,
                    "solo_start_player_pad_collision_fixed": True,
                    "packaging_bytes_changed": True, "packaging_hardening_only": False,
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
                    }},
        "artifacts": {name: record(name, blob) for name, blob in sorted(payloads.items())},
        "inputs": {
            "sealed_mp24_mrpack": record(inputs.base_mrpack.name, loaded.base_blob),
            "mp25_jar": record(MP25_NAME, loaded.mp25), "lost_cities": record(LOST_CITIES_NAME, loaded.lost),
            "asset": record(ASSET_NAME, loaded.asset),
            "asset_lost_cities_profile": record(ASSET_PROFILE_MEMBER, loaded.asset_profile),
            "asset_receipt": record(inputs.asset_receipt.name, loaded.asset_receipt),
            "world_builder_archive": record(inputs.world_archive.name, loaded.world_input),
            "world_receipt": record(inputs.world_receipt.name, loaded.world_receipt),
            "world_tree_sha256": tree_hash(loaded.world_tree),
            "server_dependencies": {key: record(PurePosixPath(DEPENDENCY_PATHS[key]).name, blob)
                                    for key, blob in sorted(loaded.dependencies.items())},
        },
        "client_contract": {"save_roots": ["saves/cold_ruin_sector_01"], "view_distance": 6,
                            "simulation_distance": 6, "cloth_config_inputs": 1,
                            "entity_block_destruction": False,
                            "player_vehicle_block_destruction": False,
                            "embedded_mods": [MP25_NAME, LOST_CITIES_NAME, ASSET_NAME],
                            "embedded_license_paths": sorted(expected_license_paths(True)),
                            "qualification_payload_present": False,
                            "lost_cities_profile": {
                                "archive_path": CLIENT_LOST_CITIES_PROFILE_PATH,
                                "runtime_path": LOST_CITIES_PROFILE_RUNTIME_PATH,
                                "bytes": len(loaded.asset_profile),
                                "sha256": sha256_bytes(loaded.asset_profile),
                            }},
        "server_contract": {"level_name": "saves/cold_ruin_sector_01", "view_distance": 6,
                            "simulation_distance": 6, "cloth_config_jars": 1,
                            "natural_spawning": False,
                            "entity_block_destruction": False,
                            "player_vehicle_block_destruction": False,
                            "embedded_license_paths": sorted(expected_license_paths(False)),
                            "qualification_payload_present": False,
                            "lost_cities_profile": {
                                "archive_path": SERVER_LOST_CITIES_PROFILE_PATH,
                                "runtime_path": LOST_CITIES_PROFILE_RUNTIME_PATH,
                                "bytes": len(loaded.asset_profile),
                                "sha256": sha256_bytes(loaded.asset_profile),
                            }},
        "content_boundary": {"world_count": 1, "contains_mosslorn": False,
                             "contains_downloaded_city": False,
                             "client_lost_cities_profile_count": 1,
                             "server_lost_cities_profile_count": 1,
                             "compressed_world_budget_bytes": MAX_WORLD_BYTES,
                             "world_builder_archive_bytes": len(loaded.world_input)},
        "archive_contract": {"compression": "ZIP_DEFLATED-9", "timestamp": "1980-01-01T00:00:00Z",
                             "unix_mode": "0644", "entries": archive_entry_names},
        "distribution_hardening": {
            "embedded_license_bundle": {
                key: record(LICENSE_FILENAMES[key], blob)
                for key, blob in sorted(loaded.license_blobs.items())
            },
            "client_license_root": CLIENT_LICENSE_ROOT,
            "server_license_root": SERVER_LICENSE_ROOT,
            "qualification_probe_shipped": False,
            "public_platform_permission_review": "manual-pending",
            "fully_offline_installer": False,
        },
        "evidence_status": {"measured_offline": ["input hashes and JAR metadata",
                            "independent archive reconstruction", "one bounded save and exact server overlay",
                            "exact asset-embedded Lost Cities profile on client and server",
                            "6/6 package settings", "canonical two-build byte equality"],
                            "awaiting_live_validation": True, "live_validation_file": LIVE_VALIDATION_NAME},
        "builder": {"file": "tools/build_ashen_span_mp25.py", "bytes": len(builder_blob),
                    "sha256": sha256_bytes(builder_blob)},
    }
    return canonical_json(value)


def receipt(inputs: Inputs, loaded: Loaded, files: dict[str, bytes]) -> bytes:
    return canonical_json({
        "schema_version": 1, "builder": "operation-ashen-span-packager/1.1.0",
        "candidate_id": CANDIDATE_ID, "status": "offline-only-not-live-qualified",
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
        "fixed_inputs": {"base_mp24_sha256": sha256_bytes(loaded.base_blob), "mp25_sha256": sha256_bytes(loaded.mp25),
                         "lost_cities_sha256": sha256_bytes(loaded.lost), "asset_sha256": sha256_bytes(loaded.asset),
                          "asset_lost_cities_profile_sha256": sha256_bytes(loaded.asset_profile),
                          "world_builder_archive_sha256": sha256_bytes(loaded.world_input),
                          "world_tree_sha256": tree_hash(loaded.world_tree),
                          "embedded_license_sha256": {
                              key: sha256_bytes(blob) for key, blob in sorted(loaded.license_blobs.items())
                          }},
        "offline_gates": {"exclusive_new_staging": True, "two_builds_byte_equal": True,
                          "one_save": "cold_ruin_sector_01", "client_view_distance": 6,
                          "client_simulation_distance": 6, "server_view_distance": 6,
                          "server_simulation_distance": 6, "cloth_config_inputs": 1,
                          "entity_block_destruction": False,
                          "player_vehicle_block_destruction": False,
                          "client_lost_cities_profile": CLIENT_LOST_CITIES_PROFILE_PATH,
                          "server_lost_cities_profile": SERVER_LOST_CITIES_PROFILE_PATH,
                          "lost_cities_profile_byte_identical": True,
                          "mosslorn_present": False, "downloaded_city_present": False,
                          "world_within_32_mib": len(loaded.world_input) <= MAX_WORLD_BYTES,
                          "embedded_license_bundle_complete": True,
                          "qualification_probe_shipped": False,
                          "production_jar_changed_from_rc5": True,
                          "solo_start_player_pad_collision_fixed": True,
                          "authored_world_changed_from_rc5": False,
                          "mission_content_roster_tactics_balance_changed_from_rc5": False,
                          "package_bytes_changed_from_rc5": True},
        "outputs": {name: record(name, blob) for name, blob in sorted(files.items())},
        "deferred": ["interactive profile import", "10–13 minute gameplay and balance", "FPS and compatibility",
                     "live retry/restore observation", "soak"],
    })


def hashes(files: dict[str, bytes]) -> bytes:
    return canonical_text("\n".join(f"{sha256_bytes(files[name])} *{name}" for name in sorted(files)))


def reconstruct(inputs: Inputs, loaded: Loaded) -> tuple[dict[str, bytes], dict[str, dict[str, bytes]]]:
    notice = notices(loaded)
    ce = client_entries(loaded, notice); se = server_entries(loaded, notice); we = world_entries(loaded)
    mrpack = make_zip(ce); server = make_zip(se); world = make_zip(we)
    require(len(world) <= MAX_WORLD_BYTES, "expected world archive exceeds 32 MiB")
    require(sha256_bytes(world) == RC5_PACKAGED_WORLD_SHA256,
            "expected packaged world differs from immutable RC5")
    files = {MRPACK_NAME: mrpack, SERVER_NAME: server, WORLD_NAME: world, MP25_NAME: loaded.mp25, NOTICE_NAME: notice}
    recs = {"mrpack": record(MRPACK_NAME, mrpack), "server": record(SERVER_NAME, server),
            "world": record(WORLD_NAME, world), "mp25": record(MP25_NAME, loaded.mp25)}
    files[RUNBOOK_NAME] = runbook(recs); files[ROLLBACK_NAME] = rollback(); files[LIVE_VALIDATION_NAME] = live_validation()
    entry_names = {MRPACK_NAME: sorted(ce), SERVER_NAME: sorted(se), WORLD_NAME: sorted(we)}
    files[MANIFEST_NAME] = manifest(inputs, loaded, files, entry_names)
    files[RECEIPT_NAME] = receipt(inputs, loaded, files)
    files[HASHES_NAME] = hashes(files)
    return files, {MRPACK_NAME: ce, SERVER_NAME: se, WORLD_NAME: we}


def inspect_actual_archives(actual: dict[str, bytes], expected_entries: dict[str, dict[str, bytes]], loaded: Loaded) -> None:
    client = read_zip(actual[MRPACK_NAME], "candidate MRPack", canonical=True, compression=zipfile.ZIP_DEFLATED)
    server = read_zip(actual[SERVER_NAME], "candidate server overlay", canonical=True, compression=zipfile.ZIP_DEFLATED)
    world = read_zip(actual[WORLD_NAME], "candidate world archive", canonical=True, compression=zipfile.ZIP_DEFLATED)
    require(client == expected_entries[MRPACK_NAME] and server == expected_entries[SERVER_NAME]
            and world == expected_entries[WORLD_NAME], "actual archive entry bytes differ from independent reconstruction")
    boundary(set(client), True); boundary(set(server), False)
    require(client[CLIENT_MOD_PATHS["mp25"]] == actual[MP25_NAME] == loaded.mp25,
            "client, sidecar, and input mp.25 JAR are not byte-identical")
    require(client[CLIENT_MOD_PATHS["lost_cities"]] == server[f"mods/{LOST_CITIES_NAME}"] == loaded.lost,
            "client/server Lost Cities bytes differ")
    require(client[CLIENT_MOD_PATHS["asset"]] == server[f"mods/{ASSET_NAME}"] == loaded.asset,
            "client/server asset bytes differ")
    require(client.get(CLIENT_LOST_CITIES_PROFILE_PATH)
            == server.get(SERVER_LOST_CITIES_PROFILE_PATH)
            == loaded.asset_profile,
            "client/server Lost Cities profile differs from the exact embedded asset member")
    expected_safety_config = pomkots_safety_config()
    require(client.get(CLIENT_POMKOTS_CONFIG_PATH) == expected_safety_config,
            "actual client does not pin both Pomkots block-destruction switches false")
    require(server.get(SERVER_POMKOTS_CONFIG_PATH) == expected_safety_config,
            "actual server does not pin both Pomkots block-destruction switches false")
    options = client[OPTIONS_PATH].decode("utf-8")
    require(options.count("renderDistance:6\n") == options.count("simulationDistance:6\n") == 1,
            "actual client options are not exact 6/6")
    properties = server["server.properties"].decode("utf-8")
    for line in ("view-distance=6\n", "simulation-distance=6\n", "level-name=saves/cold_ruin_sector_01\n"):
        require(properties.count(line) == 1, f"actual server properties lost exact {line.strip()}")
    index = parse_index(client[INDEX_PATH], "actual client index")
    require(sum("cloth-config" in str(item.get("path", "")).casefold() for item in index["files"]) == 1,
            "actual client does not have exactly one Cloth Config input")
    require(sum("cloth-config" in name.casefold() for name in server if name.startswith("mods/")) == 1,
            "actual server does not have exactly one Cloth Config JAR")
    require(len(actual[WORLD_NAME]) <= MAX_WORLD_BYTES, "actual packaged world exceeds 32 MiB")


def verify_candidate(inputs: Inputs, candidate_dir: Path) -> dict[str, object]:
    require(candidate_dir.is_dir() and not is_link_or_reparse(candidate_dir), f"candidate directory missing/unsafe: {candidate_dir}")
    children = list(candidate_dir.iterdir())
    require(all(path.is_file() and not is_link_or_reparse(path) for path in children), "candidate directory contains a directory/link")
    require({path.name for path in children} == OUTPUT_NAMES, "candidate directory has missing or extra files")
    actual = {path.name: read_stable(path, f"candidate file {path.name}") for path in children}
    loaded = load(inputs)
    expected, expected_entries = reconstruct(inputs, loaded)
    require(set(expected) == OUTPUT_NAMES, "independent output contract internal mismatch")
    for name in sorted(expected):
        require(actual[name] == expected[name], f"candidate file differs from independent reconstruction: {name}")
    inspect_actual_archives(actual, expected_entries, loaded)

    manifest_value = parse_json(actual[MANIFEST_NAME], "actual manifest")
    receipt_value = parse_json(actual[RECEIPT_NAME], "actual offline receipt")
    require(manifest_value["status"] == "offline-candidate-not-live-qualified", "manifest overclaims live status")
    require(receipt_value["status"] == "offline-only-not-live-qualified", "receipt overclaims live status")
    profile_record = record(ASSET_PROFILE_MEMBER, loaded.asset_profile)
    require(manifest_value["inputs"]["asset_lost_cities_profile"] == profile_record,
            "manifest does not bind the exact embedded Lost Cities profile")
    require(manifest_value["client_contract"]["lost_cities_profile"]["archive_path"]
            == CLIENT_LOST_CITIES_PROFILE_PATH
            and manifest_value["server_contract"]["lost_cities_profile"]["archive_path"]
            == SERVER_LOST_CITIES_PROFILE_PATH,
            "manifest Lost Cities profile destinations mismatch")
    require(receipt_value["fixed_inputs"]["asset_lost_cities_profile_sha256"]
            == ASSET_PROFILE_SHA256,
            "receipt does not bind the exact embedded Lost Cities profile")
    expected_hash_lines = hashes({name: blob for name, blob in actual.items() if name != HASHES_NAME})
    require(actual[HASHES_NAME] == expected_hash_lines, "SHA256SUMS does not match actual candidate files")
    return {
        "candidate_id": CANDIDATE_ID,
        "candidate_tree_sha256": candidate_tree_sha256(actual),
        "mrpack": record(MRPACK_NAME, actual[MRPACK_NAME]),
        "server": record(SERVER_NAME, actual[SERVER_NAME]),
        "world": record(WORLD_NAME, actual[WORLD_NAME]),
        "files": len(actual),
        "cloth_config_inputs": 1,
        "live_qualified": False,
    }


def add_inputs(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--base-mrpack", type=Path, required=True)
    parser.add_argument("--mp25-jar", type=Path, required=True); parser.add_argument("--mp25-sha256", required=True)
    parser.add_argument("--lost-cities-jar", type=Path, required=True)
    parser.add_argument("--asset-jar", type=Path, required=True); parser.add_argument("--asset-sha256", required=True)
    parser.add_argument("--asset-receipt", type=Path, required=True)
    parser.add_argument("--world-dir", type=Path, required=True); parser.add_argument("--world-archive", type=Path, required=True)
    parser.add_argument("--world-sha256", required=True); parser.add_argument("--world-receipt", type=Path, required=True)
    parser.add_argument("--architectury-jar", type=Path, required=True)
    parser.add_argument("--cloth-config-jar", type=Path, required=True)
    parser.add_argument("--geckolib-jar", type=Path, required=True)
    parser.add_argument("--source-commit", required=True)


def from_args(args: argparse.Namespace) -> Inputs:
    return Inputs(args.base_mrpack, args.mp25_jar, args.mp25_sha256, args.lost_cities_jar,
                  args.asset_jar, args.asset_sha256, args.asset_receipt, args.world_dir,
                  args.world_archive, args.world_sha256, args.world_receipt,
                  args.architectury_jar, args.cloth_config_jar, args.geckolib_jar, args.source_commit)


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__); add_inputs(parser)
    parser.add_argument("--candidate-dir", type=Path, required=True)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    try:
        result = verify_candidate(from_args(args), args.candidate_dir)
        print(f"PASS {result['candidate_id']}: {result['mrpack']['sha256']} | "
              f"server {result['server']['sha256']} | world {result['world']['sha256']} | "
              f"candidate tree {result['candidate_tree_sha256']} | "
              "independent reconstruction, offline-only")
        return 0
    except (VerifyError, OSError, UnicodeError, json.JSONDecodeError,
            tomllib.TOMLDecodeError, zipfile.BadZipFile) as exc:
        print(f"FAIL Operation Ashen Span mp.25 candidate: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
