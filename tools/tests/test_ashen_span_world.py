from __future__ import annotations

import copy
from dataclasses import replace
import json
import shutil
import tempfile
from pathlib import Path
import struct
import sys
import unittest
import zipfile
import zlib


TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))

from ashen_span_world import anvil, builder, nbt  # noqa: E402
from ashen_span_world.builder import (  # noqa: E402
    CONTENT,
    SAFETY,
    WORLD_SEED,
    safety_digest,
)


class NbtTest(unittest.TestCase):
    def test_round_trip_all_contract_types(self):
        root = nbt.compound({
            "byte": nbt.Tag(nbt.TAG_BYTE, -2),
            "short": nbt.Tag(nbt.TAG_SHORT, -300),
            "int": nbt.integer(3465),
            "long": nbt.long(WORLD_SEED),
            "float": nbt.Tag(nbt.TAG_FLOAT, 1.25),
            "double": nbt.Tag(nbt.TAG_DOUBLE, 2.5),
            "bytes": nbt.Tag(nbt.TAG_BYTE_ARRAY, b"abc"),
            "string": nbt.string("ashen"),
            "list": nbt.Tag(nbt.TAG_LIST, (nbt.TAG_INT, [nbt.integer(1), nbt.integer(2)])),
            "compound": nbt.compound({"x": nbt.integer(7)}),
            "ints": nbt.Tag(nbt.TAG_INT_ARRAY, [-1, 2]),
            "longs": nbt.Tag(nbt.TAG_LONG_ARRAY, [-1, WORLD_SEED]),
        })
        encoded = nbt.encode("", root, "gzip")
        name, decoded = nbt.read(encoded, "gzip")
        self.assertEqual("", name)
        self.assertEqual(root, decoded)
        self.assertEqual(encoded, nbt.encode("", decoded, "gzip"))

    def test_dependency_free_saved_data_path(self):
        root = nbt.compound({"data": nbt.compound({"worldSeed": nbt.long(WORLD_SEED)})})
        self.assertEqual(WORLD_SEED, nbt.value_at(root, "data", "worldSeed").value)


class ContractTest(unittest.TestCase):
    def test_exact_bounds_and_digest(self):
        self.assertEqual(680, len(SAFETY))
        self.assertEqual(308, len(CONTENT))
        self.assertTrue(CONTENT.issubset(SAFETY))
        self.assertEqual(
            "cf7c9217fe947bc7048731622db60f809d12ba4987b97074416b9439ab5ca8b1",
            safety_digest(),
        )

    def test_locked_source_contract_and_release_hashes(self):
        contract_path = TOOLS.parent / "sector01-src" / "sector01-contract.json"
        contract = json.loads(contract_path.read_text(encoding="utf-8"))
        builder.validate_locked_contract(contract)
        self.assertEqual("cold_ruin_sector_01", builder.WORLD_ARCHIVE_ROOT)
        self.assertEqual("cold_ruin_sector_01_mp25_world", builder.WORLD_OUTPUT_STEM)
        self.assertEqual(builder.CONTRACT_SHA256, builder.sha256_file(contract_path))
        self.assertEqual(
            builder.GENERATION_DATAPACK_SHA256,
            builder.generation_datapack_digest(builder.GENERATION_DATAPACK_SOURCE),
        )

        changed = copy.deepcopy(contract)
        changed["route"]["east_power_deck"]["deck_y"] = 73
        with self.assertRaises(builder.BuildError):
            builder.validate_locked_contract(changed)

    def test_generation_enables_lost_cities_feature_pipeline(self):
        properties = builder._server_properties()
        self.assertIn("generate-structures=true\n", properties)
        self.assertNotIn("generate-structures=false", properties)


