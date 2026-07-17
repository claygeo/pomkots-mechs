"""Reproducible, isolated Forge world generation for Operation Ashen Span."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path
import queue
import shutil
import stat
import subprocess
import sys
import tempfile
import threading
import time
import zipfile

from . import anvil, nbt


WORLD_SEED = 0x415348454E535041
MAP_ID = "cold_ruin_sector_01"
MISSION_ID = "operation_ashen_span"
MAP_VERSION = 1
WORLD_DIMENSION = "minecraft:overworld"
DATA_VERSION = 3465
WORLD_OUTPUT_STEM = f"{MAP_ID}_mp25_world"
WORLD_ARCHIVE_ROOT = MAP_ID
SPAWN = (-160, 83, 0)
SPAWN_ANGLE = 0.0
DAY_TIME = 11000
SAFETY_X = range(-17, 17)
SAFETY_Z = range(-10, 10)
CONTENT_X = range(-11, 11)
CONTENT_Z = range(-7, 7)
SAFETY = {(x, z) for x in SAFETY_X for z in SAFETY_Z}
CONTENT = {(x, z) for x in CONTENT_X for z in CONTENT_Z}
PROFILE_NAME = "mecharena_sector01"
GENERATION_PACK_NAME = "ashen_span_generator"
GENERATION_BIOME = "mecharena_generation:empty"
GENERATION_DATAPACK_SOURCE = Path(__file__).with_name("generation_datapack")
GENERATION_DATAPACK_FILES = (
    "pack.mcmeta",
    "data/minecraft/tags/worldgen/biome/is_overworld.json",
    "data/mecharena_generation/worldgen/biome/empty.json",
)
MARKER_FINGERPRINT = "ashen-span-v1:lodestone:blackstone:orange-east:cyan-west"
CANONICAL_ZIP_TIME = (1980, 1, 1, 0, 0, 0)
MAX_WORLD_BYTES = 32 * 1024 * 1024
MAX_WORLD_UNCOMPRESSED_BYTES = 256 * 1024 * 1024
LOST_CITIES_SHA256 = "da5ae1b0c0d0c8066f2971c9adb57d18657c4844aad6cba4f9f07f7946e30a95"
ASSET_SHA256 = "e1ac026bc07966803c5f3afcf455a0b6b7f924f4131c52600355944a5cbbefa9"
PROFILE_SHA256 = "d0a3f587d39350e0c66fd59164ce500146406ce55bd75d582912905eb5c1e868"
CONTRACT_SHA256 = "60d315c55e9382cd11dd98cc1b5c4b78e97cefe7eb7f150a17724987f109f92e"
GENERATION_DATAPACK_SHA256 = "1006df5f0363cf4c37a546fbab8199ba11b5d301d1f11116c3bc7b201e2b1fc4"
PINNED_FORGE_RUNTIME_SHA256 = "1159a5bc02501e3971b397576f979b78e510f7defefd7988611e5b1e0637a0bf"
PINNED_JAVA_SHA256 = "b3afe83e1ab067da4c56f1a7b2ba4c14ec832d694333f35b2b45178e9ac596ef"
PROOF_LOG_NAME = "one-chunk-proof.log"
FULL_LOG_NAME = "full-generation.log"

FIXED_GAME_RULES = {
    "doDaylightCycle": "false",
    "doEntityDrops": "false",
    "doFireTick": "false",
    "doInsomnia": "false",
    "doMobLoot": "false",
    "doMobSpawning": "false",
    "doPatrolSpawning": "false",
    "doTileDrops": "false",
    "doTraderSpawning": "false",
    "doWardenSpawning": "false",
    "doWeatherCycle": "false",
    "mobGriefing": "false",
    "randomTickSpeed": "0",
}

GATES = {
    "G1": (-137, -137, 82, 89, -8, 8),
    "G2": (-89, -89, 64, 78, -14, 14),
    "G3": (-32, -32, 72, 82, -13, 14),
    "G4": (24, 24, 72, 82, -13, 14),
    "G5": (72, 72, 72, 84, -8, 8),
    "R01_INTERNAL": (58, 58, 72, 84, -8, 8),
}

STAGING_SHUTTERS = {
    "P1_NORTH": (-151, -151, 83, 89, -17, -11),
    "P1_SOUTH": (-151, -151, 83, 89, 11, 17),
    "P1_CENTER": (-153, -153, 83, 89, -3, 3),
    "P2_NORTH_CARGO_LOUVER": (-113, -113, 65, 78, -28, -19),
    "P2_SOUTH_CARGO_LOUVER": (-113, -113, 65, 78, 19, 28),
    "P2_CENTER_CARGO_LOUVER": (-97, -97, 65, 78, -5, 5),
    "P3_CENTER_WINDBREAK_LOUVER": (-64, -64, 73, 78, -5, 6),
    "P4_EAST_BARRICADE": (0, 1, 73, 78, -13, 14),
    "P5_NORTH": (45, 45, 73, 82, -23, -8),
    "P5_SOUTH": (45, 45, 73, 82, 8, 23),
}

SOCKETS = (
    (-150, 83, -14), (-150, 83, 14), (-141, 83, 0),
    (-112, 65, -24), (-112, 65, 24), (-96, 65, 0),
    (-48, 54, 0), (-42, 73, -9), (-42, 73, 9),
    (16, 73, 0), (4, 73, -8), (4, 73, 8), (18, 73, 9),
    (48, 73, -18), (48, 73, 18),
    (64, 73, 0), (144, 73, 0),
)
RECOVERY_ANCHORS = (
    (-152, 83, 0), (-116, 65, 0), (-56, 73, 0), (-4, 73, 0),
    (44, 73, 0), (56, 73, 0), (128, 73, 0),
)
PLAYER_FALLBACK = (92, 73, 0)

CARGO_PODS = (
    (-124, 65, -22, 8, 5, 6), (-124, 65, 22, 8, 5, 6),
    (-108, 65, -12, 8, 5, 6), (-108, 65, 12, 8, 5, 6),
    (-96, 65, -26, 8, 5, 6), (-96, 65, 26, 8, 5, 6),
)

GENERATION_BATCHES = (
    (-17, -6, -10, 9),
    (-5, 6, -10, 9),
    (7, 16, -10, 9),
)

AIR_BLOCKS = frozenset({"minecraft:air", "minecraft:cave_air", "minecraft:void_air"})
ROUTE_SUPPORT_BLOCKS = frozenset({
    "minecraft:chiseled_polished_blackstone",
    "minecraft:cyan_concrete",
    "minecraft:gray_concrete",
    "minecraft:orange_concrete",
    "minecraft:polished_blackstone_bricks",
    "minecraft:polished_deepslate",
})
FORBIDDEN_BLOCK_FRAGMENTS = (
    "water", "lava", "spawner", "chest", "barrel", "rail", "redstone",
    "command_block", "item_frame", "armor_stand", "leaves", "sapling", "vine",
)

MARKERS = (
    (-168, 79, -23, "minecraft:lodestone", {}),
    (-167, 79, -23, "minecraft:chiseled_polished_blackstone", {}),
    (-166, 79, -23, "minecraft:orange_glazed_terracotta", {"facing": "east"}),
    (-165, 79, -23, "minecraft:cyan_glazed_terracotta", {"facing": "west"}),
)


class BuildError(RuntimeError):
    pass


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def is_sha256(value: object) -> bool:
    return (
        isinstance(value, str)
        and len(value) == 64
        and all(character in "0123456789abcdef" for character in value)
    )


def _normalize_sha256(value: str | None, label: str) -> str:
    normalized = (value or "").lower()
    if not is_sha256(normalized):
        raise BuildError(f"{label} must be an explicit 64-character SHA-256")
    return normalized


def is_link_or_reparse(path: Path) -> bool:
    try:
        metadata = path.lstat()
    except OSError:
        return False
    attributes = int(getattr(metadata, "st_file_attributes", 0))
    reparse = int(getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400))
    is_junction = getattr(path, "is_junction", lambda: False)
    return stat.S_ISLNK(metadata.st_mode) or bool(attributes & reparse) or bool(is_junction())


def runtime_tree_digest(root: Path) -> str:
    """Hash every runtime path and byte before any of it is executed."""
    if not root.is_dir() or is_link_or_reparse(root):
        raise BuildError(f"runtime tree is missing or unsafe: {root}")
    digest = hashlib.sha256(b"ashen-span-runtime-tree-v1\0")
    files = []
    seen: set[str] = set()
    for path in sorted(root.rglob("*")):
        if is_link_or_reparse(path):
            raise BuildError(f"runtime tree contains a link/reparse point: {path}")
        if path.is_dir():
            continue
        if not path.is_file():
            raise BuildError(f"runtime tree contains a non-file: {path}")
        relative = path.relative_to(root).as_posix()
        folded = relative.casefold()
        if folded in seen:
            raise BuildError(f"runtime tree paths case-collide: {relative}")
        seen.add(folded)
        files.append((relative, path))
    if not files:
        raise BuildError(f"runtime tree is empty: {root}")
    for relative, path in files:
        size = path.stat().st_size
        digest.update(f"{relative}\0{size}\0{sha256_file(path)}\n".encode("utf-8"))
    return digest.hexdigest()


def safety_digest() -> str:
    digest = hashlib.sha256()
    for x in SAFETY_X:
        for z in SAFETY_Z:
            digest.update(f"{x},{z}\n".encode("ascii"))
    return digest.hexdigest()


def generation_datapack_digest(root: Path) -> str:
    digest = hashlib.sha256()
    actual = {
        path.relative_to(root).as_posix()
        for path in root.rglob("*")
        if path.is_file()
    } if root.is_dir() else set()
    if actual != set(GENERATION_DATAPACK_FILES):
        raise BuildError(
            f"generation datapack file set drifted: extra={sorted(actual - set(GENERATION_DATAPACK_FILES))}, "
            f"missing={sorted(set(GENERATION_DATAPACK_FILES) - actual)}"
        )
    for relative in sorted(actual):
        digest.update(
            f"{relative}\0{sha256_file(root / Path(relative))}\n".encode("utf-8")
        )
    return digest.hexdigest()


def _locked_route() -> dict:
    return {
        "garage_drop_deck": {
            "bounds": {"x": [-168, -137], "z": [-23, 24]},
            "roof_y": 82,
            "player_spawn": [-160, 83, 0],
            "facing": "east",
        },
        "garage_ramp": {
            "bounds": {"x": [-136, -101], "z": [-5, 6]},
            "from_y": 82,
            "to_y": 64,
            "width": 12,
            "run": 36,
        },
        "freight_canyon": {
            "bounds": {"x": [-136, -89], "z": [-39, 40]},
            "ground_y": 64,
            "lane_width": 18,
            "minimum_overhead_clearance": 12,
        },
        "ashen_span": {
            "bounds": {"x": [-88, 23], "z": [-13, 14]},
            "deck_y": 72,
            "approach_ramp_x": [-88, -73],
            "approach_from_y": 64,
            "approach_to_y": 72,
            "trench_x": [-72, -25],
            "trench_floor_y": 48,
        },
        "service_recovery_ramp": {
            "bounds": {"x": [-88, -77], "z": [15, 55]},
            "from_y": 48,
            "to_y": 72,
            "width": 12,
        },
        "gatehouse_apron": {
            "bounds": {"x": [24, 71], "z": [-31, 32]},
            "deck_y": 72,
            "service_gantry_root": [60, 73, 28],
        },
        "reveal_corridor": {
            "bounds": {"x": [72, 79], "z": [-7, 8]},
            "deck_y": 72,
        },
        "east_power_deck": {
            "bounds": {"x": [80, 175], "z": [-47, 48]},
            "deck_y": 72,
            "clear_center": {"x": [84, 171], "z": [-43, 44]},
            "clear_center_size": [88, 88],
            "safety_apron": 4,
            "parapet_height": 3,
            "open_sky": True,
        },
    }


def _bounds_tuple(entry: dict) -> tuple[int, int, int, int, int, int]:
    bounds = entry["bounds"]
    return (*bounds["x"], *bounds["y"], *bounds["z"])


def validate_locked_contract(contract: dict) -> None:
    """Reject source drift before Forge is allowed to generate a world."""
    expected_scalars = {
        ("asset", "mod_id"): "mecharena_sector01",
        ("asset", "map_id"): MAP_ID,
        ("asset", "map_version"): MAP_VERSION,
        ("asset", "mission_id"): MISSION_ID,
        ("generation", "minecraft"): "1.20.1",
        ("generation", "loader"): "forge",
        ("generation", "lost_cities"): "7.4.13",
        ("generation", "profile"): PROFILE_NAME,
        ("generation", "dimension"): WORLD_DIMENSION,
        ("generation", "seed"): WORLD_SEED,
        ("bounds", "view_distance"): 6,
        ("bounds", "simulation_distance"): 6,
        ("bounds", "compressed_world_budget_mib"): 32,
    }
    for keys, expected in expected_scalars.items():
        value: object = contract
        for key in keys:
            if not isinstance(value, dict) or key not in value:
                raise BuildError(f"asset contract is missing {'.'.join(keys)}")
            value = value[key]
        if value != expected:
            raise BuildError(f"asset contract {'.'.join(keys)} is {value!r}, expected {expected!r}")
    if contract.get("generation", {}).get("origin") != [0, 0]:
        raise BuildError("asset contract origin is not [0,0]")
    bounds = contract.get("bounds", {})
    expected_bounds = {
        "authored_blocks": {"x": [-176, 175], "z": [-112, 111], "size": [352, 224]},
        "authored_chunks": {"x": [-11, 10], "z": [-7, 6], "size": [22, 14], "count": 308},
        "playable": {"x": [-168, 167], "z": [-56, 55]},
        "safety_chunks": {"x": [-17, 16], "z": [-10, 9], "size": [34, 20], "count": 680},
        "safety_blocks": {"x": [-272, 271], "z": [-160, 159], "size": [544, 320]},
    }
    for key, expected in expected_bounds.items():
        if bounds.get(key) != expected:
            raise BuildError(f"asset contract {key} bounds drifted from the locked mission")
    if contract.get("route") != _locked_route():
        raise BuildError("asset contract route geometry drifted from the locked mission")
    actual_gates = {entry["id"]: _bounds_tuple(entry) for entry in contract.get("gates", [])}
    if actual_gates != GATES:
        raise BuildError(f"asset contract gate geometry mismatch: {actual_gates!r}")
    actual_shutters = {
        entry["id"]: _bounds_tuple(entry) for entry in contract.get("staging_shutters", [])
    }
    if actual_shutters != STAGING_SHUTTERS:
        raise BuildError(f"asset contract staging geometry mismatch: {actual_shutters!r}")
    encounters = contract.get("encounters", [])
    sockets = tuple(tuple(socket["position"]) for encounter in encounters for socket in encounter["sockets"])
    recoveries = tuple(tuple(encounter["recovery_anchor"]) for encounter in encounters)
    if sockets != SOCKETS or recoveries != RECOVERY_ANCHORS:
        raise BuildError("asset contract sockets/recovery anchors drifted from the locked mission")
    if tuple(encounters[-1].get("player_fallback", ())) != PLAYER_FALLBACK:
        raise BuildError("asset contract player fallback drifted from the locked mission")
    marker_contract = contract.get("markers", {})
    if marker_contract.get("socket_surface") != "minecraft:cyan_concrete":
        raise BuildError("asset contract socket surface is not cyan concrete")
    if marker_contract.get("recovery_surface") != "minecraft:chiseled_polished_blackstone":
        raise BuildError("asset contract recovery surface is not chiseled polished blackstone")
    if marker_contract.get("gate_material") != "minecraft:orange_concrete":
        raise BuildError("asset contract gate material is not orange concrete")


def _assert_under(path: Path, parent: Path) -> None:
    path, parent = path.resolve(), parent.resolve()
    if path == parent or parent not in path.parents:
        raise BuildError(f"refusing destructive operation outside {parent}: {path}")


def reset_directory(path: Path, safe_parent: Path) -> None:
    _assert_under(path, safe_parent)
    if path.exists():
        shutil.rmtree(path)
    path.mkdir(parents=True)


def _validate_generation_runtime(paths: "Paths") -> tuple[str, str]:
    forge_sha = _normalize_sha256(paths.forge_runtime_sha256, "Forge runtime SHA-256")
    java_sha = _normalize_sha256(paths.java_sha256, "Java executable SHA-256")
    if paths.java is None or not paths.java.is_file() or is_link_or_reparse(paths.java):
        raise BuildError(f"pinned Java executable is missing or unsafe: {paths.java}")
    actual_java = sha256_file(paths.java)
    if actual_java != java_sha:
        raise BuildError(f"Java executable hash mismatch: {actual_java}; expected {java_sha}")
    actual_forge = runtime_tree_digest(paths.forge_runtime / "libraries")
    if actual_forge != forge_sha:
        raise BuildError(f"Forge runtime hash mismatch: {actual_forge}; expected {forge_sha}")
    return forge_sha, java_sha


def _copy_runtime(source: Path, destination: Path, expected_sha: str) -> None:
    required = source / "libraries" / "net" / "minecraftforge" / "forge" / "1.20.1-47.3.3" / "win_args.txt"
    if not required.is_file():
        raise BuildError(f"Forge 1.20.1-47.3.3 runtime is incomplete: {required}")
    # A private copy avoids any writes to the historical server-spike runtime.
    shutil.copytree(source / "libraries", destination / "libraries", copy_function=shutil.copy2)
    copied_sha = runtime_tree_digest(destination / "libraries")
    if copied_sha != expected_sha:
        raise BuildError(f"private Forge runtime copy changed bytes: {copied_sha}; expected {expected_sha}")


def _write_text(path: Path, value: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(value, encoding="utf-8", newline="\n")


def prepare_runtime(paths: "Paths") -> None:
    forge_sha, _ = _validate_generation_runtime(paths)
    reset_directory(paths.runtime, paths.work)
    _copy_runtime(paths.forge_runtime, paths.runtime, forge_sha)
    (paths.runtime / "mods").mkdir()
    shutil.copy2(paths.lost_cities, paths.runtime / "mods" / paths.lost_cities.name)
    shutil.copy2(paths.asset, paths.runtime / "mods" / paths.asset.name)
    profile_target = paths.runtime / "config" / "lostcities" / "profiles" / f"{PROFILE_NAME}.json"
    profile_target.parent.mkdir(parents=True)
    shutil.copy2(paths.profile, profile_target)
    _write_text(paths.runtime / "eula.txt", "eula=true\n")
    _write_text(paths.runtime / "defaultconfigs" / "lostcities-server.toml", _lost_cities_server_config())
    _write_text(paths.runtime / "server.properties", _server_properties())


def _lost_cities_server_config() -> str:
    return """# Operation Ashen Span generation-only Lost Cities configuration
