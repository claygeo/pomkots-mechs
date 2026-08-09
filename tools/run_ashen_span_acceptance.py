#!/usr/bin/env python3
"""Run the unshipped Ashen Span probe on an exclusive candidate-world copy."""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import queue
import shutil
import stat
import subprocess
import sys
import threading
import time
from typing import Any
import zipfile

# Direct `python tools/run_ashen_span_acceptance.py` execution otherwise places
# only tools/ on sys.path.  Insert the repository root before package imports.
REPO_ROOT = Path(__file__).resolve().parents[1]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from tools.ashen_span_world import anvil
from tools.ashen_span_world.builder import runtime_tree_digest
from tools.ashen_span_qualification import (
    QualificationError as CandidateBindingError,
    bind_candidate as bind_candidate_tree,
)


SCHEMA_VERSION = 1
CANDIDATE_ID = "operation-ashen-span-mp25-rc5"
SOURCE_COMMIT = "d96b7b84688e925f311849d7c40f72a4f8a691c2"
RC5_CANDIDATE_TREE_SHA256 = "1E284A0555A421E5084B2A9425B70AA573C2ADBB382E0CCA8A6A3E8B012B38DB"
RC6_CANDIDATE_ID = "operation-ashen-span-mp25-rc6"
MP25_NAME = "pomkotsmechs-forge-0.0.1-alpha.7-mp.25.jar"
# Frozen historical diagnostic binding only. This pre-erratum artifact predates
# the deterministic checkout policy and must not be treated as a future RC6 runtime anchor.
RC6_RUNTIME_SOURCE_COMMIT = "5a35ec3d9a69fdd4d88ed7a0b21b28bc1b18ecfb"
RC6_MP25_SHA256 = "0263191d695c2cbb136b883cc62daf1354c1b2013ffcceddde54ce9dd63600f4"
RC6_MP25_BYTES = 10_704_165
MISSION_ID = "operation_ashen_span"
MAP_ID = "cold_ruin_sector_01"
MISSION_SEED = 4707185498036326465
FORGE_VERSION = "1.20.1-47.3.3"
FORGE_LIBRARIES_SHA256 = "1159a5bc02501e3971b397576f979b78e510f7defefd7988611e5b1e0637a0bf"
JAVA_SHA256 = "b3afe83e1ab067da4c56f1a7b2ba4c14ec832d694333f35b2b45178e9ac596ef"
# Set after the final remapQualificationJar build.  Keeping the value in source
# makes a substituted probe fail before Java is started.
PROBE_SHA256 = "fb12d93072068cbcf4201cbcc7bff211fe9b4571f91b70da34ae1ea26cb65b74"
PROBE_TOKEN = b"ashen_span_qualification"
EXPECTED_CHUNKS = {(x, z) for x in range(-17, 17) for z in range(-10, 10)}
MAX_OVERLAY_BYTES = 256 * 1024 * 1024
WORLD_CLAIM = "runtime probe only; external runner verifies exact 680-full-chunk inventory"
EXPECTED_PHASE_ORDER = [
    "DROP_DECK", "FREIGHT_CANYON", "WEST_SPAN", "EAST_SPAN",
    "GATEHOUSE_ESCORTS", "GATEKEEPER", "POWER_DECK",
]
BUILD_NAMES = ["Vanguard", "Siege", "Skirmisher", "Artillery", "Duelist", "Trooper"]
EXPECTED_CANDIDATE_FILES = {
    "ASHEN_SPAN_LIVE_VALIDATION.md": (921, "1bd729b7de764ead91d3ede21f5778375e0db4ae6a53a101b449780a1bfd666d"),
    "MANIFEST.json": (10631, "558808bdab0958c7682cd19a92cb8b0c0fc83bb28a4dbbc846d5479ba38584fd"),
    "OFFLINE_BUILD_RECEIPT.json": (3656, "a9fafae5778063800ca20193f4428692e544c9efe959a50dcf1ad65c9ad6729d"),
    "ROLLBACK.md": (721, "e1280d5d0ada2c1714feaf9630889ce61caaa7f894084a1e4dcdc534b0857870"),
    "RUNBOOK.md": (2023, "c8e0958b21c1120a4b5ca01213e5200b2e817a31655bde6921c5bc8f1285772d"),
    "SHA256SUMS.txt": (969, "108c2e2e6b2828d53b479e392d4200f3f2b18171ece21b09e3f98b0ea73e1873"),
    "THIRD_PARTY_NOTICES.md": (3444, "df1b31483a918be6962a68069c95360c8d2285c188334647ce937ae239895057"),
    "cold_ruin_sector_01-mp25-world.zip": (434133, "01fa630aab605053cd7a7b9d8377cae1066fde48815d4fdf8a60049411500d87"),
    "mech-arena-0.8.0-operation-ashen-span-mp25-rc5.mrpack": (12236731, "bcdd30312061b608b1d5087573a4f51a50e1d898196ff0578f5cb2b5fd3d815b"),
    "mech-arena-operation-ashen-span-mp25-rc5-server-overlay.zip": (14510782, "251c0f41c015d4e5deaa92e2b0cecdd35399cae17f12172cc1a3bd87cd4a27dc"),
    "pomkotsmechs-forge-0.0.1-alpha.7-mp.25.jar": (10700527, "29d3295d47cb3cd6744bae98afb404e5cfc87f94e26b0556a1120a5b42744213"),
}
RECEIPT_OUTPUT_FILES = set(EXPECTED_CANDIDATE_FILES) - {
    "OFFLINE_BUILD_RECEIPT.json", "SHA256SUMS.txt",
}


