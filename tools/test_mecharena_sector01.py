#!/usr/bin/env python3
"""Deterministic contract tests for the mecharena_sector01 asset compiler.

The alphabetical test order is intentional: the one-chunk scale gate executes
before the full 308-chunk asset expansion.
"""

from __future__ import annotations

import importlib.util
import json
import math
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parent
BUILDER_PATH = SCRIPT_DIR / "build_mecharena_sector01.py"
SPEC = importlib.util.spec_from_file_location("build_mecharena_sector01", BUILDER_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"could not import {BUILDER_PATH}")
builder = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = builder
SPEC.loader.exec_module(builder)


class Sector01AssetTests(unittest.TestCase):
    contract: dict
    profile: dict
    notices: bytes
    full_entries: dict[str, bytes] | None = None
    full_voxels = None

    @classmethod
    def setUpClass(cls) -> None:
        cls.contract, cls.profile, cls.notices = builder.load_source(builder.DEFAULT_SOURCE)

    @staticmethod
    def _json(entries: dict[str, bytes], name: str) -> dict:
        return json.loads(entries[name].decode("utf-8"))

    @classmethod
    def _ensure_full(cls) -> tuple[dict[str, bytes], object]:
        if cls.full_entries is None:
            entries, voxels, mode = builder.build_entries(cls.contract, cls.profile, cls.notices)
            if mode != "full":
                raise AssertionError(mode)
            cls.full_entries = entries
            cls.full_voxels = voxels
        return cls.full_entries, cls.full_voxels

    def test_00_one_chunk_proof(self) -> None:
        """Scale gate: validate one representative Garage chunk before 308."""
        proof_chunk = (-11, -2)
        entries, voxels, mode = builder.build_entries(
            self.contract,
            self.profile,
            self.notices,
            chunks=[proof_chunk],
        )
        self.assertEqual("proof", mode)
        namespace = self.contract["asset"]["mod_id"]
        base = f"data/{namespace}/lostcities"
        stem = builder.chunk_stem(*proof_chunk)
        part_name = f"{base}/parts/{stem}.json"
        building_name = f"{base}/buildings/{stem}.json"
        self.assertIn(part_name, entries)
        self.assertIn(building_name, entries)
        self.assertEqual(2, sum(name.startswith(f"{base}/parts/") for name in entries))
        self.assertEqual(1, sum(name.startswith(f"{base}/buildings/") for name in entries))

        part = self._json(entries, part_name)
        self.assertEqual(16, part["xsize"])
        self.assertEqual(16, part["zsize"])
        self.assertEqual(73, len(part["slices"]))
        self.assertTrue(all(len(layer) == 16 for layer in part["slices"]))
        self.assertTrue(all(len(row) == 16 for layer in part["slices"] for row in layer))
        self.assertEqual("mecharena_sector01:ashen_span_palette", part["refpalette"])

        predefined = self._json(entries, f"{base}/predefinedcities/cold_ruin_sector_01.json")
        self.assertEqual(1, predefined["radius"])
        self.assertEqual(1, len(predefined["buildings"]))
        self.assertEqual(-11, predefined["buildings"][0]["chunkx"])
        self.assertEqual(-2, predefined["buildings"][0]["chunkz"])
        self.assertTrue(predefined["buildings"][0]["preventruins"])

        expected_sentinels = ("L", "C", "E", "W")
        for sentinel, expected in zip(self.contract["markers"]["contract_sentinels"], expected_sentinels):
            self.assertEqual(expected, voxels.get(*sentinel["position"]))
            x, y, z = sentinel["position"]
            local_x = x - proof_chunk[0] * 16
            local_z = z - proof_chunk[1] * 16
            self.assertEqual(expected, part["slices"][y - 48][local_z][local_x])

        with tempfile.TemporaryDirectory() as temporary:
            jar = Path(temporary) / "proof.jar"
            builder.write_canonical_jar(entries, jar)
            with zipfile.ZipFile(jar) as archive:
                self.assertEqual(sorted(archive.namelist()), archive.namelist())
                self.assertTrue(all(info.date_time == builder.ZIP_TIMESTAMP for info in archive.infolist()))
                mods_toml = archive.read("META-INF/mods.toml").decode("utf-8")
                self.assertIn('modLoader="lowcodefml"', mods_toml)
                self.assertIn('versionRange="[1.20-7.4.13]"', mods_toml)

    def test_10_full_22_by_14_expansion(self) -> None:
        entries, _voxels = self._ensure_full()
        namespace = self.contract["asset"]["mod_id"]
        base = f"data/{namespace}/lostcities"
        part_names = sorted(
            name for name in entries
            if name.startswith(f"{base}/parts/") and name.endswith(".json")
        )
        building_names = sorted(
            name for name in entries
            if name.startswith(f"{base}/buildings/") and name.endswith(".json")
        )
        self.assertEqual(309, len(part_names))  # 308 authored + one inert selector part
        self.assertEqual(308, len(building_names))
        predefined = self._json(entries, f"{base}/predefinedcities/cold_ruin_sector_01.json")
        self.assertEqual(308, len(predefined["buildings"]))
        coordinates = {(entry["chunkx"], entry["chunkz"]) for entry in predefined["buildings"]}
        self.assertEqual(set(builder.authored_chunks(self.contract)), coordinates)
        self.assertEqual((-11, -7), min(coordinates))
        self.assertEqual((10, 6), max(coordinates))
        self.assertTrue(all(not entry["multi"] for entry in predefined["buildings"]))
        self.assertTrue(all(entry["preventruins"] for entry in predefined["buildings"]))

        for entry in predefined["buildings"]:
            stem = entry["building"].split(":", 1)[1]
            building_name = f"{base}/buildings/{stem}.json"
            part_name = f"{base}/parts/{stem}.json"
            self.assertIn(building_name, entries)
            self.assertIn(part_name, entries)
            building = self._json(entries, building_name)
            self.assertEqual(0, building["minfloors"])
            self.assertEqual(0, building["maxfloors"])
            self.assertEqual(0, building["mincellars"])
            self.assertEqual(0, building["maxcellars"])
            self.assertTrue(building["overrideFloors"])
            self.assertFalse(building["allowDoors"])
            self.assertFalse(building["allowFillers"])
            self.assertEqual(f"mecharena_sector01:{stem}", building["parts"][0]["part"])

    def test_20_exact_gates_shutters_markers_and_socket_support(self) -> None:
        _entries, voxels = self._ensure_full()
        expected_gate_counts = {
            "G1": 136,
            "G2": 435,
            "G3": 308,
            "G4": 308,
            "G5": 221,
            "R01_INTERNAL": 221,
        }
        for gate in self.contract["gates"]:
            bounds = gate["bounds"]
            positions = [
                (x, y, z)
                for x in range(bounds["x"][0], bounds["x"][1] + 1)
                for y in range(bounds["y"][0], bounds["y"][1] + 1)
                for z in range(bounds["z"][0], bounds["z"][1] + 1)
            ]
            self.assertEqual(expected_gate_counts[gate["id"]], len(positions))
            self.assertTrue(all(voxels.get(*position) == "O" for position in positions), gate["id"])

        expected_shutters = {
            "P1_NORTH": {"x": [-151, -151], "y": [83, 89], "z": [-17, -11]},
            "P1_SOUTH": {"x": [-151, -151], "y": [83, 89], "z": [11, 17]},
            "P1_CENTER": {"x": [-153, -153], "y": [83, 89], "z": [-3, 3]},
            "P2_NORTH_CARGO_LOUVER": {"x": [-113, -113], "y": [65, 78], "z": [-28, -19]},
            "P2_SOUTH_CARGO_LOUVER": {"x": [-113, -113], "y": [65, 78], "z": [19, 28]},
            "P2_CENTER_CARGO_LOUVER": {"x": [-97, -97], "y": [65, 78], "z": [-5, 5]},
            "P3_CENTER_WINDBREAK_LOUVER": {"x": [-64, -64], "y": [73, 78], "z": [-5, 6]},
            "P4_EAST_BARRICADE": {"x": [0, 1], "y": [73, 78], "z": [-13, 14]},
            "P5_NORTH": {"x": [45, 45], "y": [73, 82], "z": [-23, -8]},
            "P5_SOUTH": {"x": [45, 45], "y": [73, 82], "z": [8, 23]},
        }
        actual_shutters = {entry["id"]: entry["bounds"] for entry in self.contract["staging_shutters"]}
        for shutter_id, expected in expected_shutters.items():
            self.assertEqual(expected, actual_shutters[shutter_id])
            bounds = actual_shutters[shutter_id]
            for x in range(bounds["x"][0], bounds["x"][1] + 1):
                for y in range(bounds["y"][0], bounds["y"][1] + 1):
                    for z in range(bounds["z"][0], bounds["z"][1] + 1):
                        self.assertEqual("B", voxels.get(x, y, z), shutter_id)

        expected_windbreaks = {
            (-64, "south"): {"x": [-64, -64], "y": [73, 78], "z": [-13, -6]},
            (-64, "north"): {"x": [-64, -64], "y": [73, 78], "z": [7, 14]},
            (-8, "south"): {"x": [-8, -8], "y": [73, 78], "z": [-13, -6]},
            (-8, "north"): {"x": [-8, -8], "y": [73, 78], "z": [7, 14]},
        }
        for windbreak in self.contract["windbreaks"]:
            self.assertEqual("transverse", windbreak["orientation"])
            self.assertEqual(
                expected_windbreaks[(windbreak["center_x"], windbreak["side"])],
                windbreak["bounds"],
            )

        sentinel_chars = ("L", "C", "E", "W")
        for sentinel, expected in zip(self.contract["markers"]["contract_sentinels"], sentinel_chars):
            self.assertEqual(expected, voxels.get(*sentinel["position"]))

        for encounter in self.contract["encounters"]:
            for socket in encounter["sockets"]:
                x, feet_y, z = socket["position"]
                self.assertNotEqual(builder.HARD_AIR, voxels.get(x, feet_y - 1, z), socket)
                self.assertEqual(builder.HARD_AIR, voxels.get(x, feet_y, z), socket)
            x, feet_y, z = encounter["recovery_anchor"]
            self.assertNotEqual(builder.HARD_AIR, voxels.get(x, feet_y - 1, z), encounter["phase"])
            self.assertEqual(builder.HARD_AIR, voxels.get(x, feet_y, z), encounter["phase"])

    def test_30_route_landmarks_shells_and_cover_contract(self) -> None:
        _entries, voxels = self._ensure_full()
        # Continuous non-jump route spot checks.
        for x in range(-168, -136):
            self.assertNotEqual(builder.HARD_AIR, voxels.get(x, 82, 0))
        for x in range(-136, -100):
            progress = x + 136
            y = 82 - ((progress * 18 + 17) // 35)
            self.assertNotEqual(builder.HARD_AIR, voxels.get(x, y, 0))
        for x in range(-100, -88):
            self.assertNotEqual(builder.HARD_AIR, voxels.get(x, 64, 0))
        for x in range(-88, -72):
            progress = x + 88
            y = 64 + ((progress * 8 + 7) // 15)
            self.assertNotEqual(builder.HARD_AIR, voxels.get(x, y, 0))
        for x in range(-72, 176):
            if x in (58, 72):  # closed authored gate/shutter foundations remain solid
                self.assertEqual("O", voxels.get(x, 72, 0))
            else:
                self.assertNotEqual(builder.HARD_AIR, voxels.get(x, 72, 0))

        # All six freight pods are exact 8 x 6 x 5 opaque solids.
        for pod in self.contract["cargo_pods"]:
            cx, base_y, cz = pod["center"]
            sx, sy, sz = pod["size"]
            positions = [
                (x, y, z)
                for x in range(cx - sx // 2, cx - sx // 2 + sx)
                for y in range(base_y, base_y + sy)
                for z in range(cz - sz // 2, cz - sz // 2 + sz)
            ]
            self.assertEqual(240, len(positions))
            self.assertTrue(all(voxels.get(*position) in builder.OPAQUE_CHARS for position in positions))

        # Shell facade glass remains restrained and every shell is 4–6 floors.
        facade = 0
        glass = 0
        for shell in self.contract["shells"]:
            self.assertIn(shell["floors"], (4, 5, 6))
            x1, x2 = shell["x"]
            z1, z2 = shell["z"]
            base_y = shell["base_y"]
            top_y = base_y + shell["floors"] * 6 - 1
            for y in range(base_y, top_y + 1):
                for z in range(z1, z2 + 1):
                    for x in range(x1, x2 + 1):
                        if x in (x1, x2) or z in (z1, z2):
                            facade += 1
                            glass += voxels.get(x, y, z) == "Q"
            self.assertTrue(any(voxels.get(x1, y, z1) != builder.HARD_AIR for y in range(base_y, top_y + 1)))
        self.assertLess(glass / facade, 0.10)

        cooling = self.contract["landmarks"]["broken_cooling_stack"]
        cx, base_y, cz = cooling["center"]
        self.assertTrue(any(voxels.get(cx + 10, y, cz) != builder.HARD_AIR for y in range(base_y, base_y + 42)))
        self.assertEqual(builder.HARD_AIR, voxels.get(cx + 10, base_y + 42, cz))
        relay = self.contract["landmarks"]["east_relay_mast"]
        rx, root_y, rz = relay["root"]
        self.assertNotEqual(builder.HARD_AIR, voxels.get(rx, root_y, rz))
        self.assertNotEqual(builder.HARD_AIR, voxels.get(rx, root_y + 47, rz))
        self.assertEqual(builder.HARD_AIR, voxels.get(rx, root_y + 48, rz))

    def test_35_all_normal_staging_sockets_are_physically_occluded(self) -> None:
        _entries, voxels = self._ensure_full()

        def positions(bounds: dict) -> set[tuple[int, int, int]]:
            return {
                (x, y, z)
                for x in range(bounds["x"][0], bounds["x"][1] + 1)
                for y in range(bounds["y"][0], bounds["y"][1] + 1)
                for z in range(bounds["z"][0], bounds["z"][1] + 1)
            }

        gate_positions = set().union(
            *(positions(gate["bounds"]) for gate in self.contract["gates"])
        )
        phase_shutters: dict[object, set[tuple[int, int, int]]] = {}
        for shutter in self.contract["staging_shutters"]:
            phase_shutters.setdefault(shutter["phase"], set()).update(
                positions(shutter["bounds"])
            )

        def first_opaque(
            start: tuple[float, float, float], target: tuple[float, float, float]
        ) -> tuple[int, int, int] | None:
            distance = math.dist(start, target)
            # Sixty-four samples per block cannot step over a full-block collider;
            # this mirrors the asset's full-cube palette without Minecraft.
            steps = max(1, math.ceil(distance * 64.0))
            for index in range(1, steps):
                ratio = index / steps
                cell = tuple(
                    math.floor(start[axis] + (target[axis] - start[axis]) * ratio)
                    for axis in range(3)
                )
                if cell in gate_positions:
                    continue  # concealment may not rely on a prior mission gate
                if voxels.get(*cell) in builder.OPAQUE_CHARS:
                    return cell
            return None

        encounters = {
            encounter["phase"]: encounter for encounter in self.contract["encounters"]
        }
        entity_heights = {"PMS07": 5.0}

        def target(socket: dict) -> tuple[float, float, float]:
            x, feet_y, z = socket["position"]
            return (
                x + 0.5,
                feet_y + entity_heights.get(socket["entity"], 3.0) * 0.5,
                z + 0.5,
            )

        def assert_matrix(
            phase: object,
            viewpoints: list[tuple[float, float, float]],
            require_phase_shutter: bool,
        ) -> None:
            for viewpoint in viewpoints:
                for socket in encounters[phase]["sockets"]:
                    hit = first_opaque(viewpoint, target(socket))
                    self.assertIsNotNone(hit, (phase, viewpoint, socket))
                    if require_phase_shutter:
                        self.assertIn(
                            hit,
                            phase_shutters[phase],
                            (phase, viewpoint, socket, hit),
                        )

        assert_matrix(1, [(-160.0, 88.37, 0.0)], True)

        phase2_views = []
        for x in (-136, -134, -132):
            progress = x + 136
            surface_y = 82 - ((progress * 18 + 17) // 35)
            phase2_views.extend(
                (float(x), surface_y + 5.37, float(z)) for z in range(-5, 7)
            )
        assert_matrix(2, phase2_views, True)

        phase3_views = []
        for x in range(-89, -83):
            surface_y = 64 if x < -88 else 64 + (((x + 88) * 8 + 7) // 15)
            phase3_views.extend(
                (float(x), surface_y + 5.37, float(z)) for z in range(-13, 15)
            )
        assert_matrix(3, phase3_views, False)

        phase4_views = [
            (float(x), 77.37, float(z))
            for x in range(-32, -27)
            for z in range(-13, 15)
        ]
        assert_matrix(4, phase4_views, False)
        # Prove the expanded barricade itself covers every fixed P4 socket after
        # discounting the earlier permanent x=-8 windbreak pair.
        phase4_barricade_views = [(-7.0, 77.37, float(z)) for z in range(-13, 15)]
        assert_matrix(4, phase4_barricade_views, True)

        phase5_views = [
            (float(x), 77.37, float(z))
            for x in range(24, 29)
            for z in range(-31, 33)
        ]
        assert_matrix("5A", phase5_views, True)

        # P1's center shutter also protects the only initial normal fallback.
        recovery = encounters[1]["recovery_anchor"]
        recovery_target = (
            recovery[0] + 0.5,
            recovery[1] + 1.5,
            recovery[2] + 0.5,
        )
        self.assertIn(
            first_opaque((-160.0, 88.37, 0.0), recovery_target),
            phase_shutters[1],
        )

        # Closed staging geometry must never overlap an exact entity footprint.
        entity_widths = {"PMS02": 3.0, "PMS07": 5.0}
        all_shutters = [
            shutter["bounds"] for shutter in self.contract["staging_shutters"]
        ]
        for phase in (1, 2, 3, 4, "5A"):
            for socket in encounters[phase]["sockets"]:
                width = entity_widths.get(socket["entity"], 0.9)
                height = entity_heights.get(socket["entity"], 3.0)
                x, feet_y, z = socket["position"]
                entity_box = (
                    x + 0.5 - width / 2,
                    x + 0.5 + width / 2,
                    feet_y,
                    feet_y + height,
                    z + 0.5 - width / 2,
                    z + 0.5 + width / 2,
                )
                for bounds in all_shutters:
                    shutter_box = (
                        bounds["x"][0],
                        bounds["x"][1] + 1,
                        bounds["y"][0],
                        bounds["y"][1] + 1,
                        bounds["z"][0],
                        bounds["z"][1] + 1,
                    )
                    overlaps = (
                        entity_box[0] < shutter_box[1]
                        and entity_box[1] > shutter_box[0]
                        and entity_box[2] < shutter_box[3]
                        and entity_box[3] > shutter_box[2]
                        and entity_box[4] < shutter_box[5]
                        and entity_box[5] > shutter_box[4]
                    )
                    self.assertFalse(overlaps, (phase, socket, bounds))

    def test_40_power_deck_clear_center_is_cover_free_and_open_sky(self) -> None:
        _entries, voxels = self._ensure_full()
        clear = self.contract["route"]["east_power_deck"]["clear_center"]
        for z in range(clear["z"][0], clear["z"][1] + 1):
            for x in range(clear["x"][0], clear["x"][1] + 1):
                self.assertNotEqual(builder.HARD_AIR, voxels.get(x, 72, z))
                for y in range(73, 121):
                    self.assertEqual(builder.HARD_AIR, voxels.get(x, y, z), (x, y, z))

    def test_50_palette_and_profile_exclude_nonauthored_systems(self) -> None:
        entries, _voxels = self._ensure_full()
        namespace = self.contract["asset"]["mod_id"]
        palette = self._json(entries, f"data/{namespace}/lostcities/palettes/ashen_span_palette.json")
        blocks = [entry["block"] for entry in palette["palette"]]
        for block in blocks:
            for fragment in builder.FORBIDDEN_BLOCK_FRAGMENTS:
                self.assertNotIn(fragment, block, block)
        self.assertIn("minecraft:structure_void", blocks)
        self.assertIn("minecraft:orange_concrete", blocks)
        self.assertIn("minecraft:cyan_concrete", blocks)
        self.assertIn("minecraft:weathered_copper", blocks)

        lostcity = self.profile["lostcity"]
        self.assertEqual(0, lostcity["highwayDistanceMask"])
        self.assertEqual(0.0, lostcity["scatteredChanceMultiplier"])
        self.assertFalse(lostcity["railwaysEnabled"])
        self.assertFalse(lostcity["generateSpawners"])
        self.assertFalse(lostcity["generateLoot"])
        self.assertFalse(lostcity["rubbleLayer"])
        self.assertEqual(0.0, lostcity["ruinChance"])
        self.assertTrue(lostcity["avoidFoliage"])
        self.assertTrue(lostcity["avoidWater"])
        world_style = self._json(entries, f"data/{namespace}/lostcities/worldstyles/ashen_span_world.json")
        self.assertEqual(0.0, world_style["scattered"]["chance"])
        self.assertEqual([], world_style["scattered"]["list"])
        self.assertEqual("mecharena_sector01:ashen_span_city", world_style["citystyles"][0]["citystyle"])

        forbidden_archive_suffixes = ("level.dat", ".mca", ".mcr", ".schematic", ".nbt")
        self.assertFalse(any(name.endswith(forbidden_archive_suffixes) for name in entries))
        self.assertIn("META-INF/THIRD_PARTY_NOTICES.md", entries)
        self.assertIn(b"MIT License", entries["META-INF/THIRD_PARTY_NOTICES.md"])
        self.assertIn(b"7.4.13", entries["META-INF/THIRD_PARTY_NOTICES.md"])
        self.assertEqual((builder.REPO_ROOT / "LICENSE").read_bytes(),
                         entries["META-INF/LICENSE"])

    def test_60_canonical_full_jar_and_receipt_are_byte_reproducible(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            jar_a = root / "a" / "sector01.jar"
            jar_b = root / "b" / "sector01.jar"
            receipt_a = root / "a" / "receipt.json"
            receipt_b = root / "b" / "receipt.json"
            result_a = builder.build(builder.DEFAULT_SOURCE, jar_a, receipt_a)
            result_b = builder.build(builder.DEFAULT_SOURCE, jar_b, receipt_b)
            self.assertEqual(jar_a.read_bytes(), jar_b.read_bytes())
            self.assertEqual(receipt_a.read_bytes(), receipt_b.read_bytes())
            self.assertEqual(result_a, result_b)
            self.assertEqual(308, result_a["building_count"])
            self.assertEqual(308, result_a["predefined_building_count"])
            self.assertEqual(309, result_a["part_count_including_empty"])
            self.assertEqual(builder.sha256_file(jar_a), result_a["artifact"]["sha256"])
            self.assertEqual(builder.sha256_file(builder.REPO_ROOT / "LICENSE"),
                             result_a["source_sha256"]["LICENSE"])
            with zipfile.ZipFile(jar_a) as archive:
                names = archive.namelist()
                self.assertEqual(sorted(names), names)
                self.assertEqual(len(names), len(set(names)))
                self.assertTrue(all(info.date_time == builder.ZIP_TIMESTAMP for info in archive.infolist()))
                for name in names:
                    if name.endswith(".json") or name == "pack.mcmeta":
                        json.loads(archive.read(name).decode("utf-8"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