[profiles]
specialBedBlock = "minecraft:diamond_block"
selectedProfile = "mecharena_sector01"
selectedCustomJson = ""
todoQueueSize = 100000
forceSaplingGrowth = false
cacheCleanupSeconds = 300
avoidStructures = []
avoidStructuresAdjacent = false
avoidVillagesAdjacent = false
avoidVillages = true
avoidFlattening = true
"""


def _server_properties() -> str:
    flat = (
        '{"biome":"mecharena_generation:empty","features":true,"lakes":false,'
        '"layers":[{"block":"minecraft:bedrock","height":1},'
        '{"block":"minecraft:deepslate","height":47}],"structures":{"structures":{}}}'
    )
    values = {
        "allow-flight": "true",
        "allow-nether": "false",
        "difficulty": "normal",
        "enable-command-block": "false",
        "enable-jmx-monitoring": "false",
        "enable-query": "false",
        "enable-rcon": "false",
        "enable-status": "false",
        "enforce-secure-profile": "false",
        "enforce-whitelist": "false",
        # Lost Cities is a biome feature. Minecraft's generate-structures switch
        # controls the WorldGenSettings.generate_features bit as well as vanilla
        # structures; false silently produces an empty flat Overworld. Keep the
        # feature pipeline enabled while the flat generator's structures map stays
        # empty and the pinned Lost Cities profile disables stock structures.
        "generate-structures": "true",
        "generator-settings": flat,
        "hardcore": "false",
        "level-name": "world",
        "level-seed": str(WORLD_SEED),
        "level-type": "minecraft:flat",
        "max-players": "1",
        "max-tick-time": "0",
        "motd": "Operation Ashen Span offline world builder",
        "network-compression-threshold": "256",
        "online-mode": "false",
        "pvp": "false",
        "server-ip": "127.0.0.1",
        "server-port": "0",
        "simulation-distance": "6",
        "spawn-animals": "false",
        "spawn-monsters": "false",
        "spawn-npcs": "false",
        "spawn-protection": "0",
        "sync-chunk-writes": "true",
        "use-native-transport": "false",
        "view-distance": "6",
        "white-list": "true",
    }
    return "\n".join(f"{key}={values[key]}" for key in sorted(values)) + "\n"


def prepare_world_directory(paths: "Paths") -> Path:
    world = paths.runtime / "world"
    reset_directory(world, paths.runtime)
    if not GENERATION_DATAPACK_SOURCE.is_dir():
        raise BuildError(f"generation datapack source is missing: {GENERATION_DATAPACK_SOURCE}")
    target = world / "datapacks" / GENERATION_PACK_NAME
    shutil.copytree(GENERATION_DATAPACK_SOURCE, target, copy_function=shutil.copy2)
    return world


class ForgeServer:
    def __init__(self, runtime: Path, log_path: Path, java: Path, java_sha256: str):
        self.runtime = runtime
        self.log_path = log_path
        self.java = java
        self.java_sha256 = java_sha256
        self.process: subprocess.Popen[str] | None = None
        self.lines: list[str] = []
        self._queue: queue.Queue[str] = queue.Queue()
        self._thread: threading.Thread | None = None

    def start(self, timeout: int = 300) -> None:
        if sha256_file(self.java) != self.java_sha256:
            raise BuildError("pinned Java executable changed before launch")
        args = [
            str(self.java), "-Xms1G", "-Xmx4G",
            "@libraries/net/minecraftforge/forge/1.20.1-47.3.3/win_args.txt",
            "nogui",
        ]
        self.log_path.parent.mkdir(parents=True, exist_ok=True)
        log_stream = self.log_path.open("w", encoding="utf-8", newline="\n")
        self.process = subprocess.Popen(
            args,
            cwd=self.runtime,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            bufsize=1,
        )

        def reader() -> None:
            assert self.process is not None and self.process.stdout is not None
            try:
                for line in self.process.stdout:
                    clean = line.rstrip("\r\n")
                    self.lines.append(clean)
                    log_stream.write(clean + "\n")
                    log_stream.flush()
                    self._queue.put(clean)
            finally:
                log_stream.close()

        self._thread = threading.Thread(target=reader, name="ashen-span-forge-log", daemon=True)
        self._thread.start()
        self.wait_for("Done (", timeout)
        required = ("LostCities",)
        whole_log = "\n".join(self.lines)
        missing = [token for token in required if token.lower() not in whole_log.lower()]
        if missing:
            raise BuildError(f"Forge started without expected mod evidence: {missing}")

    def send(self, command: str) -> None:
        if self.process is None or self.process.poll() is not None or self.process.stdin is None:
            raise BuildError("Forge server is not running")
        self.process.stdin.write(command + "\n")
        self.process.stdin.flush()

    def wait_for(self, token: str, timeout: int) -> str:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if self.process is not None and self.process.poll() is not None:
                tail = "\n".join(self.lines[-60:])
                raise BuildError(f"Forge exited {self.process.returncode} while waiting for {token!r}\n{tail}")
            try:
                line = self._queue.get(timeout=min(1.0, max(0.01, deadline - time.monotonic())))
            except queue.Empty:
                continue
            if token in line:
                return line
        tail = "\n".join(self.lines[-60:])
        raise BuildError(f"timed out waiting for Forge log token {token!r}\n{tail}")

    def barrier(self, label: str, commands: list[str], timeout: int = 120) -> None:
        token = f"ASHEN_{label}_DONE"
        for command in commands:
            self.send(command)
        self.send(f"say {token}")
        self.wait_for(token, timeout)

    def flush(self, label: str, timeout: int = 180) -> None:
        self.barrier(label, ["save-all flush"], timeout)

    def stop(self, timeout: int = 120) -> None:
        if self.process is None:
            return
        if self.process.poll() is None:
            self.send("stop")
            try:
                self.process.wait(timeout=timeout)
            except subprocess.TimeoutExpired as exc:
                raise BuildError("Forge did not stop cleanly") from exc
        if self._thread is not None:
            self._thread.join(timeout=5)
        if self.process.returncode != 0:
            raise BuildError(f"Forge stopped with exit code {self.process.returncode}")


def _chunk_loaded_token(label: str, chunk_x: int, chunk_z: int) -> str:
    x = ("M" if chunk_x < 0 else "P") + str(abs(chunk_x))
    z = ("M" if chunk_z < 0 else "P") + str(abs(chunk_z))
    return f"ASHEN_{label}_{x}_{z}_LOADED"


def _wait_chunks_loaded(
        server: ForgeServer,
        expected: set[tuple[int, int]],
        label: str,
        timeout: int,
) -> None:
    """Wait on server-owned chunk state instead of live region-file snapshots.

    While a chunk is held by a force-load ticket, `save-all flush` can persist a
    ProtoChunk status even though the server already has the full LevelChunk.  The
    prior verifier consequently looped until timeout. `execute if loaded` is the
    authoritative server-side gate; final Anvil `full` state is checked after the
    tickets are released and Forge stops cleanly.
    """
    deadline = time.monotonic() + timeout
    attempt = 0
    pending = set(expected)
    while time.monotonic() < deadline:
        attempt += 1
        commands = [
            f"execute if loaded {chunk_x * 16} 64 {chunk_z * 16} run say "
            f"{_chunk_loaded_token(label, chunk_x, chunk_z)}"
            for chunk_x, chunk_z in sorted(pending)
        ]
        server.barrier(f"{label}_LOAD_CHECK_{attempt}", commands, timeout=180)
        whole_log = "\n".join(server.lines)
        pending = {
            coord for coord in pending
            if _chunk_loaded_token(label, *coord) not in whole_log
        }
        if not pending:
            return
        time.sleep(2)
    raise BuildError(
        f"chunk generation timeout for {label}; server never reported loaded: {sorted(pending)[:12]}"
    )


def _set_world_contract(server: ForgeServer) -> None:
    server.barrier("WORLD_RULES", [
        *(f"gamerule {name} {value}" for name, value in sorted(FIXED_GAME_RULES.items())),
        "time set 11000",
        "weather clear 1000000",
        "setworldspawn -160 83 0 0",
        "difficulty normal",
    ])


def one_chunk_proof(paths: "Paths") -> dict:
    world = prepare_world_directory(paths)
    assert paths.java is not None and paths.java_sha256 is not None
    server = ForgeServer(
        paths.runtime, paths.logs / PROOF_LOG_NAME,
        paths.java, _normalize_sha256(paths.java_sha256, "Java executable SHA-256"),
    )
    try:
        server.start()
        _set_world_contract(server)
        server.barrier("PROOF_FORCELOAD", ["forceload add -176 -32 -161 -17"])
        _wait_chunks_loaded(server, {(-11, -2)}, "PROOF", 300)
        commands = []
        proof_tokens = []
        for index, (x, y, z, block, props) in enumerate(MARKERS, 1):
            state = block
            if props:
                state += "[" + ",".join(f"{k}={v}" for k, v in sorted(props.items())) + "]"
            token = f"ASHEN_PROOF_MARKER_{index}_OK"
            proof_tokens.append(token)
            commands.append(f"execute if block {x} {y} {z} {state} run say {token}")
        server.barrier("PROOF_MARKERS", commands)
        log = "\n".join(server.lines)
        missing = [token for token in proof_tokens if token not in log]
        if missing:
            raise BuildError(f"one-chunk physical marker proof failed: {missing}")
        server.barrier("PROOF_RELEASE", ["forceload remove -176 -32 -161 -17"])
        server.flush("PROOF_FINAL")
    finally:
        server.stop()
    entries = anvil.inventory(world / "region")
    proof_entry = entries.get((-11, -2))
    if proof_entry is None or anvil.status(proof_entry) != "full":
        raise BuildError("one-chunk proof did not persist chunk (-11,-2) at full status")
    verify_markers(entries)
    return {
        "chunk": [-11, -2],
        "status": "full",
        "markers": len(MARKERS),
        "log_sha256": sha256_file(paths.logs / PROOF_LOG_NAME),
    }


def generate_full_world(paths: "Paths") -> dict:
    world = prepare_world_directory(paths)
    # Each rectangle is <= the vanilla 256-forceload cap and is released before the next.
    assert paths.java is not None and paths.java_sha256 is not None
    server = ForgeServer(
        paths.runtime, paths.logs / FULL_LOG_NAME,
        paths.java, _normalize_sha256(paths.java_sha256, "Java executable SHA-256"),
    )
    batch_receipts = []
    try:
        server.start()
        _set_world_contract(server)
        for index, (min_x, max_x, min_z, max_z) in enumerate(GENERATION_BATCHES, 1):
            expected = {(x, z) for x in range(min_x, max_x + 1) for z in range(min_z, max_z + 1)}
            min_block_x, min_block_z = min_x * 16, min_z * 16
            max_block_x, max_block_z = max_x * 16 + 15, max_z * 16 + 15
            server.barrier(f"BATCH_{index}_ADD", [
                f"forceload add {min_block_x} {min_block_z} {max_block_x} {max_block_z}"
            ])
            _wait_chunks_loaded(server, expected, f"BATCH_{index}", 900)
            server.barrier(f"BATCH_{index}_REMOVE", [
                f"forceload remove {min_block_x} {min_block_z} {max_block_x} {max_block_z}"
            ])
            server.flush(f"BATCH_{index}_FINAL")
            batch_receipts.append({"chunk_bounds": [min_x, max_x, min_z, max_z], "count": len(expected)})
        server.flush("FULL_FINAL")
    finally:
        server.stop()
    entries = anvil.inventory(world / "region")
    missing = sorted(SAFETY.difference(entries))
    not_full = sorted(coord for coord in SAFETY if coord in entries and anvil.status(entries[coord]) != "full")
    if missing or not_full:
        raise BuildError(
            f"post-stop generation inventory is incomplete: missing={missing[:12]}, not_full={not_full[:12]}"
        )
    verify_markers(entries)
    return {
        "batches": batch_receipts,
        "generated_chunks": len(SAFETY),
        "log_sha256": sha256_file(paths.logs / FULL_LOG_NAME),
    }


def verify_markers(entries: dict[tuple[int, int], anvil.Entry]) -> None:
    for x, y, z, expected_name, expected_props in MARKERS:
        coord = (x >> 4, z >> 4)
        if coord not in entries:
            raise BuildError(f"marker chunk missing for {(x, y, z)}")
        name, props = anvil.block_state(entries[coord], x, y, z)
        if name != expected_name or any(props.get(key) != value for key, value in expected_props.items()):
            raise BuildError(
                f"marker mismatch at {(x, y, z)}: got {name}{props}, expected {expected_name}{expected_props}"
            )


def _copy_curated_world(source: Path, output: Path) -> None:
    reset_directory(output, output.parent)
    for filename in ("level.dat",):
        path = source / filename
        if not path.is_file():
            raise BuildError(f"generated world is missing {filename}")
        shutil.copy2(path, output / filename)
    source_server_config = source / "serverconfig" / "lostcities-server.toml"
    if not source_server_config.is_file():
        raise BuildError("generated world is missing Lost Cities server config")
    target_server_config = output / "serverconfig" / source_server_config.name
    target_server_config.parent.mkdir(parents=True)
    shutil.copy2(source_server_config, target_server_config)
    source_datapack = source / "datapacks" / GENERATION_PACK_NAME
    if not source_datapack.is_dir():
        raise BuildError("generated world is missing the pinned empty-biome datapack")
    shutil.copytree(
        source_datapack,
        output / "datapacks" / GENERATION_PACK_NAME,
        copy_function=shutil.copy2,
    )
    anvil.crop_directory(source / "region", output / "region", SAFETY)


def _write_saved_data(path: Path, profile_sha: str, asset_sha: str, contract_sha: str) -> None:
    payload = nbt.compound({
        "mapId": nbt.string(MAP_ID),
        "missionId": nbt.string(MISSION_ID),
        "mapVersion": nbt.integer(MAP_VERSION),
        "worldSeed": nbt.long(WORLD_SEED),
        "originX": nbt.integer(0),
        "originZ": nbt.integer(0),
        "contentChunkCount": nbt.integer(len(CONTENT)),
        "safetyChunkCount": nbt.integer(len(SAFETY)),
        "safetyChunkDigest": nbt.string(safety_digest()),
        "markerFingerprint": nbt.string(MARKER_FINGERPRINT),
        "profileSha256": nbt.string(profile_sha),
        "assetSha256": nbt.string(asset_sha),
        "contractSha256": nbt.string(contract_sha),
    })
    root = nbt.compound({"DataVersion": nbt.integer(DATA_VERSION), "data": payload})
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(nbt.encode("", root, "gzip"))


def _expect_nbt(root: nbt.Tag, expected: object, *keys: str) -> None:
    value = nbt.value_at(root, *keys).value
    if value != expected:
        raise BuildError(f"level.dat {'.'.join(keys)} is {value!r}, expected {expected!r}")


def verify_level_dat(level_dat: Path) -> dict:
    _, root = nbt.read_file(level_dat)
    if root.kind != nbt.TAG_COMPOUND:
        raise BuildError("level.dat root is not a compound")
    _expect_nbt(root, DATA_VERSION, "Data", "DataVersion")
    _expect_nbt(root, DATA_VERSION, "Data", "Version", "Id")
    _expect_nbt(root, "1.20.1", "Data", "Version", "Name")
    _expect_nbt(root, "main", "Data", "Version", "Series")
    _expect_nbt(root, 0, "Data", "Version", "Snapshot")
    _expect_nbt(root, WORLD_SEED, "Data", "WorldGenSettings", "seed")
    _expect_nbt(root, 1, "Data", "WorldGenSettings", "generate_features")
    _expect_nbt(root, SPAWN[0], "Data", "SpawnX")
    _expect_nbt(root, SPAWN[1], "Data", "SpawnY")
    _expect_nbt(root, SPAWN[2], "Data", "SpawnZ")
    _expect_nbt(root, SPAWN_ANGLE, "Data", "SpawnAngle")
    _expect_nbt(root, DAY_TIME, "Data", "DayTime")
    _expect_nbt(root, 2, "Data", "Difficulty")
    _expect_nbt(root, 0, "Data", "raining")
    _expect_nbt(root, 0, "Data", "thundering")
    _expect_nbt(root, 0, "Data", "rainTime")
    _expect_nbt(root, 0, "Data", "thunderTime")
    clear_weather = int(nbt.value_at(root, "Data", "clearWeatherTime").value)
    if clear_weather <= 0:
        raise BuildError("level.dat does not retain a positive clear-weather interval")
    for name, expected in FIXED_GAME_RULES.items():
        _expect_nbt(root, expected, "Data", "GameRules", name)

    dimensions = nbt.value_at(root, "Data", "WorldGenSettings", "dimensions")
    if dimensions.kind != nbt.TAG_COMPOUND or WORLD_DIMENSION not in dimensions.value:
        raise BuildError(f"level.dat does not define the authored {WORLD_DIMENSION} dimension")
    overworld = dimensions.value[WORLD_DIMENSION]
    if overworld.kind != nbt.TAG_COMPOUND:
        raise BuildError("level.dat Overworld descriptor is not a compound")
    _expect_nbt(overworld, "minecraft:overworld", "type")
    _expect_nbt(overworld, "minecraft:flat", "generator", "type")
    _expect_nbt(overworld, GENERATION_BIOME, "generator", "settings", "biome")
    _expect_nbt(overworld, 1, "generator", "settings", "features")
    _expect_nbt(overworld, 0, "generator", "settings", "lakes")
    layers = nbt.value_at(overworld, "generator", "settings", "layers")
    if layers.kind != nbt.TAG_LIST:
        raise BuildError("level.dat flat Overworld layers are malformed")
    _, layer_values = layers.value
    actual_layers = [
        (str(layer.value["block"].value), int(layer.value["height"].value))
        for layer in layer_values
    ]
    if actual_layers != [("minecraft:bedrock", 1), ("minecraft:deepslate", 47)]:
        raise BuildError(f"level.dat flat Overworld layers drifted: {actual_layers!r}")
    enabled = nbt.value_at(root, "Data", "DataPacks", "Enabled")
    if enabled.kind != nbt.TAG_LIST:
        raise BuildError("level.dat enabled datapack list is malformed")
    enabled_names = {str(tag.value) for tag in enabled.value[1]}
    expected_packs = {
        "vanilla",
        "mod:forge",
        "mod:lostcities",
        "mod:mecharena_sector01",
        f"file/{GENERATION_PACK_NAME}",
    }
    if enabled_names != expected_packs:
        raise BuildError(f"level.dat enabled datapacks drifted: {sorted(enabled_names)}")
    return {
        "data_version": DATA_VERSION,
        "dimension": WORLD_DIMENSION,
        "spawn": list(SPAWN),
        "day_time": DAY_TIME,
        "weather": "clear",
        "fixed_game_rules": dict(sorted(FIXED_GAME_RULES.items())),
    }


def _saved_data_values(path: Path) -> dict[str, object]:
    _, root = nbt.read_file(path)
    data = nbt.value_at(root, "data")
    if data.kind != nbt.TAG_COMPOUND:
        raise BuildError("map SavedData payload is not a compound")
    return {key: tag.value for key, tag in data.value.items()}


def _expected_region_names() -> set[str]:
    return {f"r.{x // 32}.{z // 32}.mca" for x, z in SAFETY}


def _curated_file_manifest(world: Path) -> tuple[list[dict[str, object]], str]:
    pack_root = f"datapacks/{GENERATION_PACK_NAME}"
    allowed_dirs = {
        "data",
        "region",
        "serverconfig",
        "datapacks",
        pack_root,
        f"{pack_root}/data",
        f"{pack_root}/data/minecraft",
        f"{pack_root}/data/minecraft/tags",
        f"{pack_root}/data/minecraft/tags/worldgen",
        f"{pack_root}/data/minecraft/tags/worldgen/biome",
        f"{pack_root}/data/mecharena_generation",
        f"{pack_root}/data/mecharena_generation/worldgen",
        f"{pack_root}/data/mecharena_generation/worldgen/biome",
    }
    actual_dirs: set[str] = set()
    for path in world.rglob("*"):
        if path.is_symlink():
            raise BuildError(f"world template contains a symlink: {path.relative_to(world)}")
        if path.is_dir():
            actual_dirs.add(path.relative_to(world).as_posix())
    if actual_dirs != allowed_dirs:
        raise BuildError(
            f"world template directory set is not curated: extra={sorted(actual_dirs - allowed_dirs)}, "
            f"missing={sorted(allowed_dirs - actual_dirs)}"
        )
    expected_files = {
        "level.dat",
        "data/mecharena_sector01_contract.dat",
        "serverconfig/lostcities-server.toml",
        *(f"region/{name}" for name in _expected_region_names()),
        *(f"{pack_root}/{relative}" for relative in GENERATION_DATAPACK_FILES),
    }
    actual_files = {
        path.relative_to(world).as_posix()
        for path in world.rglob("*")
        if path.is_file()
    }
    if actual_files != expected_files:
        raise BuildError(
            f"world template file set is not curated: extra={sorted(actual_files - expected_files)}, "
            f"missing={sorted(expected_files - actual_files)}"
        )
    manifest: list[dict[str, object]] = []
    digest = hashlib.sha256()
    for relative in sorted(actual_files):
        path = world / Path(relative)
        size = path.stat().st_size
        file_sha = sha256_file(path)
        digest.update(f"{relative}\0{size}\0{file_sha}\n".encode("utf-8"))
        manifest.append({"path": relative, "bytes": size, "sha256": file_sha})
    return manifest, digest.hexdigest()


class _BlockLookup:
    def __init__(self, entries: dict[tuple[int, int], anvil.Entry]):
        self.entries = entries
        self.roots: dict[tuple[int, int], nbt.Tag] = {}

    def state(self, x: int, y: int, z: int) -> tuple[str, dict[str, str]]:
        coord = (x >> 4, z >> 4)
        try:
            entry = self.entries[coord]
        except KeyError as exc:
            raise BuildError(f"authored block {(x, y, z)} is outside the packaged chunk inventory") from exc
        root = self.roots.setdefault(coord, anvil.unpack_nbt(entry))
        return anvil.block_state_from_root(root, x, y, z)


def _points(bounds: tuple[int, int, int, int, int, int]):
    min_x, max_x, min_y, max_y, min_z, max_z = bounds
    for x in range(min_x, max_x + 1):
        for y in range(min_y, max_y + 1):
            for z in range(min_z, max_z + 1):
                yield x, y, z


def _route_supports() -> list[tuple[int, int, int]]:
    result: list[tuple[int, int, int]] = []
    result.extend((x, 82, 0) for x in range(-168, -136))
    for x in range(-136, -100):
        progress = x + 136
        result.append((x, 82 - ((progress * 18 + 17) // 35), 0))
    result.extend((x, 64, 0) for x in range(-100, -88))
    for x in range(-88, -72):
        progress = x + 88
        result.append((x, 64 + ((progress * 8 + 7) // 15), 0))
    result.extend((x, 72, 0) for x in range(-72, 176))
    return result


def verify_authored_facts(entries: dict[tuple[int, int], anvil.Entry]) -> dict:
    blocks = _BlockLookup(entries)
    verify_markers(entries)

    for gate_id, bounds in GATES.items():
        for point in _points(bounds):
            name, _ = blocks.state(*point)
            if name != "minecraft:orange_concrete":
                raise BuildError(f"{gate_id} gate mismatch at {point}: {name}")
    for shutter_id, bounds in STAGING_SHUTTERS.items():
        for point in _points(bounds):
            name, _ = blocks.state(*point)
            if name != "minecraft:polished_blackstone_bricks":
                raise BuildError(f"{shutter_id} staging shutter mismatch at {point}: {name}")

    def verify_staging_surface(position: tuple[int, int, int], expected_center: str, label: str) -> None:
        x, feet_y, z = position
        center, _ = blocks.state(x, feet_y - 1, z)
        if center != expected_center:
            raise BuildError(f"{label} center support mismatch at {(x, feet_y - 1, z)}: {center}")
        for dx in range(-2, 3):
            for dz in range(-2, 3):
                support, _ = blocks.state(x + dx, feet_y - 1, z + dz)
                if support not in ROUTE_SUPPORT_BLOCKS:
                    raise BuildError(f"{label} unsupported footprint at {(x + dx, feet_y - 1, z + dz)}: {support}")
        for y in (feet_y, feet_y + 1):
            body, _ = blocks.state(x, y, z)
            if body not in AIR_BLOCKS:
                raise BuildError(f"{label} obstructed center at {(x, y, z)}: {body}")

    for index, position in enumerate(SOCKETS, 1):
        verify_staging_surface(position, "minecraft:cyan_concrete", f"socket {index}")
    for index, position in enumerate(RECOVERY_ANCHORS, 1):
        verify_staging_surface(
            position, "minecraft:chiseled_polished_blackstone", f"recovery anchor {index}"
        )
    verify_staging_surface(PLAYER_FALLBACK, "minecraft:chiseled_polished_blackstone", "player fallback")
    verify_staging_surface(SPAWN, "minecraft:cyan_concrete", "Garage player spawn")

    service_name, _ = blocks.state(60, 73, 28)
    if service_name != "minecraft:cyan_concrete":
        raise BuildError(f"service gantry root mismatch at (60,73,28): {service_name}")

    cargo_allowed = {"minecraft:polished_blackstone_bricks", "minecraft:orange_concrete"}
    for cx, base_y, cz, size_x, size_y, size_z in CARGO_PODS:
        bounds = (
            cx - size_x // 2, cx - size_x // 2 + size_x - 1,
            base_y, base_y + size_y - 1,
            cz - size_z // 2, cz - size_z // 2 + size_z - 1,
        )
        for point in _points(bounds):
            name, _ = blocks.state(*point)
            if name not in cargo_allowed:
                raise BuildError(f"freight cargo pod is not opaque at {point}: {name}")

    blocked_centerline = {-168, -153, -137, -97, -89, -64, -32, 0, 1, 24, 58, 72, 175}
    route = _route_supports()
    for x, surface_y, z in route:
        support, _ = blocks.state(x, surface_y, z)
        if support not in ROUTE_SUPPORT_BLOCKS:
            raise BuildError(f"non-jump route support mismatch at {(x, surface_y, z)}: {support}")
        if x not in blocked_centerline:
            for y in (surface_y + 1, surface_y + 2):
                headroom, _ = blocks.state(x, y, z)
                if headroom not in AIR_BLOCKS:
                    raise BuildError(f"non-jump route headroom blocked at {(x, y, z)}: {headroom}")
    for x, surface_y, z in route:
        if -136 <= x <= -89 and x not in {-97, -89}:
            for y in range(surface_y + 1, surface_y + 13):
                headroom, _ = blocks.state(x, y, z)
                if headroom not in AIR_BLOCKS:
                    raise BuildError(f"Freight Canyon 12-block clearance failed at {(x, y, z)}: {headroom}")

    for x in range(84, 172):
        for z in range(-43, 45):
            name, _ = blocks.state(x, 73, z)
            if name not in AIR_BLOCKS:
                raise BuildError(f"Power Deck clear center contains cover at {(x, 73, z)}: {name}")
    sky_x = sorted({84, 171, *range(88, 172, 8)})
    sky_z = sorted({-43, 44, *range(-40, 45, 8)})
    for x in sky_x:
        for z in sky_z:
            for y in range(73, 121):
                name, _ = blocks.state(x, y, z)
                if name not in AIR_BLOCKS:
                    raise BuildError(f"Power Deck open-sky sample blocked at {(x, y, z)}: {name}")
    return {
        "markers": len(MARKERS),
        "gates": len(GATES),
        "staging_shutters": len(STAGING_SHUTTERS),
        "sockets": len(SOCKETS),
        "recovery_anchors": len(RECOVERY_ANCHORS),
        "route_support_samples": len(route),
        "power_deck_clear_layer_blocks": 88 * 88,
    }


def verify_world(
        world: Path,
        profile_sha: str,
        asset_sha: str,
        contract_sha: str,
) -> dict:
    if not world.is_dir():
        raise BuildError(f"world template directory is missing: {world}")
    manifest, tree_sha = _curated_file_manifest(world)
    packaged_datapack = world / "datapacks" / GENERATION_PACK_NAME
    packaged_datapack_sha = generation_datapack_digest(packaged_datapack)
    if packaged_datapack_sha != GENERATION_DATAPACK_SHA256:
        raise BuildError(f"packaged generation datapack hash mismatch: {packaged_datapack_sha}")
    level_facts = verify_level_dat(world / "level.dat")
    config = (world / "serverconfig" / "lostcities-server.toml").read_text(encoding="utf-8")
    if f'selectedProfile = "{PROFILE_NAME}"' not in config:
        raise BuildError("packaged world does not pin the Lost Cities profile")
    if 'selectedCustomJson = ""' not in config:
        raise BuildError("packaged world unexpectedly selects a mutable custom Lost Cities profile")
    entries = anvil.inventory(world / "region")
    if set(entries) != SAFETY:
        extra = sorted(set(entries).difference(SAFETY))[:8]
        missing = sorted(SAFETY.difference(entries))[:8]
        raise BuildError(f"bounded chunk inventory mismatch: extra={extra}, missing={missing}")
    bad_chunks = []
    forbidden_blocks: set[str] = set()
    ticking_payloads: list[tuple[tuple[int, int], str, int]] = []
    for coord, entry in entries.items():
        data_version, x_pos, z_pos, chunk_status = anvil.chunk_metadata(entry)
        if data_version != DATA_VERSION or (x_pos, z_pos) != coord or chunk_status != "full":
            bad_chunks.append((coord, data_version, x_pos, z_pos, chunk_status))
        root = anvil.unpack_nbt(entry)
        forbidden_blocks.update(
            name
            for name in anvil.palette_names_from_root(root)
            if any(fragment in name for fragment in FORBIDDEN_BLOCK_FRAGMENTS)
        )
        for key in ("block_entities", "block_ticks", "fluid_ticks"):
            tag = root.value.get(key)
            if tag is None or tag.kind != nbt.TAG_LIST:
                raise BuildError(f"terrain chunk {coord} has malformed or missing {key}")
            count = len(tag.value[1])
            if count:
                ticking_payloads.append((coord, key, count))
    if bad_chunks:
        raise BuildError(f"terrain chunk identity/status mismatch: {sorted(bad_chunks)[:8]}")
    if forbidden_blocks:
        raise BuildError(f"template contains forbidden block palettes: {sorted(forbidden_blocks)}")
    if ticking_payloads:
        raise BuildError(f"template contains block entities or scheduled ticks: {ticking_payloads[:8]}")
    authored_facts = verify_authored_facts(entries)
    saved_path = world / "data" / "mecharena_sector01_contract.dat"
    saved = _saved_data_values(saved_path)
    expected = {
        "mapId": MAP_ID,
        "missionId": MISSION_ID,
        "mapVersion": MAP_VERSION,
        "worldSeed": WORLD_SEED,
        "originX": 0,
        "originZ": 0,
        "contentChunkCount": 308,
        "safetyChunkCount": 680,
        "safetyChunkDigest": safety_digest(),
        "markerFingerprint": MARKER_FINGERPRINT,
        "profileSha256": profile_sha,
        "assetSha256": asset_sha,
        "contractSha256": contract_sha,
    }
    if saved != expected:
        raise BuildError(f"SavedData contract mismatch: {saved!r}")
    terrain_bytes = sum(path.stat().st_size for path in (world / "region").glob("*.mca"))
    return {
        "terrain_chunk_count": len(entries),
        "authored_chunk_count": len(CONTENT.intersection(entries)),
        "safety_chunk_digest": safety_digest(),
        "terrain_bytes": terrain_bytes,
        "forbidden_palette_entries": 0,
        "block_entities_and_scheduled_ticks": 0,
        "curated_file_count": len(manifest),
        "curated_tree_sha256": tree_sha,
        "generation_datapack_sha256": packaged_datapack_sha,
        "level": level_facts,
        "authored_facts": authored_facts,
    }


def deterministic_zip(world: Path, archive: Path) -> None:
    archive.parent.mkdir(parents=True, exist_ok=True)
    if archive.exists():
        archive.unlink()
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as output:
        for path in sorted(p for p in world.rglob("*") if p.is_file()):
            relative = Path(WORLD_ARCHIVE_ROOT) / path.relative_to(world)
            info = zipfile.ZipInfo(relative.as_posix(), CANONICAL_ZIP_TIME)
            info.create_system = 3  # Unix: make the canonical 0100644 mode portable on Windows.
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            output.writestr(info, path.read_bytes(), compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)


def _reconstruct_archive(archive: Path, destination: Path) -> tuple[Path, int]:
    if not archive.is_file():
        raise BuildError(f"world archive is missing: {archive}")
    archive_bytes = archive.stat().st_size
    if archive_bytes > MAX_WORLD_BYTES:
        raise BuildError(f"world archive is {archive_bytes} bytes; budget is {MAX_WORLD_BYTES}")
    world = destination / WORLD_ARCHIVE_ROOT
    with zipfile.ZipFile(archive) as source:
        infos = source.infolist()
        if not infos:
            raise BuildError("world archive is empty")
        names = [info.filename for info in infos]
        if names != sorted(names) or len(names) != len(set(names)):
            raise BuildError("world archive entries are not uniquely sorted")
        uncompressed = sum(info.file_size for info in infos)
        if uncompressed > MAX_WORLD_UNCOMPRESSED_BYTES:
            raise BuildError(f"world archive expands to unsafe size: {uncompressed}")
        for info in infos:
            if info.is_dir() or info.filename.endswith("/"):
                raise BuildError(f"world archive contains a non-canonical directory entry: {info.filename}")
            if "\\" in info.filename:
                raise BuildError(f"world archive uses a non-POSIX path: {info.filename}")
            parts = info.filename.split("/")
            if len(parts) < 2 or parts[0] != WORLD_ARCHIVE_ROOT or any(part in ("", ".", "..") for part in parts):
                raise BuildError(f"world archive entry escapes the locked root: {info.filename}")
            if info.date_time != CANONICAL_ZIP_TIME:
                raise BuildError(f"world archive timestamp is not canonical: {info.filename}")
            if info.compress_type != zipfile.ZIP_DEFLATED:
                raise BuildError(f"world archive entry is not deflated: {info.filename}")
            if info.create_system != 3:
                raise BuildError(f"world archive creator system is not canonical Unix: {info.filename}")
            if info.flag_bits & 0x1:
                raise BuildError(f"world archive entry is encrypted: {info.filename}")
            if (info.external_attr >> 16) != 0o100644:
                raise BuildError(f"world archive file mode is not canonical: {info.filename}")
            target = destination.joinpath(*parts)
            resolved = target.resolve()
            if destination.resolve() not in resolved.parents:
                raise BuildError(f"world archive entry escapes extraction root: {info.filename}")
            target.parent.mkdir(parents=True, exist_ok=True)
            with source.open(info) as input_stream, target.open("wb") as output_stream:
                shutil.copyfileobj(input_stream, output_stream)
        corrupt = source.testzip()
        if corrupt is not None:
            raise BuildError(f"world archive CRC failed: {corrupt}")
    return world, uncompressed


def verify_archive(
        archive: Path,
        frozen_world: Path,
        profile_sha: str,
        asset_sha: str,
        contract_sha: str,
        frozen_verification: dict | None = None,
) -> dict:
    with tempfile.TemporaryDirectory(prefix="ashen-span-world-verify-") as tmp:
        root = Path(tmp)
        reconstructed, uncompressed = _reconstruct_archive(archive, root / "reconstructed")
        archived = verify_world(reconstructed, profile_sha, asset_sha, contract_sha)
        frozen = frozen_verification or verify_world(
            frozen_world, profile_sha, asset_sha, contract_sha
        )
        if archived["curated_tree_sha256"] != frozen["curated_tree_sha256"]:
            raise BuildError("world archive does not reconstruct the frozen curated tree")
        canonical = root / "canonical.zip"
        deterministic_zip(reconstructed, canonical)
        archive_sha = sha256_file(archive)
        if sha256_file(canonical) != archive_sha:
            raise BuildError("world archive bytes are not the canonical encoding of the frozen tree")
    return {
        **archived,
        "archive_bytes": archive.stat().st_size,
        "archive_sha256": archive_sha,
        "archive_uncompressed_bytes": uncompressed,
        "archive_root": WORLD_ARCHIVE_ROOT,
        "canonical_frozen_tree_archive": True,
    }


@dataclass(frozen=True)
class Paths:
    repo: Path
    project: Path
    work: Path
    runtime: Path
    logs: Path
    evidence: Path
    forge_runtime: Path
    forge_runtime_sha256: str | None
    java: Path | None
    java_sha256: str | None
    lost_cities: Path
    asset: Path
    profile: Path
    contract: Path
    output_world: Path
    archive: Path
    receipt: Path


def discover(
        repo: Path,
        work: Path | None = None,
        forge_runtime: Path | None = None,
        lost_cities: Path | None = None,
        forge_runtime_sha256: str | None = None,
        java: Path | None = None,
        java_sha256: str | None = None,
) -> Paths:
    repo = repo.resolve()
    project = repo.parent
    work = (work or repo / "build" / "ashen-span-world").resolve()
    if repo not in work.parents:
        raise BuildError("--work must stay beneath the pomkots-mechs repository")
    dist = repo / "sector01-world" / "dist"
    assets = sorted((repo / "sector01-src" / "dist").glob("mecharena_sector01-1.0.0-mp25.jar"))
    if len(assets) != 1:
        raise BuildError("expected exactly one deterministic Sector 01 asset JAR")
    discovered_java = java
    if discovered_java is None:
        command = shutil.which("java")
        discovered_java = Path(command) if command else None
    historical_runtime = Path.home() / "OneDrive" / "Desktop" / "mech-arena" / "server-spike"
    default_runtime = historical_runtime if historical_runtime.is_dir() else project / "server-spike"
    return Paths(
        repo=repo,
        project=project,
        work=work,
        runtime=work / "runtime",
        logs=work / "logs",
        evidence=repo / "sector01-world" / "evidence",
        forge_runtime=(forge_runtime or default_runtime).resolve(),
        forge_runtime_sha256=forge_runtime_sha256 or PINNED_FORGE_RUNTIME_SHA256,
        java=discovered_java.resolve() if discovered_java is not None else None,
        java_sha256=java_sha256 or PINNED_JAVA_SHA256,
        lost_cities=(lost_cities or project / "downloads" / "forge" / "lostcities-1.20-7.4.13.jar").resolve(),
        asset=assets[0],
        profile=repo / "sector01-src" / "lostcities-profile.json",
        contract=repo / "sector01-src" / "sector01-contract.json",
        output_world=dist / WORLD_OUTPUT_STEM,
        archive=dist / f"{WORLD_OUTPUT_STEM}.zip",
        receipt=dist / f"{WORLD_OUTPUT_STEM}.build-receipt.json",
    )


def _validate_inputs(paths: Paths) -> tuple[str, str, str, str]:
    for path in (paths.forge_runtime, paths.lost_cities, paths.asset, paths.profile, paths.contract):
        if not path.exists():
            raise BuildError(f"required input is missing: {path}")
    lost_sha = sha256_file(paths.lost_cities)
    if lost_sha != LOST_CITIES_SHA256:
        raise BuildError(f"Lost Cities 7.4.13 hash mismatch: {lost_sha}")
    with zipfile.ZipFile(paths.asset) as asset_zip:
        try:
            mods_toml = asset_zip.read("META-INF/mods.toml").decode("utf-8")
        except (KeyError, UnicodeDecodeError) as exc:
            raise BuildError("Sector 01 asset has no readable META-INF/mods.toml") from exc
    if 'modId="mecharena_sector01"' not in mods_toml:
        raise BuildError("Sector 01 asset JAR does not declare mecharena_sector01")
    if 'versionRange="[1.20-7.4.13]"' not in mods_toml:
        raise BuildError("Sector 01 asset JAR does not require the exact Forge Lost Cities mod version")
    contract = json.loads(paths.contract.read_text(encoding="utf-8"))
    validate_locked_contract(contract)
    generation_pack_sha = generation_datapack_digest(GENERATION_DATAPACK_SOURCE)
    if generation_pack_sha != GENERATION_DATAPACK_SHA256:
        raise BuildError(f"generation datapack source hash mismatch: {generation_pack_sha}")
    profile_sha = sha256_file(paths.profile)
    asset_sha = sha256_file(paths.asset)
    contract_sha = sha256_file(paths.contract)
    exact = {
        "Sector 01 asset": (asset_sha, ASSET_SHA256),
        "Lost Cities profile": (profile_sha, PROFILE_SHA256),
        "Sector 01 contract": (contract_sha, CONTRACT_SHA256),
    }
    for label, (actual, expected) in exact.items():
        if actual != expected:
            raise BuildError(f"{label} hash mismatch: {actual}; expected {expected}")
    return lost_sha, profile_sha, asset_sha, contract_sha


def _make_receipt(
        paths: Paths,
        lost_sha: str,
        profile_sha: str,
        asset_sha: str,
        contract_sha: str,
        proof: dict,
        full: dict,
        frozen_verification: dict,
        verification: dict,
) -> dict:
    return {
        "schema": 2,
        "builder": "ashen-span-world/2.0.0",
        "map_id": MAP_ID,
        "mission_id": MISSION_ID,
        "map_version": MAP_VERSION,
        "seed": WORLD_SEED,
        "minecraft": "1.20.1",
        "forge": "47.3.3",
        "lost_cities": {"version": "7.4.13", "sha256": lost_sha},
        "asset": {"name": paths.asset.name, "sha256": asset_sha},
        "profile": {"name": PROFILE_NAME, "sha256": profile_sha},
        "source_contract": {"name": paths.contract.name, "sha256": contract_sha},
        "generation_datapack": {
            "name": GENERATION_PACK_NAME,
            "biome": GENERATION_BIOME,
            "sha256": GENERATION_DATAPACK_SHA256,
        },
        "generation_runtime": {
            "forge_libraries_sha256": _normalize_sha256(
                paths.forge_runtime_sha256, "Forge runtime SHA-256"
            ),
            "java_executable_sha256": _normalize_sha256(
                paths.java_sha256, "Java executable SHA-256"
            ),
        },
        "one_chunk_proof": proof,
        "generation": full,
        "frozen_tree_verification": frozen_verification,
        "verification": verification,
        "world": {
            "name": paths.archive.name,
            "archive_root": WORLD_ARCHIVE_ROOT,
            "sha256": verification["archive_sha256"],
            "bytes": verification["archive_bytes"],
            "uncompressed_bytes": verification["archive_uncompressed_bytes"],
            "curated_tree_sha256": verification["curated_tree_sha256"],
        },
        "contract": {
            "dimension": WORLD_DIMENSION,
            "data_version": DATA_VERSION,
            "content_chunk_count": 308,
            "safety_chunk_count": 680,
            "safety_chunk_digest": safety_digest(),
            "view_distance": 6,
            "simulation_distance": 6,
            "compressed_budget_bytes": MAX_WORLD_BYTES,
        },
        "determinism": {
            "scope": "canonical archive bytes for this verified frozen curated world tree",
            "independent_forge_generation_byte_equality_claimed": False,
        },
        "canonical_zip_timestamp": "1980-01-01T00:00:00Z",
    }


def _write_receipt(paths: Paths, receipt: dict) -> None:
    paths.receipt.parent.mkdir(parents=True, exist_ok=True)
    paths.receipt.write_text(
        json.dumps(receipt, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
        newline="\n",
    )


def _publish_generation_evidence(paths: Paths) -> None:
    """Publish exact generation logs once; never overwrite differing evidence."""
    sources = {
        PROOF_LOG_NAME: paths.logs / PROOF_LOG_NAME,
        FULL_LOG_NAME: paths.logs / FULL_LOG_NAME,
    }
    for source in sources.values():
        if not source.is_file():
            raise BuildError(f"cannot publish missing generation log: {source}")
    if paths.evidence.exists():
        if not paths.evidence.is_dir() or is_link_or_reparse(paths.evidence):
            raise BuildError(f"generation evidence path is unsafe: {paths.evidence}")
        actual = {path.name for path in paths.evidence.iterdir() if path.is_file()}
        if actual != set(sources):
            raise BuildError(f"generation evidence file set drifted: {sorted(actual)}")
        for name, source in sources.items():
            target = paths.evidence / name
            if is_link_or_reparse(target) or target.read_bytes() != source.read_bytes():
                raise BuildError(f"refusing to overwrite differing generation evidence: {target}")
        return
    paths.evidence.parent.mkdir(parents=True, exist_ok=True)
    paths.evidence.mkdir()
    try:
        for name, source in sources.items():
            with (paths.evidence / name).open("xb") as output:
                output.write(source.read_bytes())
    except BaseException:
        _assert_under(paths.evidence, paths.evidence.parent)
        shutil.rmtree(paths.evidence)
        raise


def _generation_evidence_from_logs(paths: Paths) -> tuple[dict, dict]:
    proof_log = paths.evidence / PROOF_LOG_NAME
    full_log = paths.evidence / FULL_LOG_NAME
    for path in (proof_log, full_log):
        if not path.is_file():
            raise BuildError(f"cannot finalize without generation log: {path}")
    proof_text = proof_log.read_text(encoding="utf-8", errors="replace")
    required_proof = {
        _chunk_loaded_token("PROOF", -11, -2),
        "ASHEN_PROOF_FINAL_DONE",
        *(f"ASHEN_PROOF_MARKER_{index}_OK" for index in range(1, len(MARKERS) + 1)),
    }
    missing_proof = sorted(token for token in required_proof if token not in proof_text)
    if missing_proof:
        raise BuildError(f"one-chunk proof log is missing evidence: {missing_proof}")

    full_text = full_log.read_text(encoding="utf-8", errors="replace")
    if "Error generating chunk" in full_text:
        raise BuildError("full generation log contains a Lost Cities chunk-generation error")
    missing_full: list[str] = []
    batch_receipts = []
    for index, (min_x, max_x, min_z, max_z) in enumerate(GENERATION_BATCHES, 1):
        expected = {
            (x, z)
            for x in range(min_x, max_x + 1)
            for z in range(min_z, max_z + 1)
        }
        missing_full.extend(
            _chunk_loaded_token(f"BATCH_{index}", *coord)
            for coord in sorted(expected)
            if _chunk_loaded_token(f"BATCH_{index}", *coord) not in full_text
        )
        final_token = f"ASHEN_BATCH_{index}_FINAL_DONE"
        if final_token not in full_text:
            missing_full.append(final_token)
        batch_receipts.append({
            "chunk_bounds": [min_x, max_x, min_z, max_z],
            "count": len(expected),
        })
    if "ASHEN_FULL_FINAL_DONE" not in full_text:
        missing_full.append("ASHEN_FULL_FINAL_DONE")
    if missing_full:
        raise BuildError(f"full generation log is missing evidence: {missing_full[:12]}")
    return (
        {
            "chunk": [-11, -2],
            "status": "full",
            "markers": len(MARKERS),
            "log": f"sector01-world/evidence/{PROOF_LOG_NAME}",
            "log_sha256": sha256_file(proof_log),
        },
        {
            "batches": batch_receipts,
            "generated_chunks": len(SAFETY),
            "log": f"sector01-world/evidence/{FULL_LOG_NAME}",
            "log_sha256": sha256_file(full_log),
        },
    )


def finalize_existing(paths: Paths) -> dict:
    """Finish archive/receipt work after a validated Forge generation pass."""
    lost_sha, profile_sha, asset_sha, contract_sha = _validate_inputs(paths)
    _publish_generation_evidence(paths)
    proof, full = _generation_evidence_from_logs(paths)
    frozen_verification = verify_world(
        paths.output_world, profile_sha, asset_sha, contract_sha
    )
    deterministic_zip(paths.output_world, paths.archive)
    verification = verify_archive(
        paths.archive,
        paths.output_world,
        profile_sha,
        asset_sha,
        contract_sha,
        frozen_verification,
    )
    receipt = _make_receipt(
        paths, lost_sha, profile_sha, asset_sha, contract_sha,
        proof, full, frozen_verification, verification,
    )
    _write_receipt(paths, receipt)
    _verify_receipt(
        receipt, verification, paths, lost_sha, profile_sha, asset_sha, contract_sha
    )
    return receipt


def build(paths: Paths) -> dict:
    lost_sha, profile_sha, asset_sha, contract_sha = _validate_inputs(paths)
    paths.work.mkdir(parents=True, exist_ok=True)
    reset_directory(paths.logs, paths.work)
    prepare_runtime(paths)
    proof = one_chunk_proof(paths)
    full = generate_full_world(paths)
    _publish_generation_evidence(paths)
    proof, full = _generation_evidence_from_logs(paths)
    _copy_curated_world(paths.runtime / "world", paths.output_world)
    _write_saved_data(
        paths.output_world / "data" / "mecharena_sector01_contract.dat",
        profile_sha,
        asset_sha,
        contract_sha,
    )
    frozen_verification = verify_world(paths.output_world, profile_sha, asset_sha, contract_sha)
    deterministic_zip(paths.output_world, paths.archive)
    verification = verify_archive(
        paths.archive,
        paths.output_world,
        profile_sha,
        asset_sha,
        contract_sha,
        frozen_verification,
    )
    receipt = _make_receipt(
        paths, lost_sha, profile_sha, asset_sha, contract_sha,
        proof, full, frozen_verification, verification,
    )
    _write_receipt(paths, receipt)
    return receipt


def _verify_receipt(
        receipt: dict,
        result: dict,
        paths: Paths,
        lost_sha: str,
        profile_sha: str,
        asset_sha: str,
        contract_sha: str,
) -> None:
    measured_proof, measured_generation = _generation_evidence_from_logs(paths)
    expected = {
        "schema": 2,
        "builder": "ashen-span-world/2.0.0",
        "map_id": MAP_ID,
        "mission_id": MISSION_ID,
        "map_version": MAP_VERSION,
        "seed": WORLD_SEED,
        "minecraft": "1.20.1",
        "forge": "47.3.3",
        "lost_cities": {"version": "7.4.13", "sha256": lost_sha},
        "asset": {"name": paths.asset.name, "sha256": asset_sha},
        "profile": {"name": PROFILE_NAME, "sha256": profile_sha},
        "source_contract": {"name": paths.contract.name, "sha256": contract_sha},
        "generation_datapack": {
            "name": GENERATION_PACK_NAME,
            "biome": GENERATION_BIOME,
            "sha256": GENERATION_DATAPACK_SHA256,
        },
        "generation_runtime": {
            "forge_libraries_sha256": _normalize_sha256(
                paths.forge_runtime_sha256, "Forge runtime SHA-256"
            ),
            "java_executable_sha256": _normalize_sha256(
                paths.java_sha256, "Java executable SHA-256"
            ),
        },
        "verification": result,
        "world": {
            "name": paths.archive.name,
            "archive_root": WORLD_ARCHIVE_ROOT,
            "sha256": result["archive_sha256"],
            "bytes": result["archive_bytes"],
            "uncompressed_bytes": result["archive_uncompressed_bytes"],
            "curated_tree_sha256": result["curated_tree_sha256"],
        },
        "contract": {
            "dimension": WORLD_DIMENSION,
            "data_version": DATA_VERSION,
            "content_chunk_count": 308,
            "safety_chunk_count": 680,
            "safety_chunk_digest": safety_digest(),
            "view_distance": 6,
            "simulation_distance": 6,
            "compressed_budget_bytes": MAX_WORLD_BYTES,
        },
        "determinism": {
            "scope": "canonical archive bytes for this verified frozen curated world tree",
            "independent_forge_generation_byte_equality_claimed": False,
        },
        "canonical_zip_timestamp": "1980-01-01T00:00:00Z",
    }
    for key, value in expected.items():
        if receipt.get(key) != value:
            raise BuildError(f"build receipt {key} does not match independently measured output")
    frozen = receipt.get("frozen_tree_verification")
    if not isinstance(frozen, dict) or frozen.get("curated_tree_sha256") != result["curated_tree_sha256"]:
        raise BuildError("build receipt frozen-tree verification does not match reconstructed archive")
    if receipt.get("one_chunk_proof") != measured_proof:
        raise BuildError("build receipt one-chunk proof does not match the actual proof log")
    if receipt.get("generation") != measured_generation:
        raise BuildError("build receipt bounded generation evidence does not match the actual generation log")


def verify_existing(paths: Paths) -> dict:
    lost_sha, profile_sha, asset_sha, contract_sha = _validate_inputs(paths)
    result = verify_archive(
        paths.archive, paths.output_world, profile_sha, asset_sha, contract_sha
    )
    if not paths.receipt.is_file():
        raise BuildError(f"missing build receipt: {paths.receipt}")
    receipt = json.loads(paths.receipt.read_text(encoding="utf-8"))
    if not isinstance(receipt, dict):
        raise BuildError("build receipt root is not a JSON object")
    _verify_receipt(
        receipt, result, paths, lost_sha, profile_sha, asset_sha, contract_sha
    )
    return result


def proof_only(paths: Paths) -> dict:
    """Run the mandatory scale gate without beginning the 680-chunk pass."""
    _validate_inputs(paths)
    paths.work.mkdir(parents=True, exist_ok=True)
    reset_directory(paths.logs, paths.work)
    prepare_runtime(paths)
    return one_chunk_proof(paths)


def main(argv: list[str] | None = None) -> int:
    # Forge occasionally emits replacement characters for formatting codes. Keep
    # fail-closed diagnostics printable on the default Windows cp1252 console.
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(errors="backslashreplace")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--work", type=Path)
    parser.add_argument("--forge-runtime", type=Path)
    parser.add_argument("--forge-runtime-sha256")
    parser.add_argument("--java", type=Path)
    parser.add_argument("--java-sha256")
    parser.add_argument("--lost-cities-jar", type=Path)
    parser.add_argument("--verify-only", action="store_true")
    parser.add_argument("--proof-only", action="store_true")
    parser.add_argument("--finalize-only", action="store_true")
    args = parser.parse_args(argv)
    if sum((args.verify_only, args.proof_only, args.finalize_only)) > 1:
        parser.error("--verify-only, --proof-only, and --finalize-only are mutually exclusive")
    try:
        paths = discover(
            args.repo, args.work, args.forge_runtime, args.lost_cities_jar,
            args.forge_runtime_sha256, args.java, args.java_sha256,
        )
        if args.verify_only:
            result = verify_existing(paths)
        elif args.finalize_only:
            result = finalize_existing(paths)
        elif args.proof_only:
            result = proof_only(paths)
        else:
            result = build(paths)
    except (BuildError, anvil.AnvilError, nbt.NbtError, OSError, ValueError, subprocess.SubprocessError) as exc:
        print(f"ASHEN_SPAN_WORLD_FAIL: {exc}")
        return 1
    print(json.dumps(result, indent=2, sort_keys=True))
    print("ASHEN_SPAN_WORLD_OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