class AcceptanceError(RuntimeError):
    """A fail-closed acceptance precondition or runtime failure."""


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def canonical_json(value: Any) -> str:
    return json.dumps(value, indent=2, sort_keys=True, ensure_ascii=False) + "\n"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AcceptanceError(message)


def is_link_or_reparse(path: Path) -> bool:
    try:
        return path.is_symlink() or bool(path.stat().st_file_attributes & stat.FILE_ATTRIBUTE_REPARSE_POINT)
    except AttributeError:
        return path.is_symlink()


def normalize_member(name: str) -> PurePosixPath:
    require("\\" not in name, f"archive member uses backslash: {name!r}")
    path = PurePosixPath(name)
    require(not path.is_absolute(), f"archive member is absolute: {name!r}")
    require(path.parts and all(part not in ("", ".", "..") for part in path.parts),
            f"archive member is unsafe: {name!r}")
    require(all(":" not in part for part in path.parts),
            f"archive member has Windows alternate-stream/drive syntax: {name!r}")
    return path


def safe_extract(archive: Path, destination: Path, max_bytes: int = MAX_OVERLAY_BYTES) -> list[str]:
    require(destination.exists() and destination.is_dir(), "safe-extract destination must already exist")
    require(not any(destination.iterdir()), "safe-extract destination must be empty")
    seen: set[str] = set()
    total = 0
    members: list[tuple[zipfile.ZipInfo, PurePosixPath]] = []
    with zipfile.ZipFile(archive) as source:
        for info in source.infolist():
            normalized = normalize_member(info.filename.rstrip("/"))
            folded = normalized.as_posix().casefold()
            require(folded not in seen, f"archive has duplicate/case-colliding member: {info.filename}")
            seen.add(folded)
            mode = info.external_attr >> 16
            require(not stat.S_ISLNK(mode), f"archive symlink is forbidden: {info.filename}")
            total += info.file_size
            require(total <= max_bytes, f"archive expands beyond {max_bytes} bytes")
            members.append((info, normalized))
        for info, normalized in members:
            target = destination.joinpath(*normalized.parts)
            resolved = target.resolve()
            require(destination.resolve() in resolved.parents,
                    f"archive member escapes destination: {info.filename}")
            if info.is_dir():
                target.mkdir(parents=True, exist_ok=False)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with source.open(info) as incoming, target.open("xb") as outgoing:
                    shutil.copyfileobj(incoming, outgoing)
    return sorted(path.as_posix() for _, path in members)


def verify_receipt_outputs(candidate: Path, receipt: dict[str, Any]) -> dict[str, dict[str, Any]]:
    require(receipt.get("schema_version") == 1, "candidate receipt schema mismatch")
    require(receipt.get("candidate_id") == CANDIDATE_ID, "candidate identity mismatch")
    require(receipt.get("source_commit") == SOURCE_COMMIT,
            "candidate source commit mismatch")
    outputs = receipt.get("outputs")
    require(isinstance(outputs, dict) and set(outputs) == RECEIPT_OUTPUT_FILES,
            "candidate receipt output set mismatch")
    measured: dict[str, dict[str, Any]] = {}
    for label, record in sorted(outputs.items()):
        require(isinstance(record, dict) and record.get("file") == label,
                f"candidate output record malformed: {label}")
        path = candidate / label
        require(path.is_file() and not is_link_or_reparse(path), f"candidate output missing/unsafe: {label}")
        size = path.stat().st_size
        digest = sha256_file(path)
        require(size == record.get("bytes"), f"candidate output size drift: {label}")
        require(digest.upper() == str(record.get("sha256", "")).upper(),
                f"candidate output hash drift: {label}")
        measured[label] = {"bytes": size, "sha256": digest}
    return measured


def archive_has_probe(data: bytes, depth: int = 0) -> bool:
    if PROBE_TOKEN in data:
        return True
    if depth > 2 or not data.startswith(b"PK"):
        return False
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            for info in archive.infolist():
                if PROBE_TOKEN.decode() in info.filename.casefold():
                    return True
                if info.file_size > MAX_OVERLAY_BYTES:
                    continue
                member = archive.read(info)
                if archive_has_probe(member, depth + 1):
                    return True
    except zipfile.BadZipFile:
        return False
    return False


def assert_probe_absent(candidate: Path) -> None:
    for path in sorted(candidate.iterdir()):
        require(PROBE_TOKEN.decode() not in path.name.casefold(),
                f"qualification probe appears in candidate filename: {path.name}")
        if path.is_file():
            require(not archive_has_probe(path.read_bytes()),
                    f"qualification probe appears in candidate payload: {path.name}")