def _valid_level_root(generate_features: int = 1) -> nbt.Tag:
    layers = nbt.Tag(nbt.TAG_LIST, (nbt.TAG_COMPOUND, [
        nbt.compound({"block": nbt.string("minecraft:bedrock"), "height": nbt.integer(1)}),
        nbt.compound({"block": nbt.string("minecraft:deepslate"), "height": nbt.integer(47)}),
    ]))
    overworld = nbt.compound({
        "type": nbt.string("minecraft:overworld"),
        "generator": nbt.compound({
            "type": nbt.string("minecraft:flat"),
            "settings": nbt.compound({
                "biome": nbt.string(builder.GENERATION_BIOME),
                "features": nbt.Tag(nbt.TAG_BYTE, 1),
                "lakes": nbt.Tag(nbt.TAG_BYTE, 0),
                "layers": layers,
            }),
        }),
    })
    rules = nbt.compound({name: nbt.string(value) for name, value in builder.FIXED_GAME_RULES.items()})
    data = nbt.compound({
        "DataVersion": nbt.integer(builder.DATA_VERSION),
        "Version": nbt.compound({
            "Id": nbt.integer(builder.DATA_VERSION),
            "Name": nbt.string("1.20.1"),
            "Series": nbt.string("main"),
            "Snapshot": nbt.Tag(nbt.TAG_BYTE, 0),
        }),
        "WorldGenSettings": nbt.compound({
            "seed": nbt.long(builder.WORLD_SEED),
            "generate_features": nbt.Tag(nbt.TAG_BYTE, generate_features),
            "dimensions": nbt.compound({builder.WORLD_DIMENSION: overworld}),
        }),
        "SpawnX": nbt.integer(builder.SPAWN[0]),
        "SpawnY": nbt.integer(builder.SPAWN[1]),
        "SpawnZ": nbt.integer(builder.SPAWN[2]),
        "SpawnAngle": nbt.Tag(nbt.TAG_FLOAT, builder.SPAWN_ANGLE),
        "DayTime": nbt.long(builder.DAY_TIME),
        "Difficulty": nbt.Tag(nbt.TAG_BYTE, 2),
        "raining": nbt.Tag(nbt.TAG_BYTE, 0),
        "thundering": nbt.Tag(nbt.TAG_BYTE, 0),
        "rainTime": nbt.integer(0),
        "thunderTime": nbt.integer(0),
        "clearWeatherTime": nbt.integer(1_000_000),
        "GameRules": rules,
        "DataPacks": nbt.compound({
            "Enabled": nbt.Tag(nbt.TAG_LIST, (nbt.TAG_STRING, [
                nbt.string("vanilla"),
                nbt.string("mod:forge"),
                nbt.string("mod:lostcities"),
                nbt.string("mod:mecharena_sector01"),
                nbt.string(f"file/{builder.GENERATION_PACK_NAME}"),
            ])),
        }),
    })
    return nbt.compound({"Data": data})


class WorldMetadataTest(unittest.TestCase):
    def test_validates_locked_level_dat_facts(self):
        with tempfile.TemporaryDirectory() as tmp:
            level_dat = Path(tmp) / "level.dat"
            level_dat.write_bytes(nbt.encode("", _valid_level_root(), "gzip"))
            facts = builder.verify_level_dat(level_dat)
            self.assertEqual("minecraft:overworld", facts["dimension"])
            self.assertEqual([-160, 83, 0], facts["spawn"])
            self.assertEqual("clear", facts["weather"])

    def test_rejects_feature_disabled_flat_world(self):
        with tempfile.TemporaryDirectory() as tmp:
            level_dat = Path(tmp) / "level.dat"
            level_dat.write_bytes(nbt.encode("", _valid_level_root(generate_features=0), "gzip"))
            with self.assertRaisesRegex(builder.BuildError, "generate_features"):
                builder.verify_level_dat(level_dat)


