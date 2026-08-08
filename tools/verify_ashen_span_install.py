"""Materialize and audit an Operation Ashen Span MRPack without launching Minecraft.

The auditor is intentionally narrower than a general Modrinth installer. It accepts
only a hash-bound Operation Ashen Span offline candidate, writes into a new profile,
downloads the nine indexed client dependencies over constrained HTTPS, and emits a
canonical pass/fail receipt. It never reads launcher state or starts Java.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import sys
import tempfile
import tomllib
from typing import BinaryIO, Callable, Iterable, Mapping, Protocol
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
import zipfile


AUDITOR_ID = "operation-ashen-span-install-auditor/1.1.0"
AUDIT_RECEIPT_SCHEMA = 2
INDEX_NAME = "modrinth.index.json"
MANIFEST_NAME = "MANIFEST.json"
OFFLINE_RECEIPT_NAME = "OFFLINE_BUILD_RECEIPT.json"
EXPECTED_INDEXED_FILES = 9
EXPECTED_GAME = "minecraft"
EXPECTED_MINECRAFT = "1.20.1"
EXPECTED_FORGE = "47.3.3"
EXPECTED_MAP = "cold_ruin_sector_01"
EXPECTED_MISSION = "operation_ashen_span"
ALLOWED_DOWNLOAD_HOSTS = frozenset({"cdn.modrinth.com"})
DOWNLOAD_CHUNK_BYTES = 128 * 1024
MAX_REDIRECTS = 5
MOD_ID = re.compile(r"[a-z][a-z0-9_]{1,63}\Z")
RC_ID = re.compile(r"operation-ashen-span-mp25-rc([1-9][0-9]*)\Z")
VERSION_ID = re.compile(r"0\.8\.0-operation-ashen-span-mp25-rc([1-9][0-9]*)\Z")
HEX_40 = re.compile(r"[0-9a-f]{40}\Z")
HEX_64 = re.compile(r"[0-9A-F]{64}\Z")
HEX_LENGTHS = {"sha1": 40, "sha512": 128, "sha256": 64}
WINDOWS_RESERVED = {
    "con", "prn", "aux", "nul", *(f"com{i}" for i in range(1, 10)),
    *(f"lpt{i}" for i in range(1, 10)),
}
CANDIDATE_TREE_DOMAIN = b"operation-ashen-span-candidate-tree-v1\0"
RC5_CANDIDATE_ID = "operation-ashen-span-mp25-rc5"
RC5_SOURCE_COMMIT = "d96b7b84688e925f311849d7c40f72a4f8a691c2"
RC5_CANDIDATE_TREE_SHA256 = "1E284A0555A421E5084B2A9425B70AA573C2ADBB382E0CCA8A6A3E8B012B38DB"
COMMON_CANDIDATE_FILES = frozenset({
    "ASHEN_SPAN_LIVE_VALIDATION.md",
    MANIFEST_NAME,
    OFFLINE_RECEIPT_NAME,
    "ROLLBACK.md",
    "RUNBOOK.md",
    "SHA256SUMS.txt",
    "THIRD_PARTY_NOTICES.md",
    "cold_ruin_sector_01-mp25-world.zip",
    "pomkotsmechs-forge-0.0.1-alpha.7-mp.25.jar",
})


class AuditError(RuntimeError):
    """A fail-closed validation or materialization error."""


class Response(Protocol):
    status: int
    headers: Mapping[str, str]

    def geturl(self) -> str: ...
    def read(self, size: int = -1) -> bytes: ...
    def __enter__(self) -> "Response": ...
    def __exit__(self, exc_type: object, exc: object, traceback: object) -> object: ...


class Opener(Protocol):
    def open(self, url: str, timeout: float) -> Response: ...


@dataclass(frozen=True)
class Limits:
    max_mrpack_bytes: int = 128 * 1024 * 1024
    max_index_bytes: int = 2 * 1024 * 1024
    max_archive_members: int = 10_000
    max_override_file_bytes: int = 128 * 1024 * 1024
    max_override_total_bytes: int = 512 * 1024 * 1024
    max_indexed_file_bytes: int = 64 * 1024 * 1024
    max_indexed_total_bytes: int = 256 * 1024 * 1024
    max_metadata_bytes: int = 2 * 1024 * 1024
    max_candidate_file_bytes: int = 512 * 1024 * 1024
    max_candidate_total_bytes: int = 1024 * 1024 * 1024
    timeout_seconds: float = 30.0


@dataclass(frozen=True)
class Candidate:
    directory: Path
    candidate_id: str
    source_commit: str
    version_id: str
    display_name: str
    mrpack_name: str
    mrpack_blob: bytes
    mrpack_sha256: str
    manifest_sha256: str
    offline_receipt_sha256: str
    embedded_mods: tuple[str, ...]


@dataclass(frozen=True)
class ExpectedCandidateBinding:
    """A trust anchor supplied independently of the candidate directory."""

    authority: str
    candidate_id: str
    source_commit: str
    candidate_tree_sha256: str
    inventory: tuple[str, ...]


@dataclass(frozen=True)
class CandidateFileFact:
    file: str
    bytes: int
    sha256: str


@dataclass(frozen=True)
class CandidateBindingEvidence:
    expected: ExpectedCandidateBinding
    mode: str
    measured_tree_sha256: str
    measured_inventory: tuple[CandidateFileFact, ...]


@dataclass(frozen=True)
class IndexedFile:
    path: str
    size: int
    sha1: str
    sha512: str
    downloads: tuple[str, ...]


@dataclass(frozen=True)
class OverrideFile:
    archive_name: str
    path: str
    size: int


@dataclass(frozen=True)
class MaterializationPlan:
    index: dict[str, object]
    indexed: tuple[IndexedFile, ...]
    overrides: tuple[OverrideFile, ...]
    output_paths: tuple[str, ...]


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AuditError(message)


def canonical_json(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8")


def sha_bytes(blob: bytes, algorithm: str = "sha256") -> str:
    return hashlib.new(algorithm, blob).hexdigest()


def utc_now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def is_reparse(path: Path) -> bool:
    try:
        value = path.lstat()
    except FileNotFoundError:
        return False
    return path.is_symlink() or bool(
        getattr(value, "st_file_attributes", 0)
        & getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400)
    )


def read_stable(path: Path, label: str, max_bytes: int) -> bytes:
    require(path.is_file() and not is_reparse(path), f"{label} is missing, not a file, or a link/reparse point")
    before = path.stat()
    require(before.st_size <= max_bytes, f"{label} exceeds the {max_bytes}-byte bound")
    blob = path.read_bytes()
    after = path.stat()
    require(
        before.st_size == after.st_size == len(blob)
        and before.st_mtime_ns == after.st_mtime_ns
        and getattr(before, "st_ino", None) == getattr(after, "st_ino", None),
        f"{label} changed while it was read",
    )
    return blob


def parse_json_object(blob: bytes, label: str) -> dict[str, object]:
    try:
        value = json.loads(blob)
    except (UnicodeError, json.JSONDecodeError) as exc:
        raise AuditError(f"{label} is not valid UTF-8 JSON: {exc}") from exc
    require(isinstance(value, dict), f"{label} must be a JSON object")
    return value


def checked_hex(value: object, algorithm: str, label: str) -> str:
    require(isinstance(value, str), f"{label} must be a {algorithm} string")
    lowered = value.lower()
    require(
        len(lowered) == HEX_LENGTHS[algorithm]
        and all(character in "0123456789abcdef" for character in lowered),
        f"{label} is not a valid {algorithm}",
    )
    return lowered


def checked_record(value: object, filename: str, label: str) -> tuple[int, str]:
    require(isinstance(value, dict), f"{label} record is missing")
    require(value.get("file") == filename, f"{label} filename does not match {filename}")
    size = value.get("bytes")
    require(isinstance(size, int) and not isinstance(size, bool) and size >= 0, f"{label} byte count is invalid")
    return size, checked_hex(value.get("sha256"), "sha256", f"{label} SHA-256")


def normalized_path(raw: object, label: str) -> str:
    require(isinstance(raw, str) and raw, f"{label} must be a nonempty path string")
    require("\\" not in raw and "\x00" not in raw, f"{label} contains a forbidden separator or NUL")
    require(not raw.startswith("/") and not raw.endswith("/"), f"{label} must be a normalized relative file path")
    require(unicodedata.normalize("NFC", raw) == raw, f"{label} is not Unicode NFC-normalized")
    parts = PurePosixPath(raw).parts
    require(parts and all(part not in ("", ".", "..") for part in parts), f"{label} contains traversal or empty segments")
    for part in parts:
        require(not part.endswith((".", " ")), f"{label} has a Windows-ambiguous segment")
        require(not any(ord(character) < 32 or character in '<>:"|?*' for character in part),
                f"{label} has a forbidden filename character")
        stem = part.split(".", 1)[0].casefold()
        require(stem not in WINDOWS_RESERVED, f"{label} uses reserved Windows name {part}")
    normalized = PurePosixPath(*parts).as_posix()
    require(normalized == raw, f"{label} is not normalized")
    return normalized


def locked_candidate_inventory(candidate_id: str) -> tuple[str, ...]:
    match = RC_ID.fullmatch(candidate_id)
    require(match is not None, "candidate binding identity is not an Operation Ashen Span mp.25 RC")
    rc_number = match.group(1)
    dynamic = {
        f"mech-arena-0.8.0-operation-ashen-span-mp25-rc{rc_number}.mrpack",
        f"mech-arena-operation-ashen-span-mp25-rc{rc_number}-server-overlay.zip",
    }
    return tuple(sorted(COMMON_CANDIDATE_FILES | dynamic))


def parse_expected_candidate_binding(value: object, label: str = "candidate binding") -> ExpectedCandidateBinding:
    require(isinstance(value, dict), f"{label} must be a JSON object")
    expected_keys = {
        "authority", "candidate_id", "candidate_tree_sha256", "inventory", "schema_version", "source_commit",
    }
    require(set(value) == expected_keys, f"{label} fields differ from the exact schema")
    require(value.get("schema_version") == 1, f"{label} schema_version is not supported")
    authority = value.get("authority")
    candidate_id = value.get("candidate_id")
    source_commit = value.get("source_commit")
    tree_sha = value.get("candidate_tree_sha256")
    inventory = value.get("inventory")
    require(
        isinstance(authority, str) and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}", authority) is not None,
        f"{label} authority is invalid",
    )
    require(isinstance(candidate_id, str) and RC_ID.fullmatch(candidate_id) is not None,
            f"{label} candidate_id is invalid")
    require(isinstance(source_commit, str) and HEX_40.fullmatch(source_commit) is not None,
            f"{label} source_commit must be 40 lowercase hexadecimal characters")
    require(isinstance(tree_sha, str) and HEX_64.fullmatch(tree_sha.upper()) is not None,
            f"{label} candidate_tree_sha256 must be 64 hexadecimal characters")
    require(isinstance(inventory, list) and all(isinstance(item, str) for item in inventory),
            f"{label} inventory must be a list of filenames")
    checked_inventory = tuple(sorted(normalized_path(item, f"{label} inventory filename") for item in inventory))
    require(all("/" not in item for item in checked_inventory), f"{label} inventory must contain top-level files")
    require(len({item.casefold() for item in checked_inventory}) == len(checked_inventory),
            f"{label} inventory contains duplicate or case-colliding filenames")
    locked_inventory = locked_candidate_inventory(candidate_id)
    require(checked_inventory == locked_inventory,
            f"{label} inventory differs from the exact 11-file Ashen Span RC inventory")
    return ExpectedCandidateBinding(
        authority=authority,
        candidate_id=candidate_id,
        source_commit=source_commit,
        candidate_tree_sha256=tree_sha.upper(),
        inventory=checked_inventory,
    )


def load_expected_candidate_binding(path: Path, limits: Limits = Limits()) -> ExpectedCandidateBinding:
    blob = read_stable(path, "external candidate binding", limits.max_index_bytes)
    return parse_expected_candidate_binding(parse_json_object(blob, "external candidate binding"),
                                            "external candidate binding")


def validate_expected_candidate_binding(binding: ExpectedCandidateBinding) -> ExpectedCandidateBinding:
    return parse_expected_candidate_binding(candidate_binding_document(binding))


def candidate_binding_document(binding: ExpectedCandidateBinding) -> dict[str, object]:
    return {
        "authority": binding.authority,
        "candidate_id": binding.candidate_id,
        "candidate_tree_sha256": binding.candidate_tree_sha256,
        "inventory": list(binding.inventory),
        "schema_version": 1,
        "source_commit": binding.source_commit,
    }


def immutable_rc5_binding() -> ExpectedCandidateBinding:
    return ExpectedCandidateBinding(
        authority="auditor-builtin-immutable-rc5",
        candidate_id=RC5_CANDIDATE_ID,
        source_commit=RC5_SOURCE_COMMIT,
        candidate_tree_sha256=RC5_CANDIDATE_TREE_SHA256,
        inventory=locked_candidate_inventory(RC5_CANDIDATE_ID),
    )


def sha_file_stable(path: Path, label: str, max_bytes: int) -> tuple[int, str]:
    require(path.is_file() and not is_reparse(path), f"{label} is missing, not a file, or a link/reparse point")
    before = path.stat()
    require(before.st_size <= max_bytes, f"{label} exceeds the {max_bytes}-byte bound")
    digest = hashlib.sha256()
    read_bytes = 0
    with path.open("rb") as source:
        while True:
            block = source.read(1024 * 1024)
            if not block:
                break
            read_bytes += len(block)
            require(read_bytes <= max_bytes, f"{label} exceeds the {max_bytes}-byte bound")
            digest.update(block)
    after = path.stat()
    require(
        before.st_size == after.st_size == read_bytes
        and before.st_mtime_ns == after.st_mtime_ns
        and getattr(before, "st_ino", None) == getattr(after, "st_ino", None),
        f"{label} changed while it was hashed",
    )
    return read_bytes, digest.hexdigest().upper()


def candidate_tree_sha256(inventory: Iterable[CandidateFileFact]) -> str:
    digest = hashlib.sha256()
    digest.update(CANDIDATE_TREE_DOMAIN)
    for fact in sorted(inventory, key=lambda item: item.file):
        digest.update(fact.file.encode("utf-8"))
        digest.update(b"\0")
        digest.update(str(fact.bytes).encode("ascii"))
        digest.update(b"\0")
        digest.update(fact.sha256.encode("ascii"))
        digest.update(b"\n")
    return digest.hexdigest().upper()


def measure_candidate_tree(directory: Path, expected_inventory: tuple[str, ...],
                           limits: Limits) -> tuple[str, tuple[CandidateFileFact, ...]]:
    require(directory.is_dir() and not is_reparse(directory), "candidate directory is missing or unsafe")
    children = sorted(directory.iterdir(), key=lambda item: item.name)
    require(all(child.is_file() and not is_reparse(child) for child in children),
            "candidate root must contain regular files only")
    actual_names = tuple(child.name for child in children)
    require(actual_names == expected_inventory,
            "candidate directory inventory differs from the externally bound exact 11-file inventory")
    total_bytes = 0
    facts: list[CandidateFileFact] = []
    for child in children:
        size, digest = sha_file_stable(child, f"candidate member {child.name}", limits.max_candidate_file_bytes)
        total_bytes += size
        require(total_bytes <= limits.max_candidate_total_bytes, "candidate directory exceeds its total byte bound")
        facts.append(CandidateFileFact(child.name, size, digest))
    inventory = tuple(facts)
    return candidate_tree_sha256(inventory), inventory


def bind_candidate(candidate: Candidate, supplied: ExpectedCandidateBinding | None,
                   limits: Limits) -> CandidateBindingEvidence:
    if candidate.candidate_id == RC5_CANDIDATE_ID:
        expected = immutable_rc5_binding()
        mode = "builtin-immutable-rc5"
        if supplied is not None:
            require(supplied == expected, "external RC5 binding differs from the immutable auditor anchor")
    else:
        require(supplied is not None,
                "future Ashen Span candidates require an explicit external candidate binding")
        expected = validate_expected_candidate_binding(supplied)
        mode = "external-explicit"
    require(expected.candidate_id == candidate.candidate_id,
            "candidate identity differs from the external candidate binding")
    require(expected.source_commit == candidate.source_commit,
            "candidate source commit differs from the external candidate binding")
    measured_tree, measured_inventory = measure_candidate_tree(candidate.directory, expected.inventory, limits)
    require(measured_tree == expected.candidate_tree_sha256,
            "candidate tree differs from the external immutable candidate binding")
    measured_by_name = {fact.file: fact for fact in measured_inventory}
    loaded_hashes = {
        candidate.mrpack_name: candidate.mrpack_sha256.upper(),
        MANIFEST_NAME: candidate.manifest_sha256.upper(),
        OFFLINE_RECEIPT_NAME: candidate.offline_receipt_sha256.upper(),
    }
    require(
        all(measured_by_name[name].sha256 == digest for name, digest in loaded_hashes.items()),
        "candidate identity files changed between parsing and immutable tree binding",
    )
    return CandidateBindingEvidence(expected, mode, measured_tree, measured_inventory)


def candidate_binding_summary(binding: CandidateBindingEvidence) -> dict[str, object]:
    return {
        "authority": binding.expected.authority,
        "binding_canonical_sha256": sha_bytes(canonical_json(candidate_binding_document(binding.expected))).upper(),
        "candidate_id": binding.expected.candidate_id,
        "expected_candidate_tree_sha256": binding.expected.candidate_tree_sha256,
        "expected_inventory": list(binding.expected.inventory),
        "measured_candidate_tree_sha256": binding.measured_tree_sha256,
        "measured_inventory": [
            {"bytes": fact.bytes, "file": fact.file, "sha256": fact.sha256}
            for fact in binding.measured_inventory
        ],
        "mode": binding.mode,
        "source_commit": binding.expected.source_commit,
    }


class PathRegistry:
    def __init__(self) -> None:
        self._entries: dict[str, tuple[str, str]] = {}

    def add(self, path: str, origin: str) -> None:
        key = path.casefold()
        previous = self._entries.get(key)
        require(previous is None, f"output path collision: {path} ({origin}) conflicts with {previous}")
        for existing_key, existing in self._entries.items():
            require(
                not key.startswith(existing_key + "/") and not existing_key.startswith(key + "/"),
                f"file/directory path collision: {path} ({origin}) conflicts with {existing}",
            )
        self._entries[key] = (path, origin)


def candidate_summary(candidate: Candidate) -> dict[str, object]:
    return {
        "candidate_id": candidate.candidate_id,
        "display_name": candidate.display_name,
        "source_commit": candidate.source_commit,
        "version_id": candidate.version_id,
        "manifest": {"file": MANIFEST_NAME, "sha256": candidate.manifest_sha256},
        "offline_receipt": {"file": OFFLINE_RECEIPT_NAME, "sha256": candidate.offline_receipt_sha256},
        "mrpack": {
            "bytes": len(candidate.mrpack_blob),
            "file": candidate.mrpack_name,
            "sha256": candidate.mrpack_sha256,
        },
    }


def load_candidate(directory: Path, limits: Limits) -> Candidate:
    require(directory.is_dir() and not is_reparse(directory), "candidate directory is missing or unsafe")
    manifest_blob = read_stable(directory / MANIFEST_NAME, "candidate manifest", limits.max_index_bytes)
    receipt_blob = read_stable(directory / OFFLINE_RECEIPT_NAME, "candidate offline receipt", limits.max_index_bytes)
    manifest = parse_json_object(manifest_blob, "candidate manifest")
    receipt = parse_json_object(receipt_blob, "candidate offline receipt")

    candidate_id = manifest.get("candidate_id")
    version_identity = manifest.get("identity")
    require(isinstance(candidate_id, str), "candidate manifest lacks candidate_id")
    rc_match = RC_ID.fullmatch(candidate_id)
    require(rc_match is not None, "candidate_id is not an Operation Ashen Span mp.25 RC identity")
    require(isinstance(version_identity, dict), "candidate manifest identity is missing")
    version_id = version_identity.get("version_id")
    version_match = VERSION_ID.fullmatch(version_id) if isinstance(version_id, str) else None
    require(version_match is not None and version_match.group(1) == rc_match.group(1),
            "candidate and version RC identities disagree")
    require(version_identity.get("minecraft") == EXPECTED_MINECRAFT, "candidate Minecraft version is not locked 1.20.1")
    require(version_identity.get("forge") == EXPECTED_FORGE, "candidate Forge version is not locked 47.3.3")
    require(version_identity.get("map_id") == EXPECTED_MAP, "candidate map identity is not Cold Ruin Sector 01")
    require(version_identity.get("mission_id") == EXPECTED_MISSION, "candidate mission identity is not Operation Ashen Span")
    display_name = version_identity.get("display_name")
    source_commit = version_identity.get("source_commit")
    require(isinstance(display_name, str) and display_name, "candidate display name is missing")
    require(isinstance(source_commit, str) and re.fullmatch(r"[0-9a-fA-F]{40}", source_commit) is not None,
            "candidate source commit is invalid")
    require(manifest.get("status") == "offline-candidate-not-live-qualified", "candidate manifest overclaims live status")
    require(receipt.get("status") == "offline-only-not-live-qualified", "candidate receipt overclaims live status")
    require(receipt.get("candidate_id") == candidate_id, "candidate receipt identity disagrees with manifest")
    require(receipt.get("source_commit") == source_commit, "candidate receipt source commit disagrees with manifest")

    artifacts = manifest.get("artifacts")
    outputs = receipt.get("outputs")
    require(isinstance(artifacts, dict) and isinstance(outputs, dict), "candidate artifact records are missing")
    mrpack_names = sorted(name for name in artifacts if isinstance(name, str) and name.casefold().endswith(".mrpack"))
    require(len(mrpack_names) == 1, "candidate manifest must bind exactly one MRPack")
    mrpack_name = normalized_path(mrpack_names[0], "candidate MRPack filename")
    require("/" not in mrpack_name, "candidate MRPack must be a top-level file")
    manifest_size, manifest_mrpack_sha = checked_record(artifacts[mrpack_name], mrpack_name, "manifest MRPack")
    receipt_size, receipt_mrpack_sha = checked_record(outputs.get(mrpack_name), mrpack_name, "receipt MRPack")
    require((manifest_size, manifest_mrpack_sha) == (receipt_size, receipt_mrpack_sha),
            "manifest and receipt MRPack records disagree")
    mrpack_blob = read_stable(directory / mrpack_name, "candidate MRPack", limits.max_mrpack_bytes)
    actual_sha = sha_bytes(mrpack_blob)
    require(len(mrpack_blob) == manifest_size and actual_sha == manifest_mrpack_sha,
            "candidate MRPack bytes do not match manifest/receipt identity")

    client_contract = manifest.get("client_contract")
    require(isinstance(client_contract, dict), "candidate client contract is missing")
    embedded = client_contract.get("embedded_mods")
    require(isinstance(embedded, list) and embedded, "candidate embedded mod inventory is missing")
    embedded_mods: list[str] = []
    seen_embedded: set[str] = set()
    for value in embedded:
        name = normalized_path(value, "embedded mod filename")
        require("/" not in name and name.casefold().endswith(".jar"), "embedded mod must be a JAR basename")
        require(name.casefold() not in seen_embedded, "candidate embedded mod filenames collide by case")
        seen_embedded.add(name.casefold())
        embedded_mods.append(name)

    return Candidate(
        directory=directory,
        candidate_id=candidate_id,
        source_commit=source_commit.lower(),
        version_id=version_id,
        display_name=display_name,
        mrpack_name=mrpack_name,
        mrpack_blob=mrpack_blob,
        mrpack_sha256=actual_sha,
        manifest_sha256=sha_bytes(manifest_blob),
        offline_receipt_sha256=sha_bytes(receipt_blob),
        embedded_mods=tuple(sorted(embedded_mods, key=str.casefold)),
    )


def validate_download_url(raw: object, label: str = "download URL") -> str:
    require(isinstance(raw, str) and raw and not any(ord(character) < 32 for character in raw),
            f"{label} is invalid")
    parsed = urllib.parse.urlsplit(raw)
    require(parsed.scheme == "https", f"{label} must use HTTPS")
    require(parsed.username is None and parsed.password is None, f"{label} must not contain credentials")
    require(parsed.hostname is not None and parsed.hostname.casefold() in ALLOWED_DOWNLOAD_HOSTS,
            f"{label} host is not approved")
    try:
        port = parsed.port
    except ValueError as exc:
        raise AuditError(f"{label} has an invalid port") from exc
    require(port in (None, 443), f"{label} must use the default HTTPS port")
    require(not parsed.fragment, f"{label} must not contain a fragment")
    require(bool(parsed.path), f"{label} must contain a path")
    return raw


class HttpsOnlyRedirectHandler(urllib.request.HTTPRedirectHandler):
    max_redirections = MAX_REDIRECTS
    max_repeats = 2

    def redirect_request(self, req: urllib.request.Request, fp: BinaryIO, code: int, msg: str,
                         headers: Mapping[str, str], newurl: str) -> urllib.request.Request | None:
        absolute = urllib.parse.urljoin(req.full_url, newurl)
        validate_download_url(absolute, "redirect URL")
        return super().redirect_request(req, fp, code, msg, headers, absolute)


class StrictHttpsOpener:
    def __init__(self) -> None:
        self._opener = urllib.request.build_opener(HttpsOnlyRedirectHandler())

    def open(self, url: str, timeout: float) -> Response:
        request = urllib.request.Request(
            validate_download_url(url),
            headers={"Accept-Encoding": "identity", "User-Agent": AUDITOR_ID},
            method="GET",
        )
        return self._opener.open(request, timeout=timeout)  # type: ignore[return-value]


def parse_index(blob: bytes, candidate: Candidate, limits: Limits) -> tuple[dict[str, object], tuple[IndexedFile, ...]]:
    require(len(blob) <= limits.max_index_bytes, "MRPack index exceeds its size bound")
    index = parse_json_object(blob, "MRPack index")
    require(index.get("formatVersion") == 1 and index.get("game") == EXPECTED_GAME,
            "MRPack index format/game is not the locked Modrinth Minecraft contract")
    require(index.get("versionId") == candidate.version_id, "MRPack versionId disagrees with candidate manifest")
    require(index.get("name") == candidate.display_name, "MRPack display name disagrees with candidate manifest")
    dependencies = index.get("dependencies")
    require(isinstance(dependencies, dict), "MRPack dependencies are missing")
    require(dependencies.get("minecraft") == EXPECTED_MINECRAFT and dependencies.get("forge") == EXPECTED_FORGE,
            "MRPack Minecraft/Forge versions are not locked 1.20.1/47.3.3")
    raw_files = index.get("files")
    require(isinstance(raw_files, list) and len(raw_files) == EXPECTED_INDEXED_FILES,
            f"MRPack must contain exactly {EXPECTED_INDEXED_FILES} indexed dependencies")

    registry = PathRegistry()
    indexed: list[IndexedFile] = []
    total_size = 0
    for position, raw in enumerate(raw_files):
        label = f"MRPack files[{position}]"
        require(isinstance(raw, dict), f"{label} must be an object")
        path = normalized_path(raw.get("path"), f"{label}.path")
        require(path.startswith("mods/") and path.casefold().endswith(".jar"),
                f"{label}.path must be a JAR directly under mods/")
        require(len(PurePosixPath(path).parts) == 2, f"{label}.path must be directly under mods/")
        registry.add(path, f"indexed dependency {position}")
        size = raw.get("fileSize")
        require(isinstance(size, int) and not isinstance(size, bool) and 0 < size <= limits.max_indexed_file_bytes,
                f"{label}.fileSize is invalid or exceeds its bound")
        total_size += size
        require(total_size <= limits.max_indexed_total_bytes, "indexed dependency total exceeds its bound")
        hashes = raw.get("hashes")
        require(isinstance(hashes, dict), f"{label}.hashes is missing")
        sha1 = checked_hex(hashes.get("sha1"), "sha1", f"{label}.hashes.sha1")
        sha512 = checked_hex(hashes.get("sha512"), "sha512", f"{label}.hashes.sha512")
        env = raw.get("env")
        require(isinstance(env, dict) and env.get("client") == "required"
                and env.get("server") in {"required", "optional", "unsupported"},
                f"{label}.env is not a valid client profile contract")
        downloads = raw.get("downloads")
        require(isinstance(downloads, list) and 1 <= len(downloads) <= 5,
                f"{label}.downloads must contain one to five URLs")
        checked_downloads = tuple(validate_download_url(url, f"{label}.downloads") for url in downloads)
        require(len(set(checked_downloads)) == len(checked_downloads), f"{label}.downloads contains duplicates")
        indexed.append(IndexedFile(path, size, sha1, sha512, checked_downloads))
    return index, tuple(sorted(indexed, key=lambda item: item.path.casefold()))


def safe_zip_members(archive: zipfile.ZipFile, limits: Limits) -> list[zipfile.ZipInfo]:
    infos = archive.infolist()
    require(len(infos) <= limits.max_archive_members, "MRPack contains too many archive members")
    names: dict[str, str] = {}
    for info in infos:
        raw = info.filename[:-1] if info.is_dir() and info.filename.endswith("/") else info.filename
        name = normalized_path(raw, "MRPack archive member")
        key = name.casefold()
        require(key not in names, f"MRPack archive member collision: {name} conflicts with {names.get(key)}")
        names[key] = name
        require(not (info.flag_bits & 1), f"encrypted MRPack member is forbidden: {name}")
        if info.create_system == 3:
            mode = info.external_attr >> 16
            require(not stat.S_ISLNK(mode), f"symlink MRPack member is forbidden: {name}")
            require(mode == 0 or stat.S_ISREG(mode) or stat.S_ISDIR(mode),
                    f"special-file MRPack member is forbidden: {name}")
    return infos


def plan_materialization(candidate: Candidate, limits: Limits) -> MaterializationPlan:
    try:
        archive = zipfile.ZipFile(io.BytesIO(candidate.mrpack_blob))
    except zipfile.BadZipFile as exc:
        raise AuditError(f"candidate MRPack is not a valid ZIP: {exc}") from exc
    with archive:
        infos = safe_zip_members(archive, limits)
        index_infos = [info for info in infos if not info.is_dir() and info.filename == INDEX_NAME]
        require(len(index_infos) == 1, "MRPack must contain exactly one root modrinth.index.json")
        index_info = index_infos[0]
        require(index_info.file_size <= limits.max_index_bytes, "MRPack index exceeds its size bound")
        index_blob = archive.read(index_info)
        require(len(index_blob) == index_info.file_size, "MRPack index decompressed size mismatch")
        index, indexed = parse_index(index_blob, candidate, limits)

        output_registry = PathRegistry()
        for item in indexed:
            output_registry.add(item.path, "indexed dependency")
        overrides: list[OverrideFile] = []
        override_total = 0
        for info in infos:
            if info.is_dir() or info.filename == INDEX_NAME:
                continue
            parts = PurePosixPath(info.filename).parts
            require(parts and parts[0] in {"overrides", "client-overrides"},
                    f"unexpected MRPack top-level file: {info.filename}")
            require(len(parts) > 1, f"MRPack override has no destination: {info.filename}")
            output = normalized_path(PurePosixPath(*parts[1:]).as_posix(), "override destination")
            require(info.file_size <= limits.max_override_file_bytes,
                    f"override exceeds per-file bound: {output}")
            override_total += info.file_size
            require(override_total <= limits.max_override_total_bytes, "override expansion exceeds total bound")
            output_registry.add(output, f"archive member {info.filename}")
            overrides.append(OverrideFile(info.filename, output, info.file_size))

        actual_embedded = sorted(
            (
                PurePosixPath(item.path).name for item in overrides
                if PurePosixPath(item.path).parent.as_posix().casefold() == "mods"
                and item.path.casefold().endswith(".jar")
            ),
            key=str.casefold,
        )
        require(tuple(actual_embedded) == candidate.embedded_mods,
                "MRPack override mod JARs disagree with the manifest embedded_mods contract")
        output_paths = tuple(sorted(
            tuple(item.path for item in indexed) + tuple(item.path for item in overrides),
            key=str.casefold,
        ))
        return MaterializationPlan(index, indexed, tuple(sorted(overrides, key=lambda item: item.path.casefold())),
                                   output_paths)


def target_path(root: Path, relative: str) -> Path:
    parts = PurePosixPath(relative).parts
    target = root.joinpath(*parts)
    require(target != root and root in target.parents, f"output escaped profile root: {relative}")
    return target


def publish_stream(target: Path, writer: Callable[[BinaryIO], int]) -> int:
    target.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary_name = tempfile.mkstemp(prefix=".ashen-audit-", suffix=".partial", dir=target.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(fd, "wb") as output:
            written = writer(output)
            output.flush()
            os.fsync(output.fileno())
        require(not target.exists() and not is_reparse(target), f"exclusive output already exists: {target.name}")
        os.replace(temporary, target)
        return written
    except BaseException:
        try:
            temporary.unlink(missing_ok=True)
        except OSError:
            pass
        raise


def extract_overrides(candidate: Candidate, plan: MaterializationPlan, root: Path) -> None:
    with zipfile.ZipFile(io.BytesIO(candidate.mrpack_blob)) as archive:
        for item in plan.overrides:
            def write(output: BinaryIO, selected: OverrideFile = item) -> int:
                written = 0
                with archive.open(selected.archive_name, "r") as source:
                    while True:
                        chunk = source.read(DOWNLOAD_CHUNK_BYTES)
                        if not chunk:
                            break
                        written += len(chunk)
                        require(written <= selected.size, f"override expanded beyond indexed ZIP size: {selected.path}")
                        output.write(chunk)
                require(written == selected.size, f"override size mismatch: {selected.path}")
                return written
            publish_stream(target_path(root, item.path), write)


def response_status(response: Response) -> int:
    value = getattr(response, "status", None)
    if value is None and hasattr(response, "getcode"):
        value = response.getcode()  # type: ignore[attr-defined]
    require(isinstance(value, int), "download response did not provide an HTTP status")
    return value


def download_one(item: IndexedFile, root: Path, opener: Opener, limits: Limits) -> dict[str, object]:
    failures: list[str] = []
    for source_url in item.downloads:
        try:
            with opener.open(source_url, timeout=limits.timeout_seconds) as response:
                status_code = response_status(response)
                require(status_code == 200, f"HTTP status {status_code}")
                final_url = validate_download_url(response.geturl(), "final download URL")
                encoding = response.headers.get("Content-Encoding")
                require(encoding in (None, "", "identity"), "download response used content encoding")
                content_length = response.headers.get("Content-Length")
                if content_length is not None:
                    require(content_length.isdecimal() and int(content_length) == item.size,
                            "download Content-Length disagrees with MRPack index")
                digest1 = hashlib.sha1()
                digest512 = hashlib.sha512()

                def write(output: BinaryIO) -> int:
                    written = 0
                    while True:
                        chunk = response.read(DOWNLOAD_CHUNK_BYTES)
                        if not chunk:
                            break
                        written += len(chunk)
                        require(written <= item.size, "download exceeded indexed size")
                        digest1.update(chunk)
                        digest512.update(chunk)
                        output.write(chunk)
                    require(written == item.size, "download byte count disagrees with MRPack index")
                    require(digest1.hexdigest() == item.sha1, "download SHA-1 disagrees with MRPack index")
                    require(digest512.hexdigest() == item.sha512, "download SHA-512 disagrees with MRPack index")
                    return written

                written = publish_stream(target_path(root, item.path), write)
                return {
                    "bytes": written,
                    "final_url": final_url,
                    "http_status": status_code,
                    "path": item.path,
                    "sha1": digest1.hexdigest(),
                    "sha512": digest512.hexdigest(),
                    "source_url": source_url,
                }
        except (AuditError, OSError, urllib.error.URLError, zipfile.BadZipFile) as exc:
            failures.append(f"{source_url}: {exc}")
    raise AuditError(f"all indexed downloads failed for {item.path}: {'; '.join(failures)}")


def jar_mod_ids(path: Path, label: str, limits: Limits) -> tuple[str, ...]:
    try:
        archive = zipfile.ZipFile(path)
    except zipfile.BadZipFile as exc:
        raise AuditError(f"{label} is not a valid JAR: {exc}") from exc
    with archive:
        metadata_names = {
            "meta-inf/mods.toml": "forge",
            "meta-inf/neoforge.mods.toml": "forge",
            "fabric.mod.json": "fabric",
            "quilt.mod.json": "quilt",
        }
        selected: list[tuple[zipfile.ZipInfo, str]] = []
        seen_names: set[str] = set()
        for info in archive.infolist():
            key = info.filename.casefold()
            if key in metadata_names:
                require(key not in seen_names, f"{label} contains duplicate mod metadata")
                seen_names.add(key)
                require(info.file_size <= limits.max_metadata_bytes, f"{label} mod metadata exceeds its bound")
                selected.append((info, metadata_names[key]))
        require(selected, f"{label} has no recognized mod metadata")
        identifiers: set[str] = set()
        for info, kind in selected:
            blob = archive.read(info)
            require(len(blob) == info.file_size, f"{label} mod metadata size mismatch")
            try:
                if kind == "forge":
                    value = tomllib.loads(blob.decode("utf-8"))
                    mods = value.get("mods")
                    require(isinstance(mods, list) and mods, f"{label} Forge metadata has no mods")
                    raw_ids = [entry.get("modId") for entry in mods if isinstance(entry, dict)]
                elif kind == "fabric":
                    value = json.loads(blob)
                    raw_ids = [value.get("id")] if isinstance(value, dict) else []
                else:
                    value = json.loads(blob)
                    loader = value.get("quilt_loader") if isinstance(value, dict) else None
                    raw_ids = [loader.get("id")] if isinstance(loader, dict) else []
            except (UnicodeError, json.JSONDecodeError, tomllib.TOMLDecodeError) as exc:
                raise AuditError(f"{label} has invalid mod metadata: {exc}") from exc
            for raw_id in raw_ids:
                require(isinstance(raw_id, str) and MOD_ID.fullmatch(raw_id) is not None,
                        f"{label} contains an invalid mod ID")
                identifiers.add(raw_id)
        require(identifiers, f"{label} exposes no mod IDs")
        return tuple(sorted(identifiers))


def profile_inventory(root: Path, expected_paths: Iterable[str], limits: Limits) -> dict[str, object]:
    expected = tuple(sorted(expected_paths, key=str.casefold))
    actual: list[str] = []
    for path in root.rglob("*"):
        require(not is_reparse(path), "materialized profile contains a link/reparse point")
        if path.is_file():
            relative = path.relative_to(root).as_posix()
            actual.append(normalized_path(relative, "materialized profile path"))
        else:
            require(path.is_dir(), "materialized profile contains a special file")
    actual.sort(key=str.casefold)
    require(tuple(actual) == expected, "materialized profile has missing or unexpected files")
    files: list[dict[str, object]] = []
    total_bytes = 0
    mods: list[dict[str, object]] = []
    owners: dict[str, str] = {}
    for relative in actual:
        path = target_path(root, relative)
        blob = read_stable(path, f"materialized file {relative}", max(
            limits.max_override_file_bytes, limits.max_indexed_file_bytes
        ))
        total_bytes += len(blob)
        files.append({"bytes": len(blob), "path": relative, "sha256": sha_bytes(blob)})
        if PurePosixPath(relative).parent.as_posix().casefold() == "mods" and relative.casefold().endswith(".jar"):
            ids = jar_mod_ids(path, f"materialized mod {relative}", limits)
            for mod_id in ids:
                previous = owners.get(mod_id)
                require(previous is None, f"duplicate mod ID {mod_id}: {relative} conflicts with {previous}")
                owners[mod_id] = relative
            mods.append({"bytes": len(blob), "mod_ids": list(ids), "path": relative, "sha256": sha_bytes(blob)})
    return {
        "bytes": total_bytes,
        "duplicate_mod_ids": [],
        "file_count": len(files),
        "files": files,
        "jar_count": len(mods),
        "mods": mods,
    }


def materialize(candidate: Candidate, plan: MaterializationPlan, root: Path,
                opener: Opener, limits: Limits) -> tuple[list[dict[str, object]], dict[str, object]]:
    extract_overrides(candidate, plan, root)
    downloads = [download_one(item, root, opener, limits) for item in plan.indexed]
    inventory = profile_inventory(root, plan.output_paths, limits)
    return downloads, inventory


def ensure_new_receipt(path: Path) -> None:
    require(not os.path.lexists(path), "receipt output already exists; refusing to overwrite it")
    require(path.parent.is_dir() and not is_reparse(path.parent), "receipt parent must be an existing ordinary directory")


def require_path_disjoint_from_candidate(candidate_dir: Path, output_path: Path, label: str) -> None:
    candidate_resolved = candidate_dir.resolve(strict=False)
    output_resolved = output_path.resolve(strict=False)
    require(
        candidate_resolved != output_resolved
        and candidate_resolved not in output_resolved.parents
        and output_resolved not in candidate_resolved.parents,
        f"{label} must be disjoint from the immutable candidate directory",
    )


def write_exclusive(path: Path, blob: bytes) -> None:
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    descriptor = os.open(path, flags, 0o644)
    try:
        with os.fdopen(descriptor, "wb") as output:
            output.write(blob)
            output.flush()
            os.fsync(output.fileno())
    except BaseException:
        try:
            path.unlink(missing_ok=True)
        except OSError:
            pass
        raise


def pass_receipt(candidate: Candidate, binding: CandidateBindingEvidence, plan: MaterializationPlan,
                 downloads: list[dict[str, object]], inventory: dict[str, object], retained: bool,
                 audited_at: str, limits: Limits) -> dict[str, object]:
    return {
        "audited_at_utc": audited_at,
        "auditor": AUDITOR_ID,
        "candidate": candidate_summary(candidate),
        "candidate_binding": candidate_binding_summary(binding),
        "evidence_status": {
            "fresh_profile_materialized": True,
            "jar_mod_ids_unique": True,
            "launched": False,
            "live_qualified": False,
        },
        "materialization": {
            "indexed_file_count": len(plan.indexed),
            "mode": "retained" if retained else "temporary-discarded",
            "override_file_count": len(plan.overrides),
        },
        "mrpack_index": {
            "forge": EXPECTED_FORGE,
            "game": EXPECTED_GAME,
            "minecraft": EXPECTED_MINECRAFT,
            "name": plan.index["name"],
            "version_id": plan.index["versionId"],
        },
        "network": {"downloads": downloads},
        "policies": {
            "allowed_download_hosts": sorted(ALLOWED_DOWNLOAD_HOSTS),
            "exclusive_outputs": True,
            "https_only": True,
            "max_indexed_file_bytes": limits.max_indexed_file_bytes,
            "max_indexed_total_bytes": limits.max_indexed_total_bytes,
            "max_override_file_bytes": limits.max_override_file_bytes,
            "max_override_total_bytes": limits.max_override_total_bytes,
            "max_redirects": MAX_REDIRECTS,
            "no_gui_or_launch": True,
        },
        "profile": inventory,
        "schema_version": AUDIT_RECEIPT_SCHEMA,
        "status": "pass",
    }


def failure_receipt(candidate: Candidate | None, stage: str, error: BaseException,
                    audited_at: str) -> dict[str, object]:
    value: dict[str, object] = {
        "audited_at_utc": audited_at,
        "auditor": AUDITOR_ID,
        "evidence_status": {
            "fresh_profile_materialized": False,
            "launched": False,
            "live_qualified": False,
        },
        "failure": {"message": str(error), "stage": stage, "type": type(error).__name__},
        "profile": {"retained": False},
        "schema_version": AUDIT_RECEIPT_SCHEMA,
        "status": "fail",
    }
    if candidate is not None:
        value["candidate"] = candidate_summary(candidate)
    return value


EXPECTED_FAILURES = (AuditError, OSError, UnicodeError, zipfile.BadZipFile,
                     tomllib.TOMLDecodeError, urllib.error.URLError)


def run_install_audit(candidate_dir: Path, receipt_path: Path, keep_profile: Path | None = None,
                      *, opener: Opener | None = None, limits: Limits = Limits(),
                      expected_binding: ExpectedCandidateBinding | None = None,
                      clock: Callable[[], str] = utc_now) -> dict[str, object]:
    require_path_disjoint_from_candidate(candidate_dir, receipt_path, "receipt output")
    if keep_profile is not None:
        require_path_disjoint_from_candidate(candidate_dir, keep_profile, "kept profile output")
    ensure_new_receipt(receipt_path)
    if keep_profile is not None:
        require(not os.path.lexists(keep_profile), "kept profile destination already exists")
        require(keep_profile.parent.is_dir() and not is_reparse(keep_profile.parent),
                "kept profile parent must be an existing ordinary directory")
        receipt_resolved = receipt_path.resolve(strict=False)
        profile_resolved = keep_profile.resolve(strict=False)
        require(receipt_resolved != profile_resolved and profile_resolved not in receipt_resolved.parents,
                "receipt must not be inside the kept profile destination")

    audited_at = clock()
    require(isinstance(audited_at, str) and audited_at.endswith("Z"), "audit clock must return a UTC Z timestamp")
    candidate: Candidate | None = None
    binding: CandidateBindingEvidence | None = None
    created_profile: Path | None = None
    temporary: tempfile.TemporaryDirectory[str] | None = None
    stage = "candidate_identity"
    try:
        candidate = load_candidate(candidate_dir, limits)
        stage = "candidate_binding"
        binding = bind_candidate(candidate, expected_binding, limits)
        stage = "mrpack_preflight"
        plan = plan_materialization(candidate, limits)
        if keep_profile is None:
            temporary = tempfile.TemporaryDirectory(prefix="ashen-span-install-audit-")
            profile = Path(temporary.name)
            require_path_disjoint_from_candidate(candidate.directory, profile, "temporary profile output")
        else:
            keep_profile.mkdir()
            created_profile = keep_profile
            profile = keep_profile
        stage = "profile_materialization"
        downloads, inventory = materialize(candidate, plan, profile, opener or StrictHttpsOpener(), limits)
        stage = "candidate_recheck"
        rechecked_tree, rechecked_inventory = measure_candidate_tree(
            candidate.directory, binding.expected.inventory, limits
        )
        require(rechecked_tree == binding.measured_tree_sha256
                and rechecked_inventory == binding.measured_inventory,
                "candidate tree changed during audit")
        result = pass_receipt(
            candidate, binding, plan, downloads, inventory, keep_profile is not None, audited_at, limits
        )
    except EXPECTED_FAILURES as exc:
        if created_profile is not None and created_profile.exists() and not is_reparse(created_profile):
            shutil.rmtree(created_profile)
            created_profile = None
        result = failure_receipt(candidate, stage, exc, audited_at)
    finally:
        if temporary is not None:
            temporary.cleanup()
    try:
        write_exclusive(receipt_path, canonical_json(result))
    except BaseException:
        if created_profile is not None and created_profile.exists() and not is_reparse(created_profile):
            shutil.rmtree(created_profile)
        raise
    return result


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate-dir", type=Path, required=True,
                        help="immutable Operation Ashen Span candidate directory")
    parser.add_argument(
        "--candidate-binding",
        type=Path,
        help=("independent schema-1 candidate binding JSON; mandatory for every candidate after immutable RC5"),
    )
    parser.add_argument("--receipt", type=Path, required=True,
                        help="new canonical JSON receipt path (parent must exist)")
    parser.add_argument("--keep-profile", type=Path,
                        help="optional new destination; omitted means disposable temporary profile")
    parser.add_argument("--timeout-seconds", type=float, default=Limits.timeout_seconds)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    try:
        require(args.timeout_seconds > 0, "timeout must be positive")
        expected_binding = (
            load_expected_candidate_binding(args.candidate_binding)
            if args.candidate_binding is not None else None
        )
        result = run_install_audit(
            args.candidate_dir,
            args.receipt,
            args.keep_profile,
            limits=Limits(timeout_seconds=args.timeout_seconds),
            expected_binding=expected_binding,
        )
    except EXPECTED_FAILURES as exc:
        print(f"FAIL Operation Ashen Span install audit: {exc}", file=sys.stderr)
        return 1
    if result["status"] != "pass":
        failure = result["failure"]
        print(f"FAIL Operation Ashen Span install audit ({failure['stage']}): {failure['message']}", file=sys.stderr)
        return 1
    candidate = result["candidate"]
    profile = result["profile"]
    print(
        f"PASS {candidate['candidate_id']}: {profile['file_count']} files, "
        f"{profile['jar_count']} unique-mod-ID JARs, no launch; receipt {args.receipt}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