def verify_candidate(candidate: Path) -> tuple[dict[str, Any], dict[str, dict[str, Any]], Path]:
    require(candidate.is_dir() and not is_link_or_reparse(candidate), "candidate directory is missing/unsafe")
    children = list(candidate.iterdir())
    names = sorted(path.name for path in children)
    require(set(names) == set(EXPECTED_CANDIDATE_FILES),
            f"candidate must be the exact sealed RC5 file set; found {names}")
    for path in children:
        require(path.is_file() and not is_link_or_reparse(path),
                f"candidate member is missing/unsafe: {path.name}")
        expected_size, expected_digest = EXPECTED_CANDIDATE_FILES[path.name]
        require(path.stat().st_size == expected_size,
                f"sealed candidate size mismatch: {path.name}")
        require(sha256_file(path) == expected_digest,
                f"sealed candidate hash mismatch: {path.name}")
    receipt_path = candidate / "OFFLINE_BUILD_RECEIPT.json"
    receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
    outputs = verify_receipt_outputs(candidate, receipt)
    manifest = json.loads((candidate / "MANIFEST.json").read_text(encoding="utf-8"))
    require(manifest.get("candidate_id") == CANDIDATE_ID, "manifest candidate identity mismatch")
    identity = manifest.get("identity")
    require(isinstance(identity, dict)
            and identity.get("source_commit") == SOURCE_COMMIT
            and identity.get("mission_id") == MISSION_ID
            and identity.get("map_id") == MAP_ID
            and identity.get("forge") == "47.3.3",
            "manifest immutable identity contract mismatch")
    expected_sum_lines = [
        f"{digest.upper()} *{name}"
        for name, (_, digest) in sorted(EXPECTED_CANDIDATE_FILES.items())
        if name != "SHA256SUMS.txt"
    ]
    actual_sum_lines = (candidate / "SHA256SUMS.txt").read_text(encoding="utf-8").splitlines()
    require(actual_sum_lines == expected_sum_lines, "candidate SHA256SUMS contract mismatch")
    overlay = candidate / "mech-arena-operation-ashen-span-mp25-rc5-server-overlay.zip"
    assert_probe_absent(candidate)
    return receipt, outputs, overlay


def verify_tree_bound_rc6(candidate: Path, expected_tree_sha256: str
                          ) -> tuple[dict[str, Any], dict[str, dict[str, Any]], Path, dict[str, str]]:
    """Bind RC6 without embedding hashes produced only after source freeze.

    The domain-separated tree digest is recorded independently after the
    deterministic candidate build and supplied on the command line. It binds
    every physical byte, while the checks below independently bind the locked
    identity, runtime lineage, and the same production JAR in all containers.
    """
    try:
        binding = bind_candidate_tree(candidate, expected_tree_sha256)
    except CandidateBindingError as exc:
        raise AcceptanceError(str(exc)) from exc
    require(binding["candidate_id"] == RC6_CANDIDATE_ID,
            "--candidate-tree-sha256 acceptance is reserved for the new RC6 candidate")
    manifest = json.loads((candidate / "MANIFEST.json").read_text(encoding="utf-8"))
    receipt = json.loads((candidate / "OFFLINE_BUILD_RECEIPT.json").read_text(encoding="utf-8"))
    identity = manifest.get("identity")
    require(isinstance(identity, dict), "RC6 manifest identity is malformed")
    source_commit = identity.get("source_commit")
    runtime_source_commit = identity.get("runtime_source_commit")
    require(isinstance(source_commit, str) and len(source_commit) == 40,
            "RC6 manifest source_commit is malformed")
    require(runtime_source_commit == RC6_RUNTIME_SOURCE_COMMIT,
            "RC6 manifest runtime_source_commit is not the reviewed production-fix commit")
    require(receipt.get("source_commit") == source_commit
            and receipt.get("runtime_source_commit") == runtime_source_commit,
            "RC6 manifest/receipt source lineage differs")

    inventory = {record["file"]: record for record in binding["inventory"]}
    output_names = set(inventory) - {"OFFLINE_BUILD_RECEIPT.json", "SHA256SUMS.txt"}
    outputs = receipt.get("outputs")
    require(isinstance(outputs, dict) and set(outputs) == output_names,
            "RC6 receipt output inventory differs from its exact 11-file tree")
    measured: dict[str, dict[str, Any]] = {}
    for name in sorted(output_names):
        record = outputs[name]
        physical = inventory[name]
        require(isinstance(record, dict) and record.get("file") == name
                and record.get("bytes") == physical["bytes"]
                and str(record.get("sha256", "")).upper() == physical["sha256"],
                f"RC6 receipt output differs from physical tree: {name}")
        measured[name] = {"bytes": physical["bytes"], "sha256": physical["sha256"].lower()}

    jar_record = inventory[MP25_NAME]
    jar_sha = jar_record["sha256"]
    require(jar_sha.lower() == RC6_MP25_SHA256 and jar_record["bytes"] == RC6_MP25_BYTES,
            "RC6 physical production JAR is not the reviewed runtime artifact")
    lineage = manifest.get("lineage")
    require(isinstance(lineage, dict), "RC6 manifest lineage is malformed")
    lineage_anchors = lineage.get("runtime_anchors")
    receipt_anchors = receipt.get("runtime_anchors")
    fixed_inputs = receipt.get("fixed_inputs")
    require(isinstance(lineage_anchors, dict)
            and str(lineage_anchors.get("mp25_sha256", "")).upper() == jar_sha,
            "RC6 manifest production JAR anchor differs from the physical JAR")
    require(isinstance(receipt_anchors, dict)
            and str(receipt_anchors.get("mp25_sha256", "")).upper() == jar_sha,
            "RC6 receipt production JAR anchor differs from the physical JAR")
    require(isinstance(fixed_inputs, dict)
            and str(fixed_inputs.get("mp25_sha256", "")).upper() == jar_sha,
            "RC6 fixed-input production JAR anchor differs from the physical JAR")

    primary = binding["primary_artifacts"]
    overlay_name = primary["server_overlay"]["file"]
    mrpack_name = primary["client_mrpack"]["file"]
    for archive_name, member in (
        (overlay_name, f"mods/{MP25_NAME}"),
        (mrpack_name, f"overrides/mods/{MP25_NAME}"),
    ):
        with zipfile.ZipFile(candidate / archive_name) as archive:
            matches = [info for info in archive.infolist() if info.filename == member]
            require(len(matches) == 1 and not matches[0].is_dir(),
                    f"RC6 {archive_name} does not contain exactly one {member}")
            require(hashlib.sha256(archive.read(matches[0])).hexdigest().upper() == jar_sha,
                    f"RC6 {archive_name} embeds a different production JAR")
    assert_probe_absent(candidate)
    return receipt, measured, candidate / overlay_name, {
        "candidate_id": RC6_CANDIDATE_ID,
        "candidate_tree_sha256": binding["candidate_tree_sha256"],
        "source_commit": source_commit,
        "runtime_source_commit": runtime_source_commit,
    }