class ArchiveTest(unittest.TestCase):
    def test_canonical_archive_uses_locked_root_and_reconstructs(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            world = root / "world"
            world.mkdir()
            (world / "level.dat").write_bytes(b"level")
            archive = root / "world.zip"
            builder.deterministic_zip(world, archive)
            with zipfile.ZipFile(archive) as source:
                self.assertTrue(all(info.create_system == 3 for info in source.infolist()))
            reconstructed, expanded = builder._reconstruct_archive(archive, root / "out")
            self.assertEqual(root / "out" / "cold_ruin_sector_01", reconstructed)
            self.assertEqual(b"level", (reconstructed / "level.dat").read_bytes())
            self.assertEqual(5, expanded)

    def test_rejects_old_namespace_and_unsafe_member(self):
        for member in ("cold_ruin_sector01/level.dat", "cold_ruin_sector_01/../escape"):
            with self.subTest(member=member), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                archive = root / "bad.zip"
                info = zipfile.ZipInfo(member, builder.CANONICAL_ZIP_TIME)
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o100644 << 16
                with zipfile.ZipFile(archive, "w") as output:
                    output.writestr(info, b"bad")
                with self.assertRaises(builder.BuildError):
                    builder._reconstruct_archive(archive, root / "out")

    def test_curated_tree_rejects_volatile_runtime_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            world = Path(tmp) / "world"
            for directory in ("data", "region", "serverconfig"):
                (world / directory).mkdir(parents=True, exist_ok=True)
            shutil.copytree(
                builder.GENERATION_DATAPACK_SOURCE,
                world / "datapacks" / builder.GENERATION_PACK_NAME,
            )
            (world / "level.dat").write_bytes(b"level")
            (world / "data" / "mecharena_sector01_contract.dat").write_bytes(b"contract")
            (world / "serverconfig" / "lostcities-server.toml").write_text("profile", encoding="utf-8")
            for name in builder._expected_region_names():
                (world / "region" / name).write_bytes(b"region")
            builder._curated_file_manifest(world)
            (world / "session.lock").write_bytes(b"volatile")
            with self.assertRaisesRegex(builder.BuildError, "file set is not curated"):
                builder._curated_file_manifest(world)


def _fake_paths(root: Path) -> builder.Paths:
    return builder.Paths(
        repo=root,
        project=root,
        work=root / "work",
        runtime=root / "runtime",
        logs=root / "logs",
        evidence=root / "evidence",
        forge_runtime=root / "forge",
        forge_runtime_sha256="e" * 64,
        java=None,
        java_sha256="f" * 64,
        lost_cities=root / "lostcities.jar",
        asset=root / "mecharena_sector01-1.0.0-mp25.jar",
        profile=root / "lostcities-profile.json",
        contract=root / "sector01-contract.json",
        output_world=root / builder.WORLD_OUTPUT_STEM,
        archive=root / f"{builder.WORLD_OUTPUT_STEM}.zip",
        receipt=root / f"{builder.WORLD_OUTPUT_STEM}.build-receipt.json",
    )


def _write_complete_generation_evidence(paths: builder.Paths) -> tuple[dict, dict]:
    paths.logs.mkdir(parents=True)
    proof_tokens = [
        builder._chunk_loaded_token("PROOF", -11, -2),
        "ASHEN_PROOF_FINAL_DONE",
        *(f"ASHEN_PROOF_MARKER_{index}_OK" for index in range(1, 5)),
    ]
    (paths.logs / builder.PROOF_LOG_NAME).write_text("\n".join(proof_tokens), encoding="utf-8")
    full_tokens = ["ASHEN_FULL_FINAL_DONE"]
    for index, (min_x, max_x, min_z, max_z) in enumerate(builder.GENERATION_BATCHES, 1):
        full_tokens.append(f"ASHEN_BATCH_{index}_FINAL_DONE")
        full_tokens.extend(
            builder._chunk_loaded_token(f"BATCH_{index}", x, z)
            for x in range(min_x, max_x + 1)
            for z in range(min_z, max_z + 1)
        )
    (paths.logs / builder.FULL_LOG_NAME).write_text("\n".join(full_tokens), encoding="utf-8")
    builder._publish_generation_evidence(paths)
    return builder._generation_evidence_from_logs(paths)


class RuntimePinTest(unittest.TestCase):
    def test_runtime_and_java_pins_are_verified_before_execution(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            paths = _fake_paths(root)
            library = paths.forge_runtime / "libraries" / "forge-runtime.jar"
            library.parent.mkdir(parents=True)
            library.write_bytes(b"pinned-forge-runtime")
            java = root / "java.exe"
            java.write_bytes(b"pinned-java-runtime")
            paths = replace(
                paths,
                forge_runtime_sha256=builder.runtime_tree_digest(library.parent),
                java=java,
                java_sha256=builder.sha256_file(java),
            )

            self.assertEqual(
                (paths.forge_runtime_sha256, paths.java_sha256),
                builder._validate_generation_runtime(paths),
            )
            library.write_bytes(b"mutated-forge-runtime")
            with self.assertRaisesRegex(builder.BuildError, "Forge runtime hash mismatch"):
                builder._validate_generation_runtime(paths)


class PipelineEvidenceTest(unittest.TestCase):
    def test_generation_log_evidence_requires_every_bounded_chunk(self):
        with tempfile.TemporaryDirectory() as tmp:
            paths = _fake_paths(Path(tmp))
            proof, generation = _write_complete_generation_evidence(paths)
            self.assertEqual(4, proof["markers"])
            self.assertEqual(680, generation["generated_chunks"])
            self.assertEqual([240, 240, 200], [batch["count"] for batch in generation["batches"]])
            self.assertEqual("sector01-world/evidence/one-chunk-proof.log", proof["log"])

            full_log = paths.evidence / builder.FULL_LOG_NAME
            full_log.write_text(full_log.read_text(encoding="utf-8").rsplit("\n", 1)[0], encoding="utf-8")
            with self.assertRaisesRegex(builder.BuildError, "missing evidence"):
                builder._generation_evidence_from_logs(paths)

    def test_receipt_cannot_replace_independent_archive_measurement(self):
        with tempfile.TemporaryDirectory() as tmp:
            paths = _fake_paths(Path(tmp))
            proof, generation = _write_complete_generation_evidence(paths)
            result = {
                "archive_sha256": "a" * 64,
                "archive_bytes": 100,
                "archive_uncompressed_bytes": 200,
                "curated_tree_sha256": "b" * 64,
            }
            frozen = {"curated_tree_sha256": "b" * 64}
            receipt = builder._make_receipt(
                paths, "e" * 64, "f" * 64, "1" * 64, "2" * 64,
                proof, generation, frozen, result,
            )
            builder._verify_receipt(
                receipt, result, paths, "e" * 64, "f" * 64, "1" * 64, "2" * 64
            )
            receipt["one_chunk_proof"]["log_sha256"] = "0" * 64
            with self.assertRaisesRegex(builder.BuildError, "actual proof log"):
                builder._verify_receipt(
                    receipt, result, paths, "e" * 64, "f" * 64, "1" * 64, "2" * 64
                )
            receipt["one_chunk_proof"] = proof
            receipt["world"]["sha256"] = "0" * 64
            with self.assertRaisesRegex(builder.BuildError, "independently measured"):
                builder._verify_receipt(
                    receipt, result, paths, "e" * 64, "f" * 64, "1" * 64, "2" * 64
                )


class AnvilTest(unittest.TestCase):
    def test_rejects_truncated_region(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "r.0.0.mca"
            path.write_bytes(b"bad")
            with self.assertRaises(anvil.AnvilError):
                anvil.read_entries(path)

    def test_reads_complete_unpadded_live_tail_and_block_palette(self):
        palette_entry = nbt.compound({"Name": nbt.string("minecraft:lodestone")})
        section = nbt.compound({
            "Y": nbt.Tag(nbt.TAG_BYTE, 4),
            "block_states": nbt.compound({
                "palette": nbt.Tag(nbt.TAG_LIST, (nbt.TAG_COMPOUND, [palette_entry])),
            }),
        })
        root = nbt.compound({
            "DataVersion": nbt.integer(builder.DATA_VERSION),
            "xPos": nbt.integer(0),
            "zPos": nbt.integer(0),
            "Status": nbt.string("minecraft:full"),
            "sections": nbt.Tag(nbt.TAG_LIST, (nbt.TAG_COMPOUND, [section])),
        })
        payload = zlib.compress(nbt.encode("", root))
        record = struct.pack(">I", len(payload) + 1) + b"\x02" + payload
        entry = anvil.Entry(0, 0, 2, payload, record)
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "r.0.0.mca"
            anvil._write_region(path, 0, 0, [entry])
            path.write_bytes(path.read_bytes()[: 8192 + len(record)])
            parsed = anvil.read_entries(path)[(0, 0)]
            self.assertEqual("full", anvil.status(parsed))
            self.assertEqual((builder.DATA_VERSION, 0, 0, "full"), anvil.chunk_metadata(parsed))
            self.assertEqual({"minecraft:lodestone"}, anvil.palette_names_from_root(anvil.unpack_nbt(parsed)))
            self.assertEqual(
                ("minecraft:lodestone", {}),
                anvil.block_state(parsed, 0, 79, 0),
            )


if __name__ == "__main__":
    unittest.main()
