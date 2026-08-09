#!/usr/bin/env python3
"""Build the deterministic mecharena_sector01 Lost Cities low-code asset JAR.

The checked-in source is deliberately small: one authored JSON contract, one
fixed Lost Cities profile, and this deterministic voxel/resource compiler. The
result contains one explicit Lost Cities part and building for every one of the
22 x 14 authored chunks. It never reads a Minecraft save or a downloaded city.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import shutil
import sys
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Mapping, Sequence


BUILDER_SCHEMA = 1
BUILDER_VERSION = "1.0.0"
ZIP_TIMESTAMP = (1980, 1, 1, 0, 0, 0)
SCRIPT_PATH = Path(__file__).resolve()
REPO_ROOT = SCRIPT_PATH.parent.parent
DEFAULT_SOURCE = REPO_ROOT / "sector01-src"
DEFAULT_ARTIFACT_NAME = "mecharena_sector01-1.0.0-mp25.jar"

HARD_AIR = "~"
PALETTE_BLOCKS: dict[str, str] = {
    HARD_AIR: "minecraft:structure_void",
    "B": "minecraft:polished_blackstone_bricks",
    "C": "minecraft:chiseled_polished_blackstone",
    "D": "minecraft:polished_deepslate",
    "E": "minecraft:orange_glazed_terracotta[facing=east]",
    "G": "minecraft:gray_concrete",
    "I": "minecraft:iron_bars",
    "L": "minecraft:lodestone",
    "O": "minecraft:orange_concrete",
    "P": "minecraft:deepslate_tiles",
    "Q": "minecraft:gray_stained_glass",
    "V": "minecraft:weathered_copper",
    "W": "minecraft:cyan_glazed_terracotta[facing=west]",
    "Y": "minecraft:cyan_concrete",
}
BLOCK_TO_CHAR = {block: char for char, block in PALETTE_BLOCKS.items()}
OPAQUE_CHARS = frozenset({"B", "C", "D", "G", "L", "O", "P", "V", "Y"})
FORBIDDEN_BLOCK_FRAGMENTS = (
    "water",
    "lava",
    "spawner",
    "chest",
    "barrel",
    "rail",
    "redstone",
    "command_block",
    "item_frame",
    "armor_stand",
    "leaves",
    "sapling",
    "vine",
)


class ContractError(ValueError):
    """Raised when checked-in source no longer matches the locked map contract."""


def canonical_json(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8")


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def load_json(path: Path) -> dict:
    with path.open("r", encoding="utf-8") as handle:
        value = json.load(handle)
    if not isinstance(value, dict):
        raise ContractError(f"{path} must contain a JSON object")
    return value


def load_source(source_dir: Path) -> tuple[dict, dict, bytes]:
    contract_path = source_dir / "sector01-contract.json"
    profile_path = source_dir / "lostcities-profile.json"
    notices_path = source_dir / "THIRD_PARTY_NOTICES.md"
    for path in (contract_path, profile_path, notices_path):
        if not path.is_file():
            raise ContractError(f"missing required source file: {path}")
    contract = load_json(contract_path)
    profile = load_json(profile_path)
    notices = notices_path.read_bytes()
    validate_contract(contract, profile)
    return contract, profile, notices


def _require_equal(label: str, actual: object, expected: object) -> None:
    if actual != expected:
        raise ContractError(f"{label}: expected {expected!r}, got {actual!r}")


def _volume_count(bounds: Mapping[str, Sequence[int]]) -> int:
    count = 1
    for axis in ("x", "y", "z"):
        lo, hi = bounds[axis]
        if lo > hi:
            raise ContractError(f"invalid {axis} bounds {lo}..{hi}")
        count *= hi - lo + 1
    return count


def validate_contract(contract: dict, profile: dict) -> None:
    _require_equal("schema_version", contract.get("schema_version"), 2)
    asset = contract.get("asset", {})
    generation = contract.get("generation", {})
    bounds = contract.get("bounds", {})
    _require_equal("asset.mod_id", asset.get("mod_id"), "mecharena_sector01")
    _require_equal("asset.map_id", asset.get("map_id"), "cold_ruin_sector_01")
    _require_equal("asset.mission_id", asset.get("mission_id"), "operation_ashen_span")
    _require_equal("generation.minecraft", generation.get("minecraft"), "1.20.1")
    _require_equal("generation.lost_cities", generation.get("lost_cities"), "7.4.13")
    _require_equal("generation.lost_cities_version_range", generation.get("lost_cities_version_range"), "[1.20-7.4.13]")
    _require_equal("generation.dimension", generation.get("dimension"), "minecraft:overworld")
    _require_equal("generation.origin", generation.get("origin"), [0, 0])
    _require_equal("generation.part_min_y", generation.get("part_min_y"), 48)
    _require_equal("generation.part_max_y", generation.get("part_max_y"), 120)
    _require_equal("authored block bounds", bounds.get("authored_blocks", {}).get("x"), [-176, 175])
    _require_equal("authored block bounds", bounds.get("authored_blocks", {}).get("z"), [-112, 111])
    _require_equal("authored chunk bounds", bounds.get("authored_chunks", {}).get("x"), [-11, 10])
    _require_equal("authored chunk bounds", bounds.get("authored_chunks", {}).get("z"), [-7, 6])
    _require_equal("authored chunk count", bounds.get("authored_chunks", {}).get("count"), 308)
    _require_equal("playable x", bounds.get("playable", {}).get("x"), [-168, 167])
    _require_equal("playable z", bounds.get("playable", {}).get("z"), [-56, 55])
    _require_equal("safety chunks x", bounds.get("safety_chunks", {}).get("x"), [-17, 16])
    _require_equal("safety chunks z", bounds.get("safety_chunks", {}).get("z"), [-10, 9])
    _require_equal("view distance", bounds.get("view_distance"), 6)
    _require_equal("simulation distance", bounds.get("simulation_distance"), 6)
    _require_equal("shell count", len(contract.get("shells", [])), 12)
    _require_equal("gate ids", [gate["id"] for gate in contract.get("gates", [])],
                   ["G1", "G2", "G3", "G4", "G5", "R01_INTERNAL"])
    _require_equal("sentinel count", len(contract.get("markers", {}).get("contract_sentinels", [])), 4)
    _require_equal("profile worldStyle", profile.get("lostcity", {}).get("worldStyle"),
                   "mecharena_sector01:ashen_span_world")
    _require_equal("profile groundLevel", profile.get("lostcity", {}).get("groundLevel"), 48)
    _require_equal("profile cityChance", profile.get("cities", {}).get("cityChance"), 0.0)
    _require_equal("profile cityLevel0Height", profile.get("cities", {}).get("cityLevel0Height"), 384)
    for key in (
        "railwaysEnabled",
        "railwayStationsEnabled",
        "railwaySurfaceStationsEnabled",
        "generateLoot",
        "generateSpawners",
        "rubbleLayer",
    ):
        _require_equal(f"profile lostcity.{key}", profile.get("lostcity", {}).get(key), False)
    _require_equal("profile highwayDistanceMask", profile.get("lostcity", {}).get("highwayDistanceMask"), 0)
    _require_equal("profile scatteredChanceMultiplier", profile.get("lostcity", {}).get("scatteredChanceMultiplier"), 0.0)
    _require_equal("profile ruinChance", profile.get("lostcity", {}).get("ruinChance"), 0.0)
    _require_equal("profile explosionChance", profile.get("explosions", {}).get("explosionChance"), 0.0)
    _require_equal("profile miniExplosionChance", profile.get("explosions", {}).get("miniExplosionChance"), 0.0)
    for gate in contract["gates"]:
        _volume_count(gate["bounds"])
    normal_units = sum(
        1
        for encounter in contract["encounters"]
        for socket in encounter["sockets"]
        if socket["entity"] in {"PMS01", "PMS02", "PMS03", "PMS04", "PMS05", "PMS07"}
    )
    _require_equal("normal roster count", normal_units, 15)


@dataclass
class VoxelMap:
    min_x: int
    max_x: int
    min_y: int
    max_y: int
    min_z: int
    max_z: int

    def __post_init__(self) -> None:
        self.blocks: dict[tuple[int, int, int], str] = {}

    def _check(self, x: int, y: int, z: int) -> None:
        if not (self.min_x <= x <= self.max_x and self.min_y <= y <= self.max_y and self.min_z <= z <= self.max_z):
            raise ContractError(
                f"voxel {(x, y, z)} outside authored part bounds "
                f"x={self.min_x}..{self.max_x}, y={self.min_y}..{self.max_y}, z={self.min_z}..{self.max_z}"
            )

    def set(self, x: int, y: int, z: int, char: str) -> None:
        self._check(x, y, z)
        if char not in PALETTE_BLOCKS:
            raise ContractError(f"undefined palette character {char!r}")
        key = (x, y, z)
        if char == HARD_AIR:
            self.blocks.pop(key, None)
        else:
            self.blocks[key] = char

    def get(self, x: int, y: int, z: int) -> str:
        self._check(x, y, z)
        return self.blocks.get((x, y, z), HARD_AIR)

    def fill_box(self, x1: int, x2: int, y1: int, y2: int, z1: int, z2: int, char: str) -> None:
        if x1 > x2 or y1 > y2 or z1 > z2:
            raise ContractError(f"reversed fill box {(x1, x2, y1, y2, z1, z2)}")
        for y in range(y1, y2 + 1):
            for z in range(z1, z2 + 1):
                for x in range(x1, x2 + 1):
                    self.set(x, y, z, char)

    def support_column(self, x: int, z: int, from_y: int, to_y: int, char: str = "B", width: int = 1) -> None:
        self.fill_box(x, x + width - 1, from_y, to_y, z, z + width - 1, char)


def _bounds_box(bounds: Mapping[str, Sequence[int]]) -> tuple[int, int, int, int, int, int]:
    return bounds["x"][0], bounds["x"][1], bounds["y"][0], bounds["y"][1], bounds["z"][0], bounds["z"][1]


def _surface_pad(voxels: VoxelMap, position: Sequence[int], center_char: str, radius: int = 1) -> None:
    x, feet_y, z = position
    floor_y = feet_y - 1
    for xx in range(x - radius, x + radius + 1):
        for zz in range(z - radius, z + radius + 1):
            voxels.set(xx, floor_y, zz, "D")
    voxels.set(x, floor_y, z, center_char)


def _build_shell(voxels: VoxelMap, shell: Mapping[str, object]) -> tuple[int, int]:
    x1, x2 = shell["x"]
    z1, z2 = shell["z"]
    base_y = int(shell["base_y"])
    floors = int(shell["floors"])
    top_y = base_y + floors * 6 - 1
    voxels.fill_box(x1, x2, 63, 63, z1, z2, "D")
    for x in (x1, x2 - 1):
        for z in (z1, z2 - 1):
            voxels.support_column(x, z, 49, 62, "B", width=2)
    facade = 0
    glass = 0
    for y in range(base_y, top_y + 1):
        rel_y = y - base_y
        for z in range(z1, z2 + 1):
            for x in range(x1, x2 + 1):
                if x not in (x1, x2) and z not in (z1, z2):
                    continue
                facade += 1
                corner = x in (x1, x2) and z in (z1, z2)
                band = rel_y % 6 == 0 or y == top_y
                broken = rel_y > 2 and not corner and ((x * 13 + z * 7 + y * 5) % 61 in (0, 1))
                window = (
                    not corner
                    and not band
                    and rel_y % 6 in (2, 3)
                    and ((x if z in (z1, z2) else z) % 11 == 0)
                )
                if broken:
                    continue
                if window:
                    voxels.set(x, y, z, "Q")
                    glass += 1
                elif corner or band:
                    voxels.set(x, y, z, "B")
                else:
                    voxels.set(x, y, z, "G" if (x + z + y) % 5 else "V")
    return facade, glass


def _build_cooling_stack(voxels: VoxelMap, landmark: Mapping[str, object]) -> None:
    cx, base_y, cz = landmark["center"]
    radius = int(landmark["radius"])
    rise = int(landmark["rise"])
    voxels.fill_box(cx - radius, cx + radius, 63, 63, cz - radius, cz + radius, "D")
    for y in range(base_y, base_y + rise):
        rel = y - base_y
        taper = 0.8 * rel / max(1, rise - 1)
        outer = radius - taper
        inner = outer - 1.35
        for z in range(cz - radius, cz + radius + 1):
            for x in range(cx - radius, cx + radius + 1):
                distance = math.hypot(x - cx, z - cz)
                if inner <= distance <= outer:
                    broken_rim = rel >= rise - 8 and ((x * 17 + z * 11 + rel) % 9 in (0, 1))
                    if not broken_rim:
                        voxels.set(x, y, z, "V" if rel % 9 == 0 else "B")


def _build_relay_mast(voxels: VoxelMap, landmark: Mapping[str, object]) -> None:
    x, root_y, z = landmark["root"]
    rise = int(landmark["rise"])
    voxels.fill_box(x - 3, x + 3, 71, 71, z - 3, z + 3, "D")
    for dx, dz in ((-2, -2), (-2, 2), (2, -2), (2, 2)):
        voxels.support_column(x + dx, z + dz, 49, root_y + 35, "B")
    for y in range(root_y, root_y + rise):
        voxels.set(x, y, z, "V" if y % 4 else "Y")
        if y <= root_y + 35:
            spread = max(0, 2 - (y - root_y) // 16)
            for dx, dz in ((-spread, -spread), (-spread, spread), (spread, -spread), (spread, spread)):
                voxels.set(x + dx, y, z + dz, "B")
    for y, arm in ((root_y + 18, 5), (root_y + 34, 7), (root_y + rise - 1, 4)):
        voxels.fill_box(x - arm, x + arm, y, y, z, z, "V")
        voxels.fill_box(x, x, y, y, z - arm, z + arm, "V")
        voxels.set(x + arm, y, z, "Y")
        voxels.set(x - arm, y, z, "Y")
        voxels.set(x, y, z + arm, "Y")
        voxels.set(x, y, z - arm, "Y")


def build_voxels(contract: dict) -> VoxelMap:
    authored = contract["bounds"]["authored_blocks"]
    generation = contract["generation"]
    voxels = VoxelMap(
        authored["x"][0], authored["x"][1],
        generation["part_min_y"], generation["part_max_y"],
        authored["z"][0], authored["z"][1],
    )

    # Stable district/trench bed. All unspecified cells above it remain hard-air
    # and therefore carve inherited Lost Cities terrain out of the authored box.
    voxels.fill_box(voxels.min_x, voxels.max_x, 48, 48, voxels.min_z, voxels.max_z, "P")
    for z in range(voxels.min_z, voxels.max_z + 1, 8):
        voxels.fill_box(voxels.min_x, voxels.max_x, 48, 48, z, z, "D")

    # Twelve deliberately hollow skyline shells, kept outside the combat route.
    facade_count = 0
    glass_count = 0
    for shell in contract["shells"]:
        facade, glass = _build_shell(voxels, shell)
        facade_count += facade
        glass_count += glass
    if glass_count / facade_count >= 0.10:
        raise ContractError(f"shell glass ratio {glass_count / facade_count:.3%} exceeds 10%")

    _build_cooling_stack(voxels, contract["landmarks"]["broken_cooling_stack"])
    _build_relay_mast(voxels, contract["landmarks"]["east_relay_mast"])

    # Garage Drop Deck: a two-block roof slab, sparse supports, a marked pad,
    # and a 12-wide no-jump descent into Freight Canyon.
    voxels.fill_box(-168, -137, 81, 82, -23, 24, "D")
    for x in (-168, -153, -138):
        for z in (-23, -1, 23):
            voxels.support_column(x, z, 49, 80, "B", width=2)
    voxels.fill_box(-168, -168, 83, 85, -23, 24, "B")
    voxels.fill_box(-168, -137, 83, 85, -23, -23, "B")
    voxels.fill_box(-168, -137, 83, 85, 24, 24, "B")
    for x in range(-162, -157):
        for z in range(-2, 3):
            voxels.set(x, 82, z, "O" if (x + z) % 2 else "Y")

    # Freight Canyon is a solid industrial plinth with two readable lanes.
    voxels.fill_box(-136, -89, 49, 63, -39, 40, "B")
    voxels.fill_box(-136, -89, 64, 64, -39, 40, "G")
    voxels.fill_box(-136, -89, 64, 64, -33, -16, "D")
    voxels.fill_box(-136, -89, 64, 64, 15, 32, "D")
    voxels.fill_box(-136, -89, 65, 78, -40, -40, "B")
    voxels.fill_box(-136, -89, 65, 78, 41, 41, "B")
    for x in range(-136, -100):
        progress = x + 136
        surface_y = 82 - ((progress * 18 + 17) // 35)
        voxels.fill_box(x, x, surface_y, surface_y, -5, 6, "D")
        if progress % 8 == 0 and surface_y > 65:
            voxels.fill_box(x, x, 65, surface_y - 1, -5, -5, "B")
            voxels.fill_box(x, x, 65, surface_y - 1, 6, 6, "B")

    # Opaque 8 x 6 x 5 freight pods. The contract's y coordinate is their
    # floor/base marker, matching the authored enemy feet at y=65.
    for pod in contract["cargo_pods"]:
        cx, base_y, cz = pod["center"]
        sx, sy, sz = pod["size"]
        x1 = cx - sx // 2
        z1 = cz - sz // 2
        for y in range(base_y, base_y + sy):
            for z in range(z1, z1 + sz):
                for x in range(x1, x1 + sx):
                    edge = x in (x1, x1 + sx - 1) or z in (z1, z1 + sz - 1) or y in (base_y, base_y + sy - 1)
                    voxels.set(x, y, z, "O" if edge and (x + z) % 5 == 0 else "B")

    # Ashen Span approach and deck. Every rise is at most one block and the
    # route remains continuous for the zero-jump Siege legs.
    for x in range(-88, -72):
        progress = x + 88
        surface_y = 64 + ((progress * 8 + 7) // 15)
        voxels.fill_box(x, x, 49, surface_y - 1, -13, 14, "B")
        voxels.fill_box(x, x, surface_y, surface_y, -13, 14, "D")
    voxels.fill_box(-72, 23, 71, 72, -13, 14, "D")
    for x in (-64, -32, 0, 23):
        for z in (-11, 12):
            voxels.support_column(x, z, 49, 70, "B", width=2)
    voxels.fill_box(-72, 23, 73, 75, -13, -13, "B")
    voxels.fill_box(-72, 23, 73, 75, 14, 14, "B")

    # Wide fall-recovery ramp from trench floor back to the west approach.
    for z in range(15, 56):
        surface_y = 72 - (((z - 15) * 24 + 20) // 40)
        if surface_y > 49:
            voxels.fill_box(-88, -77, 49, surface_y - 1, z, z, "B")
        voxels.fill_box(-88, -77, surface_y, surface_y, z, z, "D")
        voxels.fill_box(-88, -88, surface_y + 1, min(surface_y + 3, voxels.max_y), z, z, "B")
        voxels.fill_box(-77, -77, surface_y + 1, min(surface_y + 3, voxels.max_y), z, z, "B")

    # Trench-rise platform for the staged Wasp below the bridge lip.
    voxels.fill_box(-51, -45, 49, 52, -3, 3, "B")
    voxels.fill_box(-51, -45, 53, 53, -3, 3, "D")

    # Four permanent 8-wide, 6-high transverse bridge windbreaks. The
    # Phase-3 center louver is a separate reversible staging shutter below.
    for windbreak in contract["windbreaks"]:
        bounds = windbreak["bounds"]
        voxels.fill_box(*_bounds_box(bounds), "B")
        x = bounds["x"][0]
        for z in range(bounds["z"][0] + 1, bounds["z"][1], 3):
            voxels.set(x, 76, z, "O")

    # Gatehouse apron and the separate reveal corridor.
    voxels.fill_box(24, 71, 71, 72, -31, 32, "D")
    for x in (24, 40, 56, 70):
        for z in (-29, -1, 30):
            voxels.support_column(x, z, 49, 70, "B", width=2)
    voxels.fill_box(24, 71, 73, 75, -31, -31, "B")
    voxels.fill_box(24, 71, 73, 75, 32, 32, "B")
    voxels.fill_box(72, 79, 71, 72, -7, 8, "D")
    voxels.fill_box(72, 79, 73, 76, -7, -7, "B")
    voxels.fill_box(72, 79, 73, 76, 8, 8, "B")

    # Captured service gantry rooted exactly at (60,73,28).
    voxels.fill_box(58, 62, 72, 72, 26, 30, "Y")
    voxels.fill_box(58, 58, 73, 80, 28, 28, "B")
    voxels.fill_box(62, 62, 73, 80, 28, 28, "B")
    voxels.fill_box(58, 62, 80, 80, 28, 28, "V")
    voxels.set(60, 73, 28, "Y")
    voxels.set(60, 80, 28, "Y")

    # East Power Deck. Only the four-block apron/parapet rises above y=72;
    # the 88 x 88 center remains entirely cover-free and open to the sky.
    voxels.fill_box(80, 175, 71, 72, -47, 48, "D")
    for x in range(80, 176, 16):
        for z in range(-47, 49, 16):
            voxels.support_column(x, z, 49, 70, "B", width=2)
    voxels.fill_box(80, 175, 73, 75, -47, -47, "B")
    voxels.fill_box(80, 175, 73, 75, 48, 48, "B")
    voxels.fill_box(175, 175, 73, 75, -47, 48, "B")
    voxels.fill_box(80, 80, 73, 75, -47, -9, "B")
    voxels.fill_box(80, 80, 73, 75, 10, 48, "B")

    # Authored socket and recovery surfaces are part of the physical contract.
    for encounter in contract["encounters"]:
        for socket in encounter["sockets"]:
            _surface_pad(voxels, socket["position"], "Y")
        _surface_pad(voxels, encounter["recovery_anchor"], "C")
        if "player_fallback" in encounter:
            _surface_pad(voxels, encounter["player_fallback"], "C")
    _surface_pad(voxels, contract["route"]["garage_drop_deck"]["player_spawn"], "Y", radius=2)

    # Occluding reveal shutters are authored, reversible block volumes.
    for shutter in contract["staging_shutters"]:
        voxels.fill_box(*_bounds_box(shutter["bounds"]), "B")

    # Orange mission gates are generated closed. Their complete original block
    # states are subsequently journaled by Arena before any mission mutation.
    for gate in contract["gates"]:
        voxels.fill_box(*_bounds_box(gate["bounds"]), "O")

    # Four hidden contract sentinels are placed last so no scenery layer can
    # accidentally overwrite the runtime map identity/version/orientation proof.
    for sentinel in contract["markers"]["contract_sentinels"]:
        block = sentinel["block"]
        if block not in BLOCK_TO_CHAR:
            raise ContractError(f"sentinel block {block!r} is absent from the palette")
        voxels.set(*sentinel["position"], BLOCK_TO_CHAR[block])

    return voxels


def axis_token(value: int) -> str:
    return ("m" if value < 0 else "p") + f"{abs(value):02d}"


def chunk_stem(cx: int, cz: int) -> str:
    return f"chunk_{axis_token(cx)}_{axis_token(cz)}"


def authored_chunks(contract: dict) -> list[tuple[int, int]]:
    chunk_bounds = contract["bounds"]["authored_chunks"]
    return [
        (cx, cz)
        for cx in range(chunk_bounds["x"][0], chunk_bounds["x"][1] + 1)
        for cz in range(chunk_bounds["z"][0], chunk_bounds["z"][1] + 1)
    ]


def _part_json(contract: dict, voxels: VoxelMap, cx: int, cz: int) -> dict:
    min_y = contract["generation"]["part_min_y"]
    max_y = contract["generation"]["part_max_y"]
    origin_x = cx * 16
    origin_z = cz * 16
    slices: list[list[str]] = []
    for y in range(min_y, max_y + 1):
        rows = []
        for local_z in range(16):
            z = origin_z + local_z
            rows.append("".join(voxels.get(origin_x + local_x, y, z) for local_x in range(16)))
        slices.append(rows)
    return {
        "xsize": 16,
        "zsize": 16,
        "slices": slices,
        "refpalette": "mecharena_sector01:ashen_span_palette",
        "meta": [
            {"key": "nowater", "boolean": True},
        ],
    }


def _building_json(stem: str) -> dict:
    return {
        "refpalette": "mecharena_sector01:ashen_span_palette",
        "filler": "B",
        "mincellars": 0,
        "maxcellars": 0,
        "minfloors": 0,
        "maxfloors": 0,
        "allowDoors": False,
        "allowFillers": False,
        "overrideFloors": True,
        "parts": [
            {"part": f"mecharena_sector01:{stem}"},
        ],
    }


def _mods_toml(contract: dict) -> bytes:
    asset = contract["asset"]
    lost_cities_range = contract["generation"]["lost_cities_version_range"]
    text = f'''modLoader="lowcodefml"
loaderVersion="[47,)"
license="MIT"

[[mods]]
modId="{asset['mod_id']}"
version="{asset['version']}"
displayName="Cold Ruin Sector 01"
description="Authored Lost Cities assets for Operation Ashen Span. No downloaded city save is included."

[[dependencies.{asset['mod_id']}]]
modId="lostcities"
mandatory=true
versionRange="{lost_cities_range}"
ordering="AFTER"
side="BOTH"
'''
    return text.encode("utf-8")


def _common_asset_json(contract: dict, profile: dict, selected_chunks: Sequence[tuple[int, int]], mode: str) -> dict[str, bytes]:
    namespace = contract["asset"]["mod_id"]
    first_stem = chunk_stem(*selected_chunks[0])
    palette = {
        "palette": [
            {"char": char, "block": block}
            for char, block in sorted(PALETTE_BLOCKS.items())
        ]
    }
    palette_style = {
        "randompalettes": [[
            {"factor": 1.0, "palette": f"{namespace}:ashen_span_palette"}
        ]]
    }
    city_style = {
        "explosionchance": 0.0,
        "style": f"{namespace}:ashen_span_style",
        "generalblocks": {
            "ironbars": "I",
            "glowstone": "Y",
            "leaves": "B",
            "rubbledirt": "D",
        },
        "buildingsettings": {
            "minfloors": 0,
            "maxfloors": 0,
            "mincellars": 0,
            "maxcellars": 0,
            "buildingchance": 0.0,
        },
        "streetblocks": {
            "width": 8,
            "street": "D",
            "streetbase": "B",
            "streetvariant": "G",
            "border": "B",
            "wall": "B",
            "fountainchance": 0.0,
            "frontchance": 0.0,
        },
        "selectors": {
            "buildings": [{"factor": 1.0, "value": f"{namespace}:{first_stem}"}],
            "bridges": [{"factor": 1.0, "value": f"{namespace}:empty"}],
            "parks": [],
            "fountains": [],
            "stairs": [],
            "fronts": [],
            "raildungeons": [],
            "multibuildings": [],
        },
    }
    world_style = {
        "outsidestyle": f"{namespace}:ashen_span_style",
        "settings": {
            "railwayavoidance": "ignore",
            "railpartheight6": 1,
        },
        "multisettings": {
            "areasize": 1,
            "minimum": 1,
            "maximum": 1,
            "attempts": 1,
        },
        "scattered": {
            "areasize": 8,
            "chance": 0.0,
            "weightnone": 1,
            "list": [],
        },
        "citystyles": [
            {"factor": 1.0, "citystyle": f"{namespace}:ashen_span_city"}
        ],
    }
    predefined = {
        "dimension": contract["generation"]["dimension"],
        "chunkx": 0,
        "chunkz": 0,
        "radius": contract["generation"]["predefined_city_radius"],
        "citystyle": f"{namespace}:ashen_span_city",
        "buildings": [
            {
                "building": f"{namespace}:{chunk_stem(cx, cz)}",
                "chunkx": cx,
                "chunkz": cz,
                "multi": False,
                "preventruins": True,
            }
            for cx, cz in selected_chunks
        ],
        "streets": [],
    }
    empty_part = {
        "xsize": 16,
        "zsize": 16,
        "slices": [[HARD_AIR * 16 for _ in range(16)]],
        "refpalette": f"{namespace}:ashen_span_palette",
        "meta": [{"key": "nowater", "boolean": True}],
    }
    embedded_contract = dict(contract)
    embedded_contract["build"] = {
        "mode": mode,
        "selected_chunk_count": len(selected_chunks),
        "selected_chunks": [list(chunk) for chunk in selected_chunks],
    }
    internal_build = {
        "schema": BUILDER_SCHEMA,
        "builder_version": BUILDER_VERSION,
        "mode": mode,
        "canonical_timestamp": "1980-01-01T00:00:00Z",
        "source_contract_sha256": sha256_bytes(canonical_json(contract)),
        "source_profile_sha256": sha256_bytes(canonical_json(profile)),
        "authored_chunk_count": len(selected_chunks),
        "lost_cities": "7.4.13",
    }
    base = f"data/{namespace}/lostcities"
    return {
        "META-INF/mods.toml": _mods_toml(contract),
        "pack.mcmeta": canonical_json({
            "pack": {
                "description": "Cold Ruin Sector 01 — Operation Ashen Span",
                "pack_format": 15,
            }
        }),
        f"{base}/palettes/ashen_span_palette.json": canonical_json(palette),
        f"{base}/styles/ashen_span_style.json": canonical_json(palette_style),
        f"{base}/citystyles/ashen_span_city.json": canonical_json(city_style),
        f"{base}/worldstyles/ashen_span_world.json": canonical_json(world_style),
        f"{base}/predefinedcities/cold_ruin_sector_01.json": canonical_json(predefined),
        f"{base}/parts/empty.json": canonical_json(empty_part),
        f"data/{namespace}/ashen_span/cold_ruin_sector_01.json": canonical_json(embedded_contract),
        "config-template/lostcities/profiles/mecharena_sector01.json": canonical_json(profile),
        "META-INF/mecharena-sector01-build.json": canonical_json(internal_build),
    }


def build_entries(
    contract: dict,
    profile: dict,
    notices: bytes,
    *,
    chunks: Sequence[tuple[int, int]] | None = None,
) -> tuple[dict[str, bytes], VoxelMap, str]:
    all_chunks = authored_chunks(contract)
    selected = list(all_chunks if chunks is None else chunks)
    if not selected:
        raise ContractError("at least one chunk must be selected")
    if len(set(selected)) != len(selected):
        raise ContractError("duplicate selected chunks")
    unknown = sorted(set(selected) - set(all_chunks))
    if unknown:
        raise ContractError(f"selected chunks outside authored bounds: {unknown}")
    selected.sort()
    mode = "full" if selected == all_chunks else "proof"
    voxels = build_voxels(contract)
    entries = _common_asset_json(contract, profile, selected, mode)
    entries["META-INF/THIRD_PARTY_NOTICES.md"] = notices

    repo_license = REPO_ROOT / "LICENSE"
    if not repo_license.is_file():
        raise ContractError(f"missing required repository license: {repo_license}")
    entries["META-INF/LICENSE"] = repo_license.read_bytes()

    namespace = contract["asset"]["mod_id"]
    base = f"data/{namespace}/lostcities"
    for cx, cz in selected:
        stem = chunk_stem(cx, cz)
        entries[f"{base}/parts/{stem}.json"] = canonical_json(_part_json(contract, voxels, cx, cz))
        entries[f"{base}/buildings/{stem}.json"] = canonical_json(_building_json(stem))

    for name, data in entries.items():
        if name.startswith("/") or "\\" in name or ".." in Path(name).parts:
            raise ContractError(f"unsafe JAR entry {name!r}")
        if not isinstance(data, bytes):
            raise TypeError(f"entry {name} is not bytes")
    return entries, voxels, mode


def write_canonical_jar(entries: Mapping[str, bytes], output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_name(output.name + ".tmp")
    if temporary.exists():
        temporary.unlink()
    try:
        with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            for name in sorted(entries):
                info = zipfile.ZipInfo(name, ZIP_TIMESTAMP)
                info.compress_type = zipfile.ZIP_DEFLATED
                info.create_system = 3
                info.external_attr = 0o100644 << 16
                info.flag_bits |= 0x800
                archive.writestr(info, entries[name], compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
        os.replace(temporary, output)
    finally:
        if temporary.exists():
            temporary.unlink()


def emit_resource_tree(entries: Mapping[str, bytes], output_dir: Path) -> None:
    if output_dir.exists() and any(output_dir.iterdir()):
        raise ContractError(f"refusing to merge generated resources into non-empty directory: {output_dir}")
    output_dir.mkdir(parents=True, exist_ok=True)
    for name in sorted(entries):
        path = output_dir / Path(name)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(entries[name])


def make_receipt(
    contract: dict,
    profile: dict,
    source_dir: Path,
    output: Path,
    entries: Mapping[str, bytes],
    mode: str,
) -> dict:
    namespace = contract["asset"]["mod_id"]
    prefix = f"data/{namespace}/lostcities"
    part_count = sum(1 for name in entries if name.startswith(prefix + "/parts/") and name.endswith(".json"))
    building_count = sum(1 for name in entries if name.startswith(prefix + "/buildings/") and name.endswith(".json"))
    predef = json.loads(entries[f"{prefix}/predefinedcities/cold_ruin_sector_01.json"].decode("utf-8"))
    source_hashes = {
        "LICENSE": sha256_file(REPO_ROOT / "LICENSE"),
        "sector01-contract.json": sha256_file(source_dir / "sector01-contract.json"),
        "lostcities-profile.json": sha256_file(source_dir / "lostcities-profile.json"),
        "THIRD_PARTY_NOTICES.md": sha256_file(source_dir / "THIRD_PARTY_NOTICES.md"),
        "build_mecharena_sector01.py": sha256_file(SCRIPT_PATH),
    }
    return {
        "schema": BUILDER_SCHEMA,
        "builder_version": BUILDER_VERSION,
        "canonical_timestamp": "1980-01-01T00:00:00Z",
        "mode": mode,
        "map_id": contract["asset"]["map_id"],
        "map_version": contract["asset"]["map_version"],
        "mission_id": contract["asset"]["mission_id"],
        "seed": contract["generation"]["seed"],
        "profile": contract["generation"]["profile"],
        "lost_cities": contract["generation"]["lost_cities"],
        "lost_cities_version_range": contract["generation"]["lost_cities_version_range"],
        "artifact": {
            "name": output.name,
            "bytes": output.stat().st_size,
            "sha256": sha256_file(output),
        },
        "source_sha256": source_hashes,
        "resource_entry_count": len(entries),
        "part_count_including_empty": part_count,
        "building_count": building_count,
        "predefined_building_count": len(predef["buildings"]),
        "authored_chunk_count": contract["bounds"]["authored_chunks"]["count"],
        "one_chunk_proof": {
            "required_before_full_build": True,
            "canonical_chunk": [-11, -2],
            "test": "test_00_one_chunk_proof",
        },
        "world_generation": {
            "profile_source": "lostcities-profile.json",
            "dimension": contract["generation"]["dimension"],
            "origin": contract["generation"]["origin"],
            "safety_chunk_bounds": contract["bounds"]["safety_chunks"],
            "view_distance": 6,
            "simulation_distance": 6,
            "world_budget_mib": 32,
            "world_not_in_asset_jar": True,
        },
    }


def parse_chunk(value: str) -> tuple[int, int]:
    try:
        x_text, z_text = value.split(",", 1)
        return int(x_text), int(z_text)
    except (ValueError, TypeError) as exc:
        raise argparse.ArgumentTypeError("chunk must be formatted as X,Z") from exc


def build(
    source_dir: Path,
    output: Path,
    receipt_path: Path,
    *,
    proof_chunk: tuple[int, int] | None = None,
    emit_tree: Path | None = None,
) -> dict:
    contract, profile, notices = load_source(source_dir)
    chunks = None if proof_chunk is None else [proof_chunk]
    entries, _voxels, mode = build_entries(contract, profile, notices, chunks=chunks)
    write_canonical_jar(entries, output)
    receipt = make_receipt(contract, profile, source_dir, output, entries, mode)
    receipt_path.parent.mkdir(parents=True, exist_ok=True)
    receipt_path.write_bytes(canonical_json(receipt))
    if emit_tree is not None:
        emit_resource_tree(entries, emit_tree)
    return receipt


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--receipt", type=Path)
    parser.add_argument("--proof-chunk", type=parse_chunk,
                        help="build only one authored chunk (X,Z) for the scale gate")
    parser.add_argument("--emit-tree", type=Path,
                        help="optionally materialize the generated low-code resource tree into an empty directory")
    args = parser.parse_args(argv)

    source = args.source.resolve()
    suffix = "-proof" if args.proof_chunk is not None else ""
    default_output = source / "dist" / DEFAULT_ARTIFACT_NAME.replace(".jar", f"{suffix}.jar")
    output = (args.output or default_output).resolve()
    receipt = (args.receipt or output.with_suffix(".build-receipt.json")).resolve()
    try:
        result = build(
            source,
            output,
            receipt,
            proof_chunk=args.proof_chunk,
            emit_tree=args.emit_tree.resolve() if args.emit_tree else None,
        )
    except (ContractError, OSError, ValueError, zipfile.BadZipFile) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