def verify_probe(probe: Path) -> dict[str, Any]:
    require(probe.is_file() and not is_link_or_reparse(probe), "probe JAR is missing/unsafe")
    require(archive_has_probe(probe.read_bytes()), "probe JAR does not contain its qualification identity")
    digest = sha256_file(probe)
    require(digest == PROBE_SHA256,
            f"qualification probe hash mismatch: {digest}; expected {PROBE_SHA256}")
    with zipfile.ZipFile(probe) as archive:
        names = set(archive.namelist())
        require("META-INF/mods.toml" in names, "probe JAR has no Forge metadata")
        require("ashen_span_qualification.mixins.json" in names, "probe JAR has no accessor mixin config")
    return {"bytes": probe.stat().st_size, "sha256": digest}


def copy_forge_runtime(source: Path, destination: Path) -> str:
    libraries = source / "libraries"
    args = libraries / "net" / "minecraftforge" / "forge" / FORGE_VERSION / "win_args.txt"
    require(args.is_file(), f"Forge {FORGE_VERSION} win_args.txt is missing")
    require(not is_link_or_reparse(source), "Forge runtime root is a link/reparse point")
    digest = runtime_tree_digest(libraries)
    require(digest == FORGE_LIBRARIES_SHA256,
            f"Forge libraries hash mismatch: {digest}; expected {FORGE_LIBRARIES_SHA256}")
    shutil.copytree(libraries, destination / "libraries", copy_function=shutil.copy2)
    copied = runtime_tree_digest(destination / "libraries")
    require(copied == digest, "private Forge runtime copy changed bytes")
    return digest


def write_server_properties(path: Path) -> None:
    values: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        if raw and not raw.startswith("#") and "=" in raw:
            key, value = raw.split("=", 1)
            values[key] = value
    values.update({
        "enable-query": "false",
        "enable-rcon": "false",
        "enable-status": "false",
        "enforce-secure-profile": "false",
        "enforce-whitelist": "false",
        "level-name": "saves/cold_ruin_sector_01",
        "max-players": "1",
        "online-mode": "false",
        "server-ip": "127.0.0.1",
        "server-port": "0",
        "white-list": "false",
    })
    path.write_text("\n".join(f"{key}={values[key]}" for key in sorted(values)) + "\n",
                    encoding="utf-8", newline="\n")


def write_fml_config(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        "# Acceptance runtime: deterministic Forge globals; network version checks are forbidden.\n"
        "earlyWindowHeight = 480\n"
        "versionCheck = false\n"
        "earlyWindowControl = true\n"
        "earlyWindowFBScale = 1\n"
        "earlyWindowProvider = \"fmlearlywindow\"\n"
        "earlyWindowWidth = 854\n"
        "earlyWindowMaximized = false\n"
        "defaultConfigPath = \"defaultconfigs\"\n"
        "disableOptimizedDFU = true\n"
        "earlyWindowSkipGLVersions = []\n"
        "maxThreads = -1\n"
        "earlyWindowSquir = false\n"
        "earlyWindowShowCPU = false\n",
        encoding="utf-8", newline="\n")


def world_inventory(world: Path) -> dict[str, Any]:
    entries = anvil.inventory(world / "region")
    coords = set(entries)
    missing = sorted(EXPECTED_CHUNKS - coords)
    extra = sorted(coords - EXPECTED_CHUNKS)
    not_full = sorted(coord for coord, entry in entries.items() if anvil.status(entry) != "full")
    require(not missing and not extra and not not_full,
            f"world inventory mismatch missing={missing[:8]} extra={extra[:8]} not_full={not_full[:8]}")
    rows = [f"{x},{z},full" for x, z in sorted(coords)]
    region_hashes = {
        path.name: sha256_file(path) for path in sorted((world / "region").glob("r.*.*.mca"))
    }
    return {
        "chunk_count": len(coords),
        "full_chunk_count": len(coords),
        "inventory_sha256": hashlib.sha256(("\n".join(rows) + "\n").encode()).hexdigest(),
        "bounds": {"min_x": -17, "max_x": 16, "min_z": -10, "max_z": 9},
        "region_files": region_hashes,
        "world_bytes": sum(path.stat().st_size for path in world.rglob("*") if path.is_file()),
    }


