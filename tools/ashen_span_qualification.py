#!/usr/bin/env python3
"""Create and close hash-bound Operation Ashen Span live-validation kits.

This tool never launches Minecraft and never edits a candidate.  ``init`` creates a
new, exclusive evidence directory whose blank records are bound to every candidate
file.  ``status`` validates a kit without writing.  ``finalize`` rechecks the
candidate, validates every record and attachment, and writes one exclusive canonical
``FINAL_RECEIPT.json`` whose SHA-256 must be retained outside the kit for every later
audit. Incomplete human observations remain PENDING; they can never be inferred from
offline or machine evidence.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import math
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import sys
import tempfile
from typing import Any, Iterable
import zipfile


SCHEMA_VERSION = 1
BUILD_COUNT = 6
BUILD_NAMES = ("Vanguard", "Siege", "Skirmisher", "Artillery", "Duelist", "Trooper")
EXPECTED_PHASE_ORDER = (
    "GARAGE", "P1", "P2", "P3", "P4", "P5A", "P5B", "SERVICE", "P6", "VICTORY"
)
MIN_MISSION_SECONDS = 10 * 60
MAX_MISSION_SECONDS = 13 * 60
MIN_PERFORMANCE_SAMPLE_SECONDS = 5 * 60
MIN_SOAK_MINUTES = 60
EXPECTED_TERRAIN_CHUNKS = 680
EXPECTED_REGION_FILES = 4
FINAL_RECEIPT = "FINAL_RECEIPT.json"
SESSION_FILE = "SESSION.json"
RECORD_FILES = tuple(f"build-{number:02d}.json" for number in range(1, BUILD_COUNT + 1)) + (
    "recovery.json",
    "performance.json",
    "soak.json",
)
TOP_LEVEL_FILES = frozenset((SESSION_FILE, *RECORD_FILES, FINAL_RECEIPT))
SESSION_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$")
HEX_64_RE = re.compile(r"^[0-9A-F]{64}$")
UTC_RE = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$")
RC5_SOURCE_COMMIT = "d96b7b84688e925f311849d7c40f72a4f8a691c2"
# Frozen historical diagnostic binding. The pre-erratum artifact predates the
# deterministic checkout policy and must be replaced with any authorized future runtime.
RC6_RUNTIME_SOURCE_COMMIT = "5a35ec3d9a69fdd4d88ed7a0b21b28bc1b18ecfb"
# Current RC5 and proposed RC6 identities share the impossible locked 680/6/6
# world contract.  No new kit or PASS receipt may be minted until an
# authoritative erratum and machine-generated actual-world acceptance are bound.
SAFETY_ENVELOPE_ERRATUM_ID: str | None = None
QUALIFIABLE_POST_ERRATUM_CANDIDATE_ID: str | None = None
CANDIDATE_IDENTITIES = {
    "operation-ashen-span-mp25-rc5": {
        "display_name": "Mech Arena 0.8 - Operation Ashen Span MP25 RC5",
        "forge": "47.3.3",
        "map_id": "cold_ruin_sector_01",
        "minecraft": "1.20.1",
        "mission_id": "operation_ashen_span",
        "version_id": "0.8.0-operation-ashen-span-mp25-rc5",
    },
    "operation-ashen-span-mp25-rc6": {
        "display_name": "Mech Arena 0.8 - Operation Ashen Span MP25 RC6",
        "forge": "47.3.3",
        "map_id": "cold_ruin_sector_01",
        "minecraft": "1.20.1",
        "mission_id": "operation_ashen_span",
        "version_id": "0.8.0-operation-ashen-span-mp25-rc6",
    },
}
CURRENT_PRE_ERRATUM_CANDIDATE_IDS = frozenset({
    "operation-ashen-span-mp25-rc5",
    "operation-ashen-span-mp25-rc6",
})
CANDIDATE_PRIMARY_FILES = {
    "operation-ashen-span-mp25-rc5": {
        "client_mrpack": "mech-arena-0.8.0-operation-ashen-span-mp25-rc5.mrpack",
        "server_overlay": "mech-arena-operation-ashen-span-mp25-rc5-server-overlay.zip",
        "bounded_world": "cold_ruin_sector_01-mp25-world.zip",
        "pomkots_mp25": "pomkotsmechs-forge-0.0.1-alpha.7-mp.25.jar",
    },
    "operation-ashen-span-mp25-rc6": {
        "client_mrpack": "mech-arena-0.8.0-operation-ashen-span-mp25-rc6.mrpack",
        "server_overlay": "mech-arena-operation-ashen-span-mp25-rc6-server-overlay.zip",
        "bounded_world": "cold_ruin_sector_01-mp25-world.zip",
        "pomkots_mp25": "pomkotsmechs-forge-0.0.1-alpha.7-mp.25.jar",
    },
}
COMMON_PAYLOAD_FILES = frozenset({
    "ASHEN_SPAN_LIVE_VALIDATION.md",
    "ROLLBACK.md",
    "RUNBOOK.md",
    "THIRD_PARTY_NOTICES.md",
    "cold_ruin_sector_01-mp25-world.zip",
    "pomkotsmechs-forge-0.0.1-alpha.7-mp.25.jar",
})
CANDIDATE_TREE_DOMAIN = b"operation-ashen-span-candidate-tree-v1\0"


class QualificationError(RuntimeError):
    """A fail-closed validation error."""


def require_candidate_eligible(action: str, candidate_id: str) -> None:
    if SAFETY_ENVELOPE_ERRATUM_ID is None:
        raise QualificationError(
            f"cannot {action}: genuine view-distance 6 player tickets exceed the "
            "authoritative 680-chunk safety envelope; see "
            "docs/ashen-span-hardening/SAFETY_ENVELOPE_BLOCKER.md"
        )
    if candidate_id in CURRENT_PRE_ERRATUM_CANDIDATE_IDS:
        raise QualificationError(
            f"cannot {action}: historical candidate {candidate_id} is permanently "
            "ineligible; qualification requires a regenerated post-erratum identity"
        )
    if QUALIFIABLE_POST_ERRATUM_CANDIDATE_ID is None:
        raise QualificationError(
            f"cannot {action}: no regenerated post-erratum candidate identity is authorized"
        )
    if candidate_id != QUALIFIABLE_POST_ERRATUM_CANDIDATE_ID:
        raise QualificationError(
            f"cannot {action}: candidate {candidate_id} is not the authorized "
            f"post-erratum identity {QUALIFIABLE_POST_ERRATUM_CANDIDATE_ID}"
        )


def _reject_constant(value: str) -> None:
    raise QualificationError(f"non-finite JSON number is forbidden: {value}")


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise QualificationError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def read_json(path: Path) -> Any:
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as exc:
        raise QualificationError(f"cannot read {path}: {exc}") from exc
    try:
        return json.loads(text, object_pairs_hook=_unique_object, parse_constant=_reject_constant)
    except (json.JSONDecodeError, UnicodeDecodeError) as exc:
        raise QualificationError(f"invalid JSON in {path}: {exc}") from exc


def parse_json_bytes(blob: bytes, label: str) -> Any:
    try:
        text = blob.decode("utf-8")
        return json.loads(text, object_pairs_hook=_unique_object, parse_constant=_reject_constant)
    except (json.JSONDecodeError, UnicodeDecodeError) as exc:
        raise QualificationError(f"invalid JSON in {label}: {exc}") from exc


def canonical_json_bytes(value: Any) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True, allow_nan=False) + "\n").encode("utf-8")


def write_canonical(path: Path, value: Any, *, exclusive: bool = False) -> None:
    data = canonical_json_bytes(value)
    mode = "xb" if exclusive else "wb"
    try:
        with path.open(mode) as handle:
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
    except FileExistsError as exc:
        raise QualificationError(f"refusing to overwrite {path}") from exc


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    try:
        with path.open("rb") as handle:
            for block in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(block)
    except OSError as exc:
        raise QualificationError(f"cannot hash {path}: {exc}") from exc
    return digest.hexdigest().upper()


def file_fact(path: Path, relative: str) -> dict[str, Any]:
    if path.is_symlink() or not path.is_file():
        raise QualificationError(f"candidate member is not a regular file: {relative}")
    return {"bytes": path.stat().st_size, "file": relative, "sha256": sha256_file(path)}


def candidate_tree_sha256(inventory: Iterable[dict[str, Any]]) -> str:
    digest = hashlib.sha256()
    digest.update(CANDIDATE_TREE_DOMAIN)
    for fact in sorted(inventory, key=lambda item: item["file"]):
        digest.update(fact["file"].encode("utf-8"))
        digest.update(b"\0")
        digest.update(str(fact["bytes"]).encode("ascii"))
        digest.update(b"\0")
        digest.update(fact["sha256"].encode("ascii"))
        digest.update(b"\n")
    return digest.hexdigest().upper()


def require_object(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise QualificationError(f"{label} must be an object")
    return value


def require_exact_keys(value: dict[str, Any], expected: Iterable[str], label: str) -> None:
    expected_set = set(expected)
    actual = set(value)
    if actual != expected_set:
        missing = sorted(expected_set - actual)
        extra = sorted(actual - expected_set)
        raise QualificationError(f"{label} keys differ; missing={missing}, extra={extra}")


def require_text(value: Any, label: str, *, nullable: bool = False) -> str | None:
    if value is None and nullable:
        return None
    if not isinstance(value, str) or not value.strip():
        raise QualificationError(f"{label} must be a non-empty string")
    return value


def require_bool(value: Any, label: str, *, nullable: bool = False) -> bool | None:
    if value is None and nullable:
        return None
    if not isinstance(value, bool):
        raise QualificationError(f"{label} must be boolean")
    return value


def require_number(value: Any, label: str, *, nullable: bool = False, minimum: float | None = None) -> float | None:
    if value is None and nullable:
        return None
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(float(value)):
        raise QualificationError(f"{label} must be a finite number")
    number = float(value)
    if minimum is not None and number < minimum:
        raise QualificationError(f"{label} must be >= {minimum}")
    return number


def validate_utc(value: Any, label: str, *, nullable: bool = False) -> str | None:
    text = require_text(value, label, nullable=nullable)
    if text is None:
        return None
    if not UTC_RE.fullmatch(text):
        raise QualificationError(f"{label} must use YYYY-MM-DDTHH:MM:SSZ")
    try:
        dt.datetime.strptime(text, "%Y-%m-%dT%H:%M:%SZ")
    except ValueError as exc:
        raise QualificationError(f"{label} is not a valid UTC timestamp") from exc
    return text


def parse_utc(value: str) -> dt.datetime:
    return dt.datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=dt.timezone.utc)


def now_utc() -> str:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).strftime("%Y-%m-%dT%H:%M:%SZ")


def _parse_sha256s(path: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    for line_number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not raw:
            continue
        match = re.fullmatch(r"([0-9A-Fa-f]{64}) \*([^/\\]+)", raw)
        if not match:
            raise QualificationError(f"invalid SHA256SUMS line {line_number}")
        name = match.group(2)
        if name in result:
            raise QualificationError(f"duplicate SHA256SUMS member: {name}")
        result[name] = match.group(1).upper()
    return result


def _validate_mrpack_identity(path: Path, candidate_id: str) -> None:
    expected = CANDIDATE_IDENTITIES[candidate_id]
    try:
        with zipfile.ZipFile(path, "r") as archive:
            index_entries = [info for info in archive.infolist() if info.filename == "modrinth.index.json"]
            if len(index_entries) != 1:
                raise QualificationError("candidate MRPack must contain exactly one modrinth.index.json")
            info = index_entries[0]
            if info.is_dir() or info.file_size > 1024 * 1024:
                raise QualificationError("candidate MRPack index is missing or unreasonably large")
            index = require_object(parse_json_bytes(archive.read(info), "candidate MRPack index"), "candidate MRPack index")
    except (OSError, zipfile.BadZipFile, RuntimeError) as exc:
        if isinstance(exc, QualificationError):
            raise
        raise QualificationError(f"cannot inspect candidate MRPack: {exc}") from exc
    if index.get("versionId") != expected["version_id"] or index.get("name") != expected["display_name"]:
        raise QualificationError("candidate MRPack internal identity differs from the locked Ashen Span identity")


def bind_candidate(candidate_dir: Path, expected_candidate_tree_sha256: str) -> dict[str, Any]:
    candidate_dir = candidate_dir.resolve()
    if not candidate_dir.is_dir() or candidate_dir.is_symlink():
        raise QualificationError(f"candidate directory is missing or unsafe: {candidate_dir}")
    children = sorted(candidate_dir.iterdir(), key=lambda item: item.name)
    if any(child.is_dir() or child.is_symlink() for child in children):
        raise QualificationError("candidate root must contain regular files only")
    inventory = [file_fact(child, child.name) for child in children]
    expected_tree = expected_candidate_tree_sha256.upper()
    if not HEX_64_RE.fullmatch(expected_tree):
        raise QualificationError("candidate tree SHA-256 must be 64 hexadecimal characters")
    actual_tree = candidate_tree_sha256(inventory)
    if actual_tree != expected_tree:
        raise QualificationError("candidate tree differs from the independently recorded SHA-256 anchor")
    by_name = {entry["file"]: entry for entry in inventory}
    if len(inventory) != 11:
        raise QualificationError(f"Ashen Span candidate must contain exactly 11 files; found {len(inventory)}")
    required = {"MANIFEST.json", "OFFLINE_BUILD_RECEIPT.json", "SHA256SUMS.txt"}
    if not required.issubset(by_name):
        raise QualificationError(f"candidate is missing {sorted(required - set(by_name))}")

    manifest = require_object(read_json(candidate_dir / "MANIFEST.json"), "MANIFEST.json")
    receipt = require_object(read_json(candidate_dir / "OFFLINE_BUILD_RECEIPT.json"), "OFFLINE_BUILD_RECEIPT.json")
    candidate_id = require_text(manifest.get("candidate_id"), "manifest candidate_id")
    if candidate_id not in CANDIDATE_IDENTITIES:
        raise QualificationError(f"unsupported Ashen Span candidate identity: {candidate_id}")
    if manifest.get("schema_version") != SCHEMA_VERSION:
        raise QualificationError("manifest schema_version is not supported")
    identity = require_object(manifest.get("identity"), "manifest identity")
    expected_identity = CANDIDATE_IDENTITIES[candidate_id]
    for key, expected in expected_identity.items():
        if identity.get(key) != expected:
            raise QualificationError(f"manifest identity {key} is not the locked Ashen Span value")
    source_commit = require_text(identity.get("source_commit"), "manifest source_commit")
    if not re.fullmatch(r"[0-9a-f]{40}", source_commit or ""):
        raise QualificationError("manifest source_commit must be 40 lowercase hex characters")
    if candidate_id.endswith("-rc5") and source_commit != RC5_SOURCE_COMMIT:
        raise QualificationError("RC5 source_commit is not the immutable Ashen Span commit")
    if receipt.get("candidate_id") != candidate_id or receipt.get("source_commit") != source_commit:
        raise QualificationError("manifest and offline receipt identity differ")
    if receipt.get("schema_version") != SCHEMA_VERSION:
        raise QualificationError("offline receipt schema_version is not supported")
    if receipt.get("status") != "offline-only-not-live-qualified":
        raise QualificationError("offline receipt does not preserve the live-validation boundary")
    if manifest.get("status") != "offline-candidate-not-live-qualified":
        raise QualificationError("candidate status does not preserve the offline-only boundary")
    if candidate_id.endswith("-rc6"):
        runtime_source = require_text(identity.get("runtime_source_commit"),
                                      "RC6 manifest runtime_source_commit")
        if runtime_source != RC6_RUNTIME_SOURCE_COMMIT:
            raise QualificationError("RC6 runtime source is not the exact production-fix commit")
        if receipt.get("runtime_source_commit") != runtime_source:
            raise QualificationError("RC6 manifest and receipt runtime source differ")

    primary_names = CANDIDATE_PRIMARY_FILES[candidate_id]
    expected_payloads = COMMON_PAYLOAD_FILES | frozenset(primary_names.values())
    expected_inventory = expected_payloads | {"MANIFEST.json", "OFFLINE_BUILD_RECEIPT.json", "SHA256SUMS.txt"}
    if set(by_name) != expected_inventory:
        raise QualificationError(
            "candidate filenames differ from the locked Ashen Span inventory; "
            f"missing={sorted(expected_inventory - set(by_name))}, extra={sorted(set(by_name) - expected_inventory)}"
        )

    sums = _parse_sha256s(candidate_dir / "SHA256SUMS.txt")
    expected_sums = set(by_name) - {"SHA256SUMS.txt"}
    if set(sums) != expected_sums:
        raise QualificationError("SHA256SUMS inventory differs from candidate inventory")
    for name, expected_hash in sums.items():
        if by_name[name]["sha256"] != expected_hash:
            raise QualificationError(f"SHA256SUMS mismatch for {name}")

    artifacts = require_object(manifest.get("artifacts"), "manifest artifacts")
    if set(artifacts) != expected_payloads:
        raise QualificationError("manifest artifact inventory differs from the locked Ashen Span payload")
    for name, fact in artifacts.items():
        if name not in by_name or not isinstance(fact, dict):
            raise QualificationError(f"manifest artifact is absent or malformed: {name}")
        if fact.get("file") != name or fact.get("bytes") != by_name[name]["bytes"] or str(fact.get("sha256", "")).upper() != by_name[name]["sha256"]:
            raise QualificationError(f"manifest artifact mismatch: {name}")

    primary_facts = {role: by_name[name] for role, name in primary_names.items()}
    _validate_mrpack_identity(candidate_dir / primary_names["client_mrpack"], candidate_id)

    return {
        "candidate_id": candidate_id,
        "candidate_tree_sha256": actual_tree,
        "source_commit": source_commit,
        "manifest": by_name["MANIFEST.json"],
        "offline_receipt": by_name["OFFLINE_BUILD_RECEIPT.json"],
        "sha256s": by_name["SHA256SUMS.txt"],
        "primary_artifacts": primary_facts,
        "inventory": inventory,
    }


def session_template(session_id: str, created_utc: str, binding: dict[str, Any]) -> dict[str, Any]:
    return {
        "candidate": binding,
        "created_utc": created_utc,
        "environment": {
            "client_launcher": None,
            "control_scheme": None,
            "display_resolution": None,
            "java_version": None,
            "operating_system": None,
        },
        "kit_type": "operation-ashen-span-live-validation",
        "notes": None,
        "schema_version": SCHEMA_VERSION,
        "session_id": session_id,
        "status": "pending",
    }


def build_template(number: int) -> dict[str, Any]:
    return {
        "attachment_paths": [],
        "build_name": BUILD_NAMES[number - 1],
        "build_number": number,
        "human": {
            "audio_cues_pass": None,
            "balance_pass": None,
            "controls_pass": None,
            "issues": [],
            "notes": None,
            "visual_readability_pass": None,
        },
        "machine": {
            "cleanup_entity_count": None,
            "cleanup_gate_ledger_entries": None,
            "ended_utc": None,
            "gatekeeper_r01_duration_seconds": None,
            "gate_restore_pass": None,
            "gatekeeper_r01_defeated": None,
            "mission_completed": None,
            "mission_duration_seconds": None,
            "mission_outcome": None,
            "phase_elapsed_seconds": None,
            "phase_order": None,
            "service_checkpoint_used": None,
            "span_warden_duration_seconds": None,
            "span_warden_defeated": None,
            "started_utc": None,
        },
        "record_type": "garage-fleet-build",
        "schema_version": SCHEMA_VERSION,
        "verdict": None,
    }


def recovery_template() -> dict[str, Any]:
    return {
        "attachment_paths": [],
        "human": {
            "disconnect_flow_pass": None,
            "issues": [],
            "notes": None,
            "restart_restore_observed_pass": None,
            "retry_flow_pass": None,
        },
        "machine": {
            "disconnect_restore_pass": None,
            "gate_ledger_entries_after": None,
            "mission_entity_count_after": None,
            "restart_restore_pass": None,
            "retry_restore_pass": None,
        },
        "record_type": "recovery",
        "schema_version": SCHEMA_VERSION,
        "verdict": None,
    }


def performance_template() -> dict[str, Any]:
    return {
        "attachment_paths": [],
        "human": {
            "fps_acceptance_pass": None,
            "frame_pacing_pass": None,
            "issues": [],
            "notes": None,
        },
        "machine": {
            "cpu": None,
            "fps_average": None,
            "fps_one_percent_low": None,
            "frame_time_p95_ms": None,
            "gpu": None,
            "ram_gib": None,
            "sample_duration_seconds": None,
        },
        "record_type": "performance",
        "schema_version": SCHEMA_VERSION,
        "verdict": None,
    }


def soak_template() -> dict[str, Any]:
    return {
        "attachment_paths": [],
        "human": {
            "issues": [],
            "notes": None,
            "residue_review_pass": None,
            "soak_experience_pass": None,
        },
        "machine": {
            "duration_minutes": None,
            "gate_ledger_entries_after": None,
            "mission_entity_count_after": None,
            "outside_envelope_chunks_after": None,
            "region_file_count_after": None,
            "region_file_count_before": None,
            "terrain_chunk_count_after": None,
            "terrain_chunk_count_before": None,
            "unexpected_world_growth": None,
            "world_bytes_after": None,
            "world_bytes_before": None,
        },
        "record_type": "soak",
        "schema_version": SCHEMA_VERSION,
        "verdict": None,
    }


def initialize_kit(candidate_dir: Path, output_dir: Path, session_id: str, created_utc: str,
                   expected_candidate_tree_sha256: str) -> dict[str, Any]:
    if not SESSION_ID_RE.fullmatch(session_id):
        raise QualificationError("session_id must be 1-80 safe filename characters")
    validate_utc(created_utc, "created_utc")
    binding = bind_candidate(candidate_dir, expected_candidate_tree_sha256)
    require_candidate_eligible("initialize Ashen Span qualification", binding["candidate_id"])
    candidate_root = candidate_dir.resolve()
    output_dir = output_dir.resolve()
    if (candidate_root == output_dir
            or candidate_root in output_dir.parents
            or output_dir in candidate_root.parents):
        raise QualificationError(
            "qualification output and immutable candidate paths must be disjoint"
        )
    if output_dir.exists() or output_dir.is_symlink():
        raise QualificationError(f"refusing existing output directory: {output_dir}")
    output_dir.parent.mkdir(parents=True, exist_ok=True)
    scratch: Path | None = Path(tempfile.mkdtemp(prefix=f".{output_dir.name}.tmp-", dir=output_dir.parent))
    claimed = False
    published = False
    try:
        write_canonical(scratch / SESSION_FILE, session_template(session_id, created_utc, binding))
        for number in range(1, BUILD_COUNT + 1):
            write_canonical(scratch / f"build-{number:02d}.json", build_template(number))
        write_canonical(scratch / "recovery.json", recovery_template())
        write_canonical(scratch / "performance.json", performance_template())
        write_canonical(scratch / "soak.json", soak_template())
        (scratch / "attachments").mkdir()
        try:
            output_dir.mkdir()
            claimed = True
        except FileExistsError as exc:
            raise QualificationError(f"refusing existing output directory: {output_dir}") from exc
        try:
            for child in scratch.iterdir():
                child.rename(output_dir / child.name)
            scratch.rmdir()
            scratch = None
            published = True
        except Exception:
            shutil.rmtree(output_dir)
            raise
    finally:
        if scratch is not None and scratch.exists():
            shutil.rmtree(scratch)
        if claimed and not published and output_dir.exists():
            # Only the successful exclusive mkdir above can reach this branch. Its
            # partial contents belong to this invocation and are safe to remove.
            shutil.rmtree(output_dir)
    return {"candidate_id": binding["candidate_id"], "session_id": session_id, "status": "pending"}


def _validate_verdict(value: Any, label: str) -> str | None:
    if value is None:
        return None
    if value not in ("pass", "fail"):
        raise QualificationError(f"{label} must be null, 'pass', or 'fail'")
    return value


def _validate_issues(value: Any, label: str) -> list[str]:
    if not isinstance(value, list) or any(not isinstance(item, str) or not item.strip() for item in value):
        raise QualificationError(f"{label} must be an array of non-empty strings")
    return value


def _validate_attachment_paths(value: Any, label: str) -> list[str]:
    if not isinstance(value, list) or any(not isinstance(item, str) for item in value):
        raise QualificationError(f"{label} must be an array of paths")
    if len(value) != len(set(value)):
        raise QualificationError(f"{label} contains duplicate paths")
    for item in value:
        pure = PurePosixPath(item)
        if pure.is_absolute() or len(pure.parts) < 2 or pure.parts[0] != "attachments" or ".." in pure.parts or "\\" in item:
            raise QualificationError(f"unsafe attachment path in {label}: {item}")
    return value


def _nullable_text(value: Any, label: str) -> None:
    if value is not None and not isinstance(value, str):
        raise QualificationError(f"{label} must be null or a string")


def validate_session(value: Any) -> tuple[dict[str, Any], bool]:
    session = require_object(value, SESSION_FILE)
    require_exact_keys(session, ("candidate", "created_utc", "environment", "kit_type", "notes", "schema_version", "session_id", "status"), SESSION_FILE)
    if session["schema_version"] != SCHEMA_VERSION or session["kit_type"] != "operation-ashen-span-live-validation" or session["status"] != "pending":
        raise QualificationError("SESSION identity or status is invalid")
    if not isinstance(session["session_id"], str) or not SESSION_ID_RE.fullmatch(session["session_id"]):
        raise QualificationError("SESSION session_id is invalid")
    validate_utc(session["created_utc"], "SESSION created_utc")
    _nullable_text(session["notes"], "SESSION notes")
    env = require_object(session["environment"], "SESSION environment")
    keys = ("client_launcher", "control_scheme", "display_resolution", "java_version", "operating_system")
    require_exact_keys(env, keys, "SESSION environment")
    complete = True
    for key in keys:
        if env[key] is None:
            complete = False
        else:
            require_text(env[key], f"SESSION environment.{key}")
    binding = require_object(session["candidate"], "SESSION candidate")
    return binding, complete


def _validate_build(value: Any, expected_number: int) -> tuple[str | None, list[str], bool]:
    label = f"build-{expected_number:02d}.json"
    record = require_object(value, label)
    require_exact_keys(record, ("attachment_paths", "build_name", "build_number", "human", "machine", "record_type", "schema_version", "verdict"), label)
    if record["schema_version"] != SCHEMA_VERSION or record["record_type"] != "garage-fleet-build":
        raise QualificationError(f"{label} identity is invalid")
    if record["build_number"] != expected_number or record["build_name"] != BUILD_NAMES[expected_number - 1]:
        raise QualificationError(f"{label} build identity is invalid")
    verdict = _validate_verdict(record["verdict"], f"{label} verdict")
    attachments = _validate_attachment_paths(record["attachment_paths"], f"{label} attachment_paths")
    human = require_object(record["human"], f"{label} human")
    require_exact_keys(human, ("audio_cues_pass", "balance_pass", "controls_pass", "issues", "notes", "visual_readability_pass"), f"{label} human")
    issues = _validate_issues(human["issues"], f"{label} human.issues")
    _nullable_text(human["notes"], f"{label} human.notes")
    human_bools = [require_bool(human[key], f"{label} human.{key}", nullable=True) for key in ("audio_cues_pass", "balance_pass", "controls_pass", "visual_readability_pass")]
    machine = require_object(record["machine"], f"{label} machine")
    machine_keys = ("cleanup_entity_count", "cleanup_gate_ledger_entries", "ended_utc", "gatekeeper_r01_duration_seconds", "gate_restore_pass", "gatekeeper_r01_defeated", "mission_completed", "mission_duration_seconds", "mission_outcome", "phase_elapsed_seconds", "phase_order", "service_checkpoint_used", "span_warden_duration_seconds", "span_warden_defeated", "started_utc")
    require_exact_keys(machine, machine_keys, f"{label} machine")
    started = validate_utc(machine["started_utc"], f"{label} started_utc", nullable=True)
    ended = validate_utc(machine["ended_utc"], f"{label} ended_utc", nullable=True)
    if started and ended and ended < started:
        raise QualificationError(f"{label} ended_utc precedes started_utc")
    duration = require_number(machine["mission_duration_seconds"], f"{label} mission_duration_seconds", nullable=True, minimum=0)
    boss_durations = [require_number(machine[key], f"{label} {key}", nullable=True, minimum=0) for key in ("gatekeeper_r01_duration_seconds", "span_warden_duration_seconds")]
    if started and ended and duration is not None:
        observed = (parse_utc(ended) - parse_utc(started)).total_seconds()
        if abs(observed - duration) > 1:
            raise QualificationError(f"{label} timestamps contradict mission_duration_seconds")
    for key in ("cleanup_entity_count", "cleanup_gate_ledger_entries"):
        number = require_number(machine[key], f"{label} {key}", nullable=True, minimum=0)
        if number is not None and not number.is_integer():
            raise QualificationError(f"{label} {key} must be an integer")
    machine_bools = [require_bool(machine[key], f"{label} machine.{key}", nullable=True) for key in ("gate_restore_pass", "gatekeeper_r01_defeated", "mission_completed", "service_checkpoint_used", "span_warden_defeated")]
    outcome = machine["mission_outcome"]
    if outcome is not None and outcome not in ("victory", "defeat"):
        raise QualificationError(f"{label} mission_outcome must be null, victory, or defeat")
    phases = machine["phase_order"]
    if phases is not None and (not isinstance(phases, list) or any(not isinstance(item, str) for item in phases)):
        raise QualificationError(f"{label} phase_order must be null or an array of strings")
    elapsed = machine["phase_elapsed_seconds"]
    elapsed_values: list[float] | None = None
    if elapsed is not None:
        elapsed_object = require_object(elapsed, f"{label} phase_elapsed_seconds")
        require_exact_keys(elapsed_object, EXPECTED_PHASE_ORDER, f"{label} phase_elapsed_seconds")
        elapsed_values = [require_number(elapsed_object[key], f"{label} phase_elapsed_seconds.{key}", minimum=0) for key in EXPECTED_PHASE_ORDER]
        if any(right < left for left, right in zip(elapsed_values, elapsed_values[1:])):
            raise QualificationError(f"{label} phase elapsed times must be nondecreasing")
    if verdict == "fail" and not issues:
        raise QualificationError(f"{label} fail verdict requires at least one issue")
    if verdict == "pass":
        pass_ok = (
            all(item is True for item in human_bools + machine_bools)
            and not issues
            and started is not None and ended is not None
            and duration is not None and MIN_MISSION_SECONDS <= duration <= MAX_MISSION_SECONDS
            and all(item is not None and item > 0 for item in boss_durations)
            and outcome == "victory"
            and phases == list(EXPECTED_PHASE_ORDER)
            and elapsed_values is not None and elapsed_values[0] == 0
            and abs(elapsed_values[-1] - duration) <= 1
            and machine["cleanup_entity_count"] == 0
            and machine["cleanup_gate_ledger_entries"] == 0
            and bool(attachments)
        )
        if not pass_ok:
            raise QualificationError(f"{label} claims pass without every required machine/human gate")
    return verdict, attachments, all(item is not None for item in human_bools)


def _validate_recovery(value: Any) -> tuple[str | None, list[str], bool]:
    label = "recovery.json"
    record = require_object(value, label)
    require_exact_keys(record, ("attachment_paths", "human", "machine", "record_type", "schema_version", "verdict"), label)
    if record["schema_version"] != SCHEMA_VERSION or record["record_type"] != "recovery":
        raise QualificationError(f"{label} identity is invalid")
    verdict = _validate_verdict(record["verdict"], f"{label} verdict")
    attachments = _validate_attachment_paths(record["attachment_paths"], f"{label} attachment_paths")
    human = require_object(record["human"], f"{label} human")
    require_exact_keys(human, ("disconnect_flow_pass", "issues", "notes", "restart_restore_observed_pass", "retry_flow_pass"), f"{label} human")
    issues = _validate_issues(human["issues"], f"{label} issues")
    _nullable_text(human["notes"], f"{label} notes")
    human_bools = [require_bool(human[key], f"{label} {key}", nullable=True) for key in ("disconnect_flow_pass", "restart_restore_observed_pass", "retry_flow_pass")]
    machine = require_object(record["machine"], f"{label} machine")
    require_exact_keys(machine, ("disconnect_restore_pass", "gate_ledger_entries_after", "mission_entity_count_after", "restart_restore_pass", "retry_restore_pass"), f"{label} machine")
    machine_bools = [require_bool(machine[key], f"{label} {key}", nullable=True) for key in ("disconnect_restore_pass", "restart_restore_pass", "retry_restore_pass")]
    for key in ("gate_ledger_entries_after", "mission_entity_count_after"):
        number = require_number(machine[key], f"{label} {key}", nullable=True, minimum=0)
        if number is not None and not number.is_integer():
            raise QualificationError(f"{label} {key} must be an integer")
    if verdict == "fail" and not issues:
        raise QualificationError(f"{label} fail verdict requires an issue")
    if verdict == "pass" and not (all(item is True for item in human_bools + machine_bools) and not issues and machine["gate_ledger_entries_after"] == 0 and machine["mission_entity_count_after"] == 0 and attachments):
        raise QualificationError(f"{label} claims pass without every recovery gate")
    return verdict, attachments, all(item is not None for item in human_bools)


def _validate_performance(value: Any) -> tuple[str | None, list[str], bool]:
    label = "performance.json"
    record = require_object(value, label)
    require_exact_keys(record, ("attachment_paths", "human", "machine", "record_type", "schema_version", "verdict"), label)
    if record["schema_version"] != SCHEMA_VERSION or record["record_type"] != "performance":
        raise QualificationError(f"{label} identity is invalid")
    verdict = _validate_verdict(record["verdict"], f"{label} verdict")
    attachments = _validate_attachment_paths(record["attachment_paths"], f"{label} attachment_paths")
    human = require_object(record["human"], f"{label} human")
    require_exact_keys(human, ("fps_acceptance_pass", "frame_pacing_pass", "issues", "notes"), f"{label} human")
    issues = _validate_issues(human["issues"], f"{label} issues")
    _nullable_text(human["notes"], f"{label} notes")
    human_bools = [require_bool(human[key], f"{label} {key}", nullable=True) for key in ("fps_acceptance_pass", "frame_pacing_pass")]
    machine = require_object(record["machine"], f"{label} machine")
    require_exact_keys(machine, ("cpu", "fps_average", "fps_one_percent_low", "frame_time_p95_ms", "gpu", "ram_gib", "sample_duration_seconds"), f"{label} machine")
    text_values = [require_text(machine[key], f"{label} {key}", nullable=True) for key in ("cpu", "gpu")]
    numeric_values = [require_number(machine[key], f"{label} {key}", nullable=True, minimum=0) for key in ("fps_average", "fps_one_percent_low", "frame_time_p95_ms", "ram_gib", "sample_duration_seconds")]
    if verdict == "fail" and not issues:
        raise QualificationError(f"{label} fail verdict requires an issue")
    if verdict == "pass" and not (all(item is True for item in human_bools) and all(item is not None and item > 0 for item in numeric_values) and numeric_values[-1] >= MIN_PERFORMANCE_SAMPLE_SECONDS and all(text_values) and not issues and attachments):
        raise QualificationError(f"{label} claims pass without measurements and explicit human acceptance")
    return verdict, attachments, all(item is not None for item in human_bools)


def _validate_soak(value: Any) -> tuple[str | None, list[str], bool]:
    label = "soak.json"
    record = require_object(value, label)
    require_exact_keys(record, ("attachment_paths", "human", "machine", "record_type", "schema_version", "verdict"), label)
    if record["schema_version"] != SCHEMA_VERSION or record["record_type"] != "soak":
        raise QualificationError(f"{label} identity is invalid")
    verdict = _validate_verdict(record["verdict"], f"{label} verdict")
    attachments = _validate_attachment_paths(record["attachment_paths"], f"{label} attachment_paths")
    human = require_object(record["human"], f"{label} human")
    require_exact_keys(human, ("issues", "notes", "residue_review_pass", "soak_experience_pass"), f"{label} human")
    issues = _validate_issues(human["issues"], f"{label} issues")
    _nullable_text(human["notes"], f"{label} notes")
    human_bools = [require_bool(human[key], f"{label} {key}", nullable=True) for key in ("residue_review_pass", "soak_experience_pass")]
    machine = require_object(record["machine"], f"{label} machine")
    keys = ("duration_minutes", "gate_ledger_entries_after", "mission_entity_count_after", "outside_envelope_chunks_after", "region_file_count_after", "region_file_count_before", "terrain_chunk_count_after", "terrain_chunk_count_before", "unexpected_world_growth", "world_bytes_after", "world_bytes_before")
    require_exact_keys(machine, keys, f"{label} machine")
    duration = require_number(machine["duration_minutes"], f"{label} duration_minutes", nullable=True, minimum=0)
    unexpected = require_bool(machine["unexpected_world_growth"], f"{label} unexpected_world_growth", nullable=True)
    for key in set(keys) - {"duration_minutes", "unexpected_world_growth"}:
        number = require_number(machine[key], f"{label} {key}", nullable=True, minimum=0)
        if number is not None and not number.is_integer():
            raise QualificationError(f"{label} {key} must be an integer")
    if verdict == "fail" and not issues:
        raise QualificationError(f"{label} fail verdict requires an issue")
    if verdict == "pass":
        pass_ok = (
            all(item is True for item in human_bools)
            and not issues and attachments
            and duration is not None and duration >= MIN_SOAK_MINUTES
            and unexpected is False
            and machine["terrain_chunk_count_before"] == EXPECTED_TERRAIN_CHUNKS
            and machine["terrain_chunk_count_after"] == EXPECTED_TERRAIN_CHUNKS
            and machine["region_file_count_before"] == EXPECTED_REGION_FILES
            and machine["region_file_count_after"] == EXPECTED_REGION_FILES
            and machine["outside_envelope_chunks_after"] == 0
            and machine["mission_entity_count_after"] == 0
            and machine["gate_ledger_entries_after"] == 0
            and machine["world_bytes_before"] is not None
            and machine["world_bytes_after"] is not None
        )
        if not pass_ok:
            raise QualificationError(f"{label} claims pass without the 60-minute bounded-world cleanup gates")
    return verdict, attachments, all(item is not None for item in human_bools)


def _attachment_inventory(kit_dir: Path, referenced: set[str]) -> list[dict[str, Any]]:
    attachment_root = kit_dir / "attachments"
    if not attachment_root.is_dir() or attachment_root.is_symlink():
        raise QualificationError("attachments directory is missing or unsafe")
    actual: set[str] = set()
    casefolded: set[str] = set()
    facts: list[dict[str, Any]] = []
    for path in sorted(attachment_root.rglob("*"), key=lambda item: item.as_posix()):
        if path.is_symlink():
            raise QualificationError(f"attachment symlink is forbidden: {path}")
        if path.is_dir():
            continue
        relative = path.relative_to(kit_dir).as_posix()
        folded = relative.casefold()
        if folded in casefolded:
            raise QualificationError(f"case-colliding attachment path: {relative}")
        casefolded.add(folded)
        actual.add(relative)
        facts.append(file_fact(path, relative))
    if actual != referenced:
        raise QualificationError(f"attachment inventory differs; unreferenced={sorted(actual - referenced)}, missing={sorted(referenced - actual)}")
    return facts


def inspect_kit(candidate_dir: Path, kit_dir: Path, expected_candidate_tree_sha256: str,
                expected_receipt_sha256: str | None = None, *,
                allow_unfinalized: bool = False) -> dict[str, Any]:
    kit_dir = kit_dir.resolve()
    if not kit_dir.is_dir() or kit_dir.is_symlink():
        raise QualificationError(f"kit directory is missing or unsafe: {kit_dir}")
    for child in kit_dir.iterdir():
        if child.name == "attachments":
            continue
        if child.name not in TOP_LEVEL_FILES or child.is_dir() or child.is_symlink():
            raise QualificationError(f"unrecognized or unsafe kit member: {child.name}")
    missing = [name for name in (SESSION_FILE, *RECORD_FILES) if not (kit_dir / name).is_file()]
    if missing:
        raise QualificationError(f"kit is missing required records: {missing}")

    session = read_json(kit_dir / SESSION_FILE)
    recorded_binding, environment_complete = validate_session(session)
    current_binding = bind_candidate(candidate_dir, expected_candidate_tree_sha256)
    if recorded_binding != current_binding:
        raise QualificationError("candidate drift detected after kit initialization")

    verdicts: dict[str, str | None] = {}
    human_completeness: dict[str, bool] = {}
    references: set[str] = set()
    record_facts: list[dict[str, Any]] = []
    for number in range(1, BUILD_COUNT + 1):
        name = f"build-{number:02d}.json"
        verdict, paths, human_complete = _validate_build(read_json(kit_dir / name), number)
        verdicts[name] = verdict
        human_completeness[name] = human_complete
        references.update(paths)
        record_facts.append(file_fact(kit_dir / name, name))
    for name, validator in (("recovery.json", _validate_recovery), ("performance.json", _validate_performance), ("soak.json", _validate_soak)):
        verdict, paths, human_complete = validator(read_json(kit_dir / name))
        verdicts[name] = verdict
        human_completeness[name] = human_complete
        references.update(paths)
        record_facts.append(file_fact(kit_dir / name, name))
    attachments = _attachment_inventory(kit_dir, references)

    if any(value == "fail" for value in verdicts.values()):
        status = "fail"
    elif environment_complete and all(value == "pass" for value in verdicts.values()):
        status = "pass"
    else:
        status = "pending"
    if status == "pass":
        require_candidate_eligible(
            "report Ashen Span qualification PASS", current_binding["candidate_id"]
        )
    inspection = {
        "attachments": attachments,
        "candidate": current_binding,
        "environment_complete": environment_complete,
        "human_fields_complete": environment_complete and all(human_completeness.values()),
        "records": record_facts,
        "session": file_fact(kit_dir / SESSION_FILE, SESSION_FILE),
        "session_id": session["session_id"],
        "status": status,
        "verdicts": verdicts,
    }
    receipt_path = kit_dir / FINAL_RECEIPT
    if receipt_path.exists() or receipt_path.is_symlink():
        if receipt_path.is_symlink() or not receipt_path.is_file():
            raise QualificationError(f"final receipt is unsafe: {receipt_path}")
        if expected_receipt_sha256 is None or not HEX_64_RE.fullmatch(expected_receipt_sha256.upper()):
            raise QualificationError("finalized kit audit requires the independently recorded FINAL_RECEIPT SHA-256")
        if sha256_file(receipt_path) != expected_receipt_sha256.upper():
            raise QualificationError("FINAL_RECEIPT.json SHA-256 differs from the independently recorded anchor")
        receipt = require_object(read_json(receipt_path), FINAL_RECEIPT)
        require_exact_keys(receipt, (
            "attachment_files", "candidate", "evidence_tree_sha256", "human_fields_complete",
            "record_files", "record_verdicts", "schema_version",
            "session_file", "session_id", "status",
        ), FINAL_RECEIPT)
        expected_receipt = _make_final_receipt(inspection)
        if receipt != expected_receipt:
            raise QualificationError("FINAL_RECEIPT.json no longer matches the candidate or current evidence")
        if receipt_path.read_bytes() != canonical_json_bytes(receipt):
            raise QualificationError("FINAL_RECEIPT.json is not canonical")
        inspection["finalized"] = True
        inspection["final_receipt"] = file_fact(receipt_path, FINAL_RECEIPT)
    else:
        if expected_receipt_sha256 is not None:
            raise QualificationError("FINAL_RECEIPT.json is missing for the supplied receipt SHA-256 anchor")
        if not allow_unfinalized:
            raise QualificationError("unfinalized kit audit requires explicit unfinalized mode")
        inspection["finalized"] = False
        inspection["final_receipt"] = None
    return inspection


def _evidence_tree_sha256(inspection: dict[str, Any]) -> str:
    entries = [inspection["session"], *inspection["records"], *inspection["attachments"]]
    digest = hashlib.sha256()
    for fact in sorted(entries, key=lambda item: item["file"]):
        digest.update(fact["file"].encode("utf-8"))
        digest.update(b"\0")
        digest.update(fact["sha256"].encode("ascii"))
        digest.update(b"\n")
    return digest.hexdigest().upper()


def _make_final_receipt(inspection: dict[str, Any]) -> dict[str, Any]:
    return {
        "attachment_files": inspection["attachments"],
        "candidate": inspection["candidate"],
        "evidence_tree_sha256": _evidence_tree_sha256(inspection),
        "human_fields_complete": inspection["human_fields_complete"],
        "record_files": inspection["records"],
        "record_verdicts": inspection["verdicts"],
        "schema_version": SCHEMA_VERSION,
        "session_file": inspection["session"],
        "session_id": inspection["session_id"],
        "status": inspection["status"],
    }


def finalize_kit(candidate_dir: Path, kit_dir: Path,
                 expected_candidate_tree_sha256: str) -> dict[str, Any]:
    kit_dir = kit_dir.resolve()
    receipt_path = kit_dir / FINAL_RECEIPT
    if receipt_path.exists() or receipt_path.is_symlink():
        raise QualificationError(f"refusing to overwrite {receipt_path}")
    inspection = inspect_kit(candidate_dir, kit_dir, expected_candidate_tree_sha256,
                             allow_unfinalized=True)
    require_candidate_eligible(
        "finalize Ashen Span qualification", inspection["candidate"]["candidate_id"]
    )
    receipt = _make_final_receipt(inspection)
    write_canonical(receipt_path, receipt, exclusive=True)
    return receipt


def _default_session_id(timestamp: str) -> str:
    return "ashen-span-" + timestamp.replace("-", "").replace(":", "")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    init = subparsers.add_parser("init", help="create a new candidate-bound pending kit")
    init.add_argument("--candidate-dir", type=Path, required=True)
    init.add_argument("--candidate-tree-sha256", required=True,
                      help="candidate tree hash printed by the independent verifier")
    init.add_argument("--output-dir", type=Path, required=True)
    init.add_argument("--created-utc", default=None, help="UTC timestamp; defaults to current UTC")
    init.add_argument("--session-id", default=None)
    status = subparsers.add_parser("status", help="validate a kit without writing")
    status.add_argument("--candidate-dir", type=Path, required=True)
    status.add_argument("--candidate-tree-sha256", required=True,
                        help="candidate tree hash printed by the independent verifier")
    status.add_argument("--kit-dir", type=Path, required=True)
    status_mode = status.add_mutually_exclusive_group(required=True)
    status_mode.add_argument("--unfinalized", action="store_true",
                             help="explicitly audit a kit that has never been finalized")
    status_mode.add_argument("--receipt-sha256",
                             help="audit a finalized kit using the hash printed by finalize")
    final = subparsers.add_parser("finalize", help="write one immutable final receipt")
    final.add_argument("--candidate-dir", type=Path, required=True)
    final.add_argument("--candidate-tree-sha256", required=True,
                       help="candidate tree hash printed by the independent verifier")
    final.add_argument("--kit-dir", type=Path, required=True)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        if args.command == "init":
            created = args.created_utc or now_utc()
            session_id = args.session_id or _default_session_id(created)
            result = initialize_kit(args.candidate_dir, args.output_dir, session_id, created,
                                    args.candidate_tree_sha256)
            print(f"INITIALIZED {args.output_dir} status=pending candidate={result['candidate_id']} session={session_id}")
            return 0
        if args.command == "status":
            result = inspect_kit(args.candidate_dir, args.kit_dir,
                                 args.candidate_tree_sha256, args.receipt_sha256,
                                 allow_unfinalized=args.unfinalized)
            print(f"STATUS {result['status']} candidate={result['candidate']['candidate_id']} session={result['session_id']}")
            return {"pass": 0, "pending": 2, "fail": 3}[result["status"]]
        receipt = finalize_kit(args.candidate_dir, args.kit_dir,
                               args.candidate_tree_sha256)
        receipt_sha256 = sha256_file(args.kit_dir / FINAL_RECEIPT)
        print(f"FINALIZED {args.kit_dir / FINAL_RECEIPT} status={receipt['status']} candidate={receipt['candidate']['candidate_id']} sha256={receipt_sha256}")
        return {"pass": 0, "pending": 2, "fail": 3}[receipt["status"]]
    except QualificationError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