def post_world_inventory(world: Path, expected_proto_sha256: str) -> dict[str, Any]:
    entries = anvil.inventory(world / "region")
    coords = set(entries)
    missing = sorted(EXPECTED_CHUNKS - coords)
    contract_not_full = sorted(
        coord for coord in EXPECTED_CHUNKS if coord in entries and anvil.status(entries[coord]) != "full")
    require(not missing and not contract_not_full,
            f"post-run contract chunks changed missing={missing[:8]} not_full={contract_not_full[:8]}")
    proto = sorted(coords - EXPECTED_CHUNKS)
    extra_full = [coord for coord in proto if anvil.status(entries[coord]) == "full"]
    require(not extra_full, f"post-run generated forbidden extra FULL chunks: {extra_full[:8]}")
    proto_rows = [f"{x},{z},{anvil.status(entries[(x, z)])}" for x, z in proto]
    proto_sha = hashlib.sha256(("\n".join(proto_rows) + ("\n" if proto_rows else "")).encode()).hexdigest()
    require(len(expected_proto_sha256) == 64
            and all(char in "0123456789abcdefABCDEF" for char in expected_proto_sha256),
            "--post-proto-sha256 must be 64 hexadecimal characters")
    require(proto_sha == expected_proto_sha256.lower(),
            f"post-run proto inventory mismatch: {proto_sha}; expected {expected_proto_sha256.lower()}")
    status_counts: dict[str, int] = {}
    for coord in proto:
        status = anvil.status(entries[coord])
        status_counts[status] = status_counts.get(status, 0) + 1
    proto_bounds = None if not proto else {
        "min_x": min(x for x, _ in proto), "max_x": max(x for x, _ in proto),
        "min_z": min(z for _, z in proto), "max_z": max(z for _, z in proto),
    }
    return {
        "chunk_count": len(coords),
        "contract_full_chunk_count": len(EXPECTED_CHUNKS),
        "extra_full_chunk_count": 0,
        "proto_chunk_count": len(proto),
        "proto_status_counts": dict(sorted(status_counts.items())),
        "proto_bounds": proto_bounds,
        "proto_inventory_sha256": proto_sha,
        "region_files": {
            path.name: sha256_file(path) for path in sorted((world / "region").glob("r.*.*.mca"))
        },
        "world_bytes": sum(path.stat().st_size for path in world.rglob("*") if path.is_file()),
    }


def exact_keys(value: dict[str, Any], expected: set[str], label: str) -> None:
    actual = set(value)
    require(actual == expected,
            f"{label} fields mismatch missing={sorted(expected - actual)} extra={sorted(actual - expected)}")


def validate_probe_result(data: Any, build_limit: int, candidate_id: str,
                          source_commit: str = SOURCE_COMMIT,
                          runtime_source_commit: str = SOURCE_COMMIT) -> None:
    require(isinstance(data, dict), "probe result root must be an object")
    require(data.get("status") == "PASS",
            "probe reported failure "
            f"{data.get('failure_code', 'UNKNOWN')}: {data.get('failure_message', '')}")
    exact_keys(data, {
        "schema_version", "probe_version", "candidate_id", "source_commit", "runtime_source_commit",
        "mission_id", "map_id", "seed", "status", "total_ticks", "builds",
        "build_limit", "all_six_builds", "safety_chunks", "world_claim",
    }, "probe result")
    expected_scalars = {
        "schema_version": 2,
        "probe_version": "1.1.0",
        "candidate_id": candidate_id,
        "source_commit": source_commit,
        "runtime_source_commit": runtime_source_commit,
        "mission_id": MISSION_ID,
        "map_id": MAP_ID,
        "seed": MISSION_SEED,
        "status": "PASS",
        "build_limit": build_limit,
        "all_six_builds": build_limit == 6,
        "safety_chunks": 680,
        "world_claim": WORLD_CLAIM,
    }
    for field, expected in expected_scalars.items():
        require(data[field] == expected and type(data[field]) is type(expected),
                f"probe {field} mismatch: {data[field]!r}; expected {expected!r}")
    require(type(data["total_ticks"]) is int and 0 < data["total_ticks"] <= 18_000,
            "probe total_ticks is outside the bounded run contract")
    builds = data["builds"]
    require(isinstance(builds, list) and len(builds) == build_limit,
            f"probe build ledger must contain exactly {build_limit} builds")
    build_fields = {
        "build", "name", "command_path", "brigadier_start", "brigadier_retry",
        "initial_template", "retry_template",
        "phase_order", "normal_roots", "gatekeeper_entity", "span_warden_entity",
        "pmb04_hitbox_lineage",
        "service_template_restored", "service_resources_degraded", "root_ownership_metadata",
        "staged_socket_contract", "gatekeeper_uuid_continuity",
        "ownership_registry_empty", "ownership_secondary_indexes_empty", "retry_gate_restore",
        "retry_mutation_restore", "retry_exact_zero", "final_gate_restore", "mission_gate_ledger_empty",
        "forced_chunks_restored", "victory_outcome", "exact_zero",
        "player_dismounted", "player_position_restore", "player_restore",
        "durable_restore_cleared", "ticks",
    }
    true_fields = {
        "brigadier_start", "brigadier_retry", "initial_template", "retry_template",
        "service_template_restored", "service_resources_degraded",
        "root_ownership_metadata", "pmb04_hitbox_lineage", "staged_socket_contract",
        "gatekeeper_uuid_continuity", "ownership_registry_empty",
        "ownership_secondary_indexes_empty", "retry_gate_restore", "final_gate_restore",
        "retry_mutation_restore", "retry_exact_zero", "mission_gate_ledger_empty", "forced_chunks_restored",
        "victory_outcome", "exact_zero", "player_dismounted",
        "player_position_restore", "player_restore", "durable_restore_cleared",
    }
    for index, record in enumerate(builds, start=1):
        require(isinstance(record, dict), f"probe build {index} must be an object")
        exact_keys(record, build_fields, f"probe build {index}")
        require(type(record["build"]) is int and record["build"] == index,
                f"probe build order mismatch at {index}")
        require(record["name"] == BUILD_NAMES[index - 1],
                f"probe build {index} name mismatch")
        require(record["command_path"] == "arena solo start",
                f"probe build {index} did not bind the production command path")
        require(record["phase_order"] == EXPECTED_PHASE_ORDER,
                f"probe build {index} phase order mismatch")
        require(type(record["normal_roots"]) is int and record["normal_roots"] == 15,
                f"probe build {index} normal roster mismatch")
        require(record["gatekeeper_entity"] == "pomkotsmechs:arena_rival_pmvc01",
                f"probe build {index} Gatekeeper identity mismatch")
        require(record["span_warden_entity"] == "pomkotsmechs:pmb04",
                f"probe build {index} Span Warden identity mismatch")
        for field in true_fields:
            require(record[field] is True,
                    f"probe build {index} {field} was not exactly true")
        require(type(record["ticks"]) is int and record["ticks"] > 0,
                f"probe build {index} ticks must be a positive integer")
    require(sum(record["ticks"] for record in builds) <= data["total_ticks"],
            "probe per-build elapsed ticks exceed total_ticks")


def validate_console_log(console: str, build_limit: int) -> None:
    required = [
        "ASHEN_SPAN_QUALIFICATION_ARMED",
        "ASHEN_SPAN_QUALIFICATION_MAP_PASS chunks=680",
        "ASHEN_SPAN_QUALIFICATION_PASS",
        "Global Forge version check system disabled, no further processing.",
    ]
    required.extend(
        f"ASHEN_SPAN_QUALIFICATION_BUILD_PASS build={index} name={BUILD_NAMES[index - 1]}"
        for index in range(1, build_limit + 1)
    )
    for marker in required:
        require(console.count(marker) == 1,
                f"server console must contain exactly one marker: {marker}")
    require(console.count("SOLO VICTORY") == build_limit,
            f"server console must bind each PMB04 retirement to one victory; found "
            f"{console.count('SOLO VICTORY')} for {build_limit} builds")
    require("SOLO DEFEAT" not in console,
            "server console contradicts PASS with a SOLO DEFEAT outcome")
    require("SOLO RUN ABORTED" not in console,
            "server console contradicts PASS with a SOLO RUN ABORTED outcome")
    require("ASHEN_SPAN_QUALIFICATION_FAIL" not in console,
            "server console contradicts PASS with a qualification FAIL marker")
    require("ASHEN_SPAN_QUALIFICATION_EXCEPTION" not in console,
            "server console contradicts PASS with a qualification exception marker")
    require("Starting version check" not in console,
            "qualification runtime attempted an outbound Forge version check")


def java_command(java: Path, output: Path, candidate_id: str, build_limit: int = 6,
                 source_commit: str = SOURCE_COMMIT,
                 runtime_source_commit: str = SOURCE_COMMIT) -> list[str]:
    return [
        str(java), "-Xms1G", "-Xmx4G",
        "-DashenSpan.qualification=true",
        f"-DashenSpan.qualification.output={output}",
        f"-DashenSpan.qualification.candidate={candidate_id}",
        f"-DashenSpan.qualification.sourceCommit={source_commit}",
        f"-DashenSpan.qualification.runtimeSourceCommit={runtime_source_commit}",
        f"-DashenSpan.qualification.buildLimit={build_limit}",
        f"@libraries/net/minecraftforge/forge/{FORGE_VERSION}/win_args.txt",
        "nogui",
    ]


def run_server(runtime: Path, command: list[str], console_log: Path, timeout: int) -> int:
    process = subprocess.Popen(
        command, cwd=runtime, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace", bufsize=1)
    lines: queue.Queue[str] = queue.Queue()

    def capture() -> None:
        assert process.stdout is not None
        with console_log.open("x", encoding="utf-8", newline="\n") as stream:
            for line in process.stdout:
                clean = line.rstrip("\r\n")
                stream.write(clean + "\n")
                stream.flush()
                lines.put(clean)

    thread = threading.Thread(target=capture, name="ashen-span-acceptance-log", daemon=True)
    thread.start()
    deadline = time.monotonic() + timeout
    while process.poll() is None and time.monotonic() < deadline:
        try:
            line = lines.get(timeout=0.25)
            if "ASHEN_SPAN_QUALIFICATION_FAIL" in line:
                # The probe writes evidence and requests a clean halt; continue to
                # the normal exit instead of racing its server-thread cleanup.
                pass
        except queue.Empty:
            pass
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=15)
        thread.join(timeout=5)
        raise AcceptanceError(f"qualification server exceeded {timeout}s and was terminated")
    thread.join(timeout=10)
    require(not thread.is_alive(), "qualification log capture did not terminate")
    return int(process.returncode)


def prepare_runtime(candidate: Path, overlay: Path, forge_runtime: Path,
                    probe: Path, session: Path) -> tuple[Path, dict[str, Any]]:
    runtime = session / "server"
    runtime.mkdir()
    safe_extract(overlay, runtime)
    forge_digest = copy_forge_runtime(forge_runtime, runtime)
    require((runtime / "mods").is_dir(), "server overlay has no mods directory")
    require(not any(PROBE_TOKEN.decode() in path.name.casefold() for path in (runtime / "mods").iterdir()),
            "server overlay already contains a qualification probe")
    probe_target = runtime / "mods" / "ashen-span-qualification-probe.jar"
    shutil.copy2(probe, probe_target)
    (runtime / "eula.txt").write_text("eula=true\n", encoding="utf-8", newline="\n")
    write_server_properties(runtime / "server.properties")
    write_fml_config(runtime / "config" / "fml.toml")
    return runtime, {
        "forge_libraries_sha256": forge_digest,
        "overlay_sha256": sha256_file(overlay),
        "probe_copy_sha256": sha256_file(probe_target),
    }


def remove_private_runtime(runtime: Path, session: Path) -> None:
    require(runtime.resolve().parent == session.resolve() and runtime.name == "server",
            f"refusing to remove runtime outside its exclusive session: {runtime}")
    require(runtime.is_dir() and not is_link_or_reparse(runtime),
            "private runtime is missing or became a link/reparse point")
    for child in runtime.rglob("*"):
        require(not is_link_or_reparse(child),
                f"private runtime contains a link/reparse point: {child}")
    shutil.rmtree(runtime)
    require(not runtime.exists(), "private runtime cleanup did not finish")


def require_disjoint_paths(first: Path, second: Path, label: str) -> None:
    first = first.resolve()
    second = second.resolve()
    require(
        first != second and first not in second.parents and second not in first.parents,
        f"{label} paths must be disjoint",
    )


def bind_candidate_for_run(candidate: Path, expected_tree_sha256: str | None) -> tuple[
        dict[str, Any], dict[str, dict[str, Any]], Path, dict[str, str]]:
    if expected_tree_sha256:
        return verify_tree_bound_rc6(candidate, expected_tree_sha256)
    receipt, candidate_outputs, overlay = verify_candidate(candidate)
    return receipt, candidate_outputs, overlay, {
        "candidate_id": CANDIDATE_ID,
        "candidate_tree_sha256": RC5_CANDIDATE_TREE_SHA256,
        "source_commit": SOURCE_COMMIT,
        "runtime_source_commit": SOURCE_COMMIT,
    }


def require_candidate_unchanged(
        candidate: Path, expected_tree_sha256: str | None,
        initial_binding: tuple[dict[str, Any], dict[str, dict[str, Any]], Path, dict[str, str]],
) -> None:
    final_binding = bind_candidate_for_run(candidate, expected_tree_sha256)
    require(final_binding == initial_binding, "candidate changed during the acceptance run")


def run(args: argparse.Namespace) -> dict[str, Any]:
    repo = Path(__file__).resolve().parents[1]
    build_root = (repo / "build" / "ashen-span-acceptance").resolve()
    session = Path(args.output).resolve()
    candidate = Path(args.candidate).resolve()
    require(build_root in session.parents, f"output must be a new directory under {build_root}")
    require_disjoint_paths(candidate, session, "candidate and acceptance output")
    require(not session.exists(), f"refusing existing acceptance output: {session}")
    session.parent.mkdir(parents=True, exist_ok=True)
    session.mkdir()
    evidence = session / "evidence"
    evidence.mkdir()

    forge_runtime = Path(args.forge_runtime).resolve()
    probe = Path(args.probe_jar).resolve()
    java = Path(args.java).resolve()
    initial_binding = bind_candidate_for_run(candidate, args.candidate_tree_sha256)
    receipt, candidate_outputs, overlay, candidate_profile = initial_binding
    probe_identity = verify_probe(probe)
    require(java.is_file() and not is_link_or_reparse(java), "Java executable is missing/unsafe")
    java_digest = sha256_file(java)
    require(java_digest == args.java_sha256.lower(),
            f"Java hash mismatch: {java_digest}; expected {args.java_sha256.lower()}")

    runtime, runtime_identity = prepare_runtime(candidate, overlay, forge_runtime, probe, session)
    world = runtime / "saves" / "cold_ruin_sector_01"
    before = world_inventory(world)
    before_path = evidence / "WORLD_BEFORE.json"
    before_path.write_text(canonical_json(before), encoding="utf-8", newline="\n")
    probe_result = evidence / "PROBE_RESULT.json"
    console_log = evidence / "SERVER_CONSOLE.log"
    command = java_command(
        java, probe_result, candidate_profile["candidate_id"], args.build_limit,
        candidate_profile["source_commit"], candidate_profile["runtime_source_commit"])
    exit_code = run_server(runtime, command, console_log, args.timeout)
    require(exit_code == 0, f"qualification server exited {exit_code}")
    require(probe_result.is_file(), "qualification server produced no probe JSON")
    probe_data = json.loads(probe_result.read_text(encoding="utf-8"))
    validate_probe_result(
        probe_data, args.build_limit, candidate_profile["candidate_id"],
        candidate_profile["source_commit"], candidate_profile["runtime_source_commit"])
    console_text = console_log.read_text(encoding="utf-8")
    validate_console_log(console_text, args.build_limit)
    canonical_probe_result = evidence / "PROBE_RESULT_CANONICAL.json"
    canonical_probe_result.write_text(canonical_json(probe_data), encoding="utf-8", newline="\n")
    after = post_world_inventory(world, args.post_proto_sha256)
    after_path = evidence / "WORLD_AFTER.json"
    after_path.write_text(canonical_json(after), encoding="utf-8", newline="\n")
    require(before["chunk_count"] == after["contract_full_chunk_count"] == 680,
            "bounded 680-FULL terrain contract changed")
    require(after["extra_full_chunk_count"] == 0,
            "post-run world contains generated FULL terrain outside the safety envelope")
    latest_log = runtime / "logs" / "latest.log"
    require(latest_log.is_file(), "Forge latest.log is missing")
    evidence_latest = evidence / "latest.log"
    shutil.copy2(latest_log, evidence_latest)
    require_candidate_unchanged(candidate, args.candidate_tree_sha256, initial_binding)
    assert_probe_absent(candidate)

    result = {
        "schema_version": SCHEMA_VERSION,
        "candidate_id": receipt["candidate_id"],
        "source_commit": receipt["source_commit"],
        "runtime_source_commit": candidate_profile["runtime_source_commit"],
        "candidate_tree_sha256": candidate_profile["candidate_tree_sha256"],
        "status": "PASS",
        "build_limit": args.build_limit,
        "server_exit_code": exit_code,
        "candidate_outputs": candidate_outputs,
        "runtime": runtime_identity | {"java_sha256": java_digest},
        "probe": probe_identity,
        "probe_absent_from_candidate": True,
        "world_before": before,
        "world_after": after,
        "evidence": {
            path.name: {"bytes": path.stat().st_size, "sha256": sha256_file(path)}
            for path in sorted(evidence.iterdir()) if path.is_file()
        },
    }
    # A PASS receipt is committed only after the disposable runtime is proven
    # confined and completely removed.  Cleanup failure therefore cannot leave
    # behind a successful receipt.
    remove_private_runtime(runtime, session)
    receipt_path = session / "ACTUAL_WORLD_ACCEPTANCE_RECEIPT.json"
    receipt_path.write_text(canonical_json(result), encoding="utf-8", newline="\n")
    return result


def parser() -> argparse.ArgumentParser:
    repo = Path(__file__).resolve().parents[1]
    default_candidate = repo.parent / "modpack" / "candidates" / "ashen-span-mp25-rc5"
    default_probe = repo / "forge" / "build" / "libs" / "ashen-span-qualification-probe.jar"
    java_home = Path(os.environ.get("JAVA_HOME", ""))
    default_java = java_home / ("bin/java.exe" if os.name == "nt" else "bin/java")
    value = argparse.ArgumentParser(description=__doc__)
    value.add_argument("--candidate", type=Path, default=default_candidate)
    value.add_argument("--candidate-tree-sha256",
                       help="independently recorded domain-separated RC6 11-file tree anchor; "
                            "omit only for immutable hardcoded RC5 debugging")
    value.add_argument("--post-proto-sha256", required=True,
                       help="independently measured exact post-run non-FULL chunk coord/status digest")
    value.add_argument("--forge-runtime", type=Path, required=True,
                       help="read-only pinned Forge 47.3.3 runtime root")
    value.add_argument("--probe-jar", type=Path, default=default_probe)
    value.add_argument("--java", type=Path, default=default_java)
    value.add_argument("--java-sha256", default=JAVA_SHA256)
    value.add_argument("--output", type=Path, required=True,
                       help="new path below build/ashen-span-acceptance")
    value.add_argument("--timeout", type=int, default=1200)
    value.add_argument("--build-limit", type=int, choices=(1, 6), default=6,
                       help="run one end-to-end scale-gate build or the complete six-build acceptance")
    return value


def main() -> int:
    args = parser().parse_args()
    try:
        result = run(args)
    except (AcceptanceError, OSError, ValueError, zipfile.BadZipFile,
            subprocess.SubprocessError, anvil.AnvilError) as exc:
        print(f"ASHEN SPAN ACCEPTANCE FAIL: {exc}", file=sys.stderr)
        return 1
    print(f"ASHEN SPAN ACCEPTANCE PASS: {len(result['candidate_outputs'])} candidate outputs verified")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
