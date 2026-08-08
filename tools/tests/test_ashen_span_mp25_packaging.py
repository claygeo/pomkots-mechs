"""End-to-end tests for the isolated Operation Ashen Span packager/verifier."""

from __future__ import annotations

import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock
import zipfile


TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))


def _load(name: str, filename: str):
    spec = importlib.util.spec_from_file_location(name, TOOLS / filename)
    if spec is None or spec.loader is None:
        raise RuntimeError(filename)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


builder = _load("ashen_span_mp25_builder_test", "build_ashen_span_mp25.py")
verifier = _load("ashen_span_mp25_verifier_test", "verify_ashen_span_mp25.py")
REAL_BUILDER_VALIDATE_SOURCE_COMMIT = builder.validate_source_commit
REAL_BUILDER_SAFETY_GATE = builder.require_safety_envelope_resolution
REAL_VERIFIER_SAFETY_GATE = verifier.require_safety_envelope_resolution


def ordinary_zip(entries: dict[str, bytes]) -> bytes:
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as archive:
        for name, blob in entries.items():
            archive.writestr(name, blob)
    return output.getvalue()


def dependency_jar(name: str) -> bytes:
    mod_id = {"architectury": "architectury", "cloth-config": "cloth_config", "geckolib": "geckolib"}[name]
    declared = "GNU LGPLv3" if name != "geckolib" else "MIT"
    mods = f'''modLoader="javafml"
loaderVersion="[47,)"
license="{declared}"
[[mods]]
modId="{mod_id}"
version="1.0.0-test"
displayName="{name}"
'''.encode()
    entries = {
        "META-INF/MANIFEST.MF": f"Manifest-Version: 1.0\nImplementation-Title: {name}\n".encode(),
        "META-INF/mods.toml": mods,
    }
    if name == "cloth-config":
        entries["LICENSE.md"] = (
            b"GNU Lesser General Public License\nVersion 3, 29 June 2007\n"
            b"This is the deterministic unit-test license fixture.\n"
        )
    elif name == "geckolib":
        entries["LICENSE"] = b"MIT License\nPermission is hereby granted, free of charge.\n"
    return ordinary_zip(entries)


def dependency_record(path: str, blob: bytes) -> dict[str, object]:
    return {
        "path": path,
        "hashes": {"sha1": builder.sha1_bytes(blob), "sha512": builder.sha512_bytes(blob)},
        "env": {"client": "required", "server": "required"},
        "downloads": [f"https://example.invalid/{Path(path).name}"],
        "fileSize": len(blob),
    }


def mp25_jar() -> bytes:
    mods = b'''modLoader="javafml"
loaderVersion="[47,)"
license="MIT"
[[mods]]
modId="pomkotsmechs"
version="0.0.1-alpha.7-mp.25"
displayName="Pomkots Mechs"
[[dependencies.pomkotsmechs]]
modId="cloth_config"
mandatory=true
versionRange="[11.1.118,)"
ordering="NONE"
side="BOTH"
'''
    members = {
        "LICENSE": b"MIT License\nPermission is hereby granted, free of charge.\n",
        "META-INF/mods.toml": mods,
        "grcmcs/minecraft/mods/pomkotsmechs/arena/ArenaManager.class": b"arena",
        "grcmcs/minecraft/mods/pomkotsmechs/arena/AshenSpanDefinition.class": b"definition",
        "grcmcs/minecraft/mods/pomkotsmechs/arena/AshenSpanDirector.class": b"director",
        "grcmcs/minecraft/mods/pomkotsmechs/arena/AshenSpanMapContract.class": b"map",
        "grcmcs/minecraft/mods/pomkotsmechs/arena/MissionGateLedger.class": b"ledger",
        "grcmcs/minecraft/mods/pomkotsmechs/entity/vehicle/custom/ArenaRivalPmvc01Entity.class": b"rival",
    }
    return ordinary_zip(members)


def lost_cities_jar() -> bytes:
    mods = b'''modLoader="javafml"
loaderVersion="[47,)"
license="MIT License"
[[mods]]
modId="lostcities"
version="${file.jarVersion}"
displayName="LostCities"
'''
    manifest = b"Manifest-Version: 1.0\nImplementation-Version: 1.20-7.4.13\n"
    return ordinary_zip({"META-INF/mods.toml": mods, "META-INF/MANIFEST.MF": manifest})


def asset_profile() -> bytes:
    source = TOOLS.parent / "sector01-src" / "lostcities-profile.json"
    blob = builder.canonical_json(json.loads(source.read_bytes()))
    if len(blob) != builder.ASSET_PROFILE_BYTES:
        raise RuntimeError("test profile byte count drifted from production pin")
    if builder.sha256_bytes(blob) != builder.ASSET_PROFILE_SHA256:
        raise RuntimeError("test profile hash drifted from production pin")
    return blob


def asset_jar(profile: bytes | None = None) -> bytes:
    mods = b'''modLoader="lowcodefml"
loaderVersion="[47,)"
license="MIT"
[[mods]]
modId="mecharena_sector01"
version="1.0.0-mp25"
displayName="Cold Ruin Sector 01"
[[dependencies.mecharena_sector01]]
modId="lostcities"
mandatory=true
versionRange="[1.20-7.4.13]"
ordering="AFTER"
side="BOTH"
'''
    notice = (
        b"# Third-party notices\n\nThe Lost Cities - MIT License. "
        b"Permission is hereby granted, free of charge. "
        b"This original asset contains no downloaded city.\n"
    )
    return ordinary_zip({
        "META-INF/LICENSE": b"MIT License\nPermission is hereby granted, free of charge.\n",
        "META-INF/THIRD_PARTY_NOTICES.md": notice,
        "META-INF/mecharena-sector01-build.json": b"{}\n",
        "META-INF/mods.toml": mods,
        builder.ASSET_PROFILE_MEMBER: profile if profile is not None else asset_profile(),
        "data/mecharena_sector01/ashen_span/cold_ruin_sector_01.json": b"{}\n",
        "data/mecharena_sector01/lostcities/predefinedcities/cold_ruin_sector_01.json": b"{}\n",
    })


class Fixture:
    def __init__(self, root: Path) -> None:
        self.root = root
        self.architectury = dependency_jar("architectury")
        self.cloth = dependency_jar("cloth-config")
        self.gecko = dependency_jar("geckolib")
        self.dep_blobs = {
            "architectury": self.architectury,
            "cloth_config": self.cloth,
            "geckolib": self.gecko,
        }
        self.dep_paths: dict[str, Path] = {}
        for key, path in builder.DEPENDENCY_PATHS.items():
            target = root / PureName(path)
            target.write_bytes(self.dep_blobs[key])
            self.dep_paths[key] = target

        index = {
            "dependencies": {"forge": "47.3.3", "minecraft": "1.20.1"},
            "files": [dependency_record(builder.DEPENDENCY_PATHS[key], self.dep_blobs[key])
                      for key in ("architectury", "cloth_config", "geckolib")],
            "formatVersion": 1,
            "game": "minecraft",
            "name": "Mech Arena 0.8 - Solo Combat MP24 RC4",
            "summary": "sealed fixture",
            "versionId": builder.BASE_VERSION_ID,
        }
        base_mod = b"sealed-mp24-fixture"
        base_entries = {
            builder.INDEX_PATH: builder.canonical_json(index),
            builder.OPTIONS_PATH: builder.canonical_text("renderDistance:4\nsimulationDistance:6\nmaxFps:30"),
            builder.CLIENT_NOTICE_PATH: b"# Inherited notices\n\nPomkot's Mechs - MIT License\n",
            builder.BASE_MOD_PATH: base_mod,
        }
        self.base_blob = builder.make_canonical_zip(base_entries, compression=zipfile.ZIP_STORED)
        self.base = root / "sealed-mp24.mrpack"; self.base.write_bytes(self.base_blob)
        self.base_mod_sha = builder.sha256_bytes(base_mod)

        self.mp25_blob = mp25_jar(); self.mp25 = root / builder.MP25_NAME; self.mp25.write_bytes(self.mp25_blob)
        self.lost_blob = lost_cities_jar(); self.lost = root / builder.LOST_CITIES_NAME; self.lost.write_bytes(self.lost_blob)
        self.asset_profile = asset_profile()
        self.asset_blob = asset_jar(self.asset_profile); self.asset = root / builder.ASSET_NAME; self.asset.write_bytes(self.asset_blob)
        self.asset_sha = builder.sha256_bytes(self.asset_blob)
        asset_receipt = {
            "artifact": {"bytes": len(self.asset_blob), "name": builder.ASSET_NAME, "sha256": self.asset_sha.lower()},
            "authored_chunk_count": 308,
            "lost_cities_version_range": "[1.20-7.4.13]",
            "map_id": "cold_ruin_sector_01",
            "mission_id": "operation_ashen_span",
            "mode": "full",
            "predefined_building_count": 308,
            "profile": builder.WORLD_PROFILE_NAME,
            "source_sha256": {"lostcities-profile.json": builder.WORLD_PROFILE_SHA256},
        }
        self.asset_receipt = root / "asset-receipt.json"; self.asset_receipt.write_bytes(builder.canonical_json(asset_receipt))

        self.world = root / "curated-world"; self.world.mkdir()
        world_files = {
            "level.dat": b"level-fixture",
            "data/mecharena_sector01_contract.dat": b"contract-fixture",
            "serverconfig/lostcities-server.toml": b'selectedProfile = "mecharena_sector01"\n',
            "region/r.0.0.mca": b"region-fixture",
        }
        datapack_source = TOOLS / "ashen_span_world" / "generation_datapack"
        for name in builder.GENERATION_DATAPACK_PATHS:
            relative = name.removeprefix(builder.GENERATION_DATAPACK_ROOT + "/")
            world_files[name] = (datapack_source / Path(relative)).read_bytes()
        for name, blob in world_files.items():
            path = self.world / Path(name); path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(blob)
        input_world_entries = {f"{builder.WORLD_ARCHIVE_ROOT}/{name}": blob for name, blob in world_files.items()}
        self.world_blob = builder.make_canonical_zip(input_world_entries)
        self.packaged_world_sha = builder.sha256_bytes(builder.make_canonical_zip({
            f"{builder.SERVER_SAVE_ROOT}/{name}": blob for name, blob in world_files.items()
        }))
        self.world_archive = root / builder.WORLD_BUILDER_ARCHIVE_NAME; self.world_archive.write_bytes(self.world_blob)
        self.world_sha = builder.sha256_bytes(self.world_blob)
        self.world_facts = {
            "authored_chunk_count": 308,
            "curated_tree_sha256": builder.world_builder_curated_tree_sha256(world_files).lower(),
            "terrain_chunk_count": 680,
        }
        world_receipt = {
            "asset": {"name": builder.ASSET_NAME, "sha256": self.asset_sha.lower()},
            "builder": builder.WORLD_BUILDER_ID,
            "canonical_zip_timestamp": "1980-01-01T00:00:00Z",
            "contract": {
                "compressed_budget_bytes": builder.MAX_WORLD_BYTES,
                "content_chunk_count": 308,
                "data_version": builder.WORLD_DATA_VERSION,
                "dimension": builder.WORLD_DIMENSION,
                "safety_chunk_count": 680,
                "safety_chunk_digest": builder.WORLD_SAFETY_CHUNK_DIGEST,
                "simulation_distance": 6,
                "view_distance": 6,
            },
            "determinism": {
                "independent_forge_generation_byte_equality_claimed": False,
                "scope": "canonical archive bytes for this verified frozen curated world tree",
            },
            "forge": "47.3.3",
            "frozen_tree_verification": self.world_facts,
            "generation_datapack": {
                "biome": builder.GENERATION_DATAPACK_BIOME,
                "name": builder.GENERATION_DATAPACK_NAME,
                "sha256": builder.GENERATION_DATAPACK_SHA256,
            },
            "generation_runtime": {
                "forge_libraries_sha256": builder.WORLD_FORGE_LIBRARIES_SHA256,
                "java_executable_sha256": builder.WORLD_JAVA_EXECUTABLE_SHA256,
            },
            "lost_cities": {"sha256": builder.sha256_bytes(self.lost_blob).lower(), "version": "7.4.13"},
            "map_id": "cold_ruin_sector_01",
            "map_version": 1,
            "minecraft": "1.20.1",
            "mission_id": "operation_ashen_span",
            "profile": {"name": builder.WORLD_PROFILE_NAME, "sha256": builder.WORLD_PROFILE_SHA256},
            "schema": builder.WORLD_BUILDER_SCHEMA,
            "seed": builder.WORLD_SEED,
            "source_contract": {
                "name": builder.WORLD_SOURCE_CONTRACT_NAME,
                "sha256": builder.WORLD_SOURCE_CONTRACT_SHA256,
            },
            "verification": {
                **self.world_facts,
                "archive_bytes": len(self.world_blob),
                "archive_sha256": self.world_sha.lower(),
                "archive_uncompressed_bytes": sum(len(blob) for blob in world_files.values()),
            },
            "world": {
                "archive_root": builder.WORLD_ARCHIVE_ROOT,
                "bytes": len(self.world_blob),
                "curated_tree_sha256": builder.world_builder_curated_tree_sha256(world_files).lower(),
                "name": self.world_archive.name,
                "sha256": self.world_sha.lower(),
                "uncompressed_bytes": sum(len(blob) for blob in world_files.values()),
            },
        }
        self.world_receipt = root / "world-receipt.json"; self.world_receipt.write_bytes(builder.canonical_json(world_receipt))
        self.output = root / "candidate"

    def builder_inputs(self):
        return builder.CandidateInputs(
            self.base, self.mp25, builder.sha256_bytes(self.mp25_blob), self.lost,
            self.asset, self.asset_sha, self.asset_receipt, self.world,
            self.world_archive, self.world_sha, self.world_receipt,
            self.dep_paths["architectury"], self.dep_paths["cloth_config"], self.dep_paths["geckolib"],
            "0123456789abcdef0123456789abcdef01234567",
        )

    def verifier_inputs(self):
        return verifier.Inputs(
            self.base, self.mp25, builder.sha256_bytes(self.mp25_blob), self.lost,
            self.asset, self.asset_sha, self.asset_receipt, self.world,
            self.world_archive, self.world_sha, self.world_receipt,
            self.dep_paths["architectury"], self.dep_paths["cloth_config"], self.dep_paths["geckolib"],
            "0123456789abcdef0123456789abcdef01234567",
        )


def PureName(path: str) -> str:
    return path.rsplit("/", 1)[-1]


class PackagingTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.fixture = Fixture(self.root)
        patches = [
            (builder, "BASE_SHA256", builder.sha256_bytes(self.fixture.base_blob)),
            (builder, "BASE_BYTES", len(self.fixture.base_blob)),
            (builder, "BASE_MOD_SHA256", self.fixture.base_mod_sha),
            (builder, "LOST_CITIES_SHA256", builder.sha256_bytes(self.fixture.lost_blob)),
            (builder, "LOST_CITIES_BYTES", len(self.fixture.lost_blob)),
            (builder, "RC6_MP25_SHA256", builder.sha256_bytes(self.fixture.mp25_blob)),
            (builder, "RC6_MP25_BYTES", len(self.fixture.mp25_blob)),
            (builder, "require_safety_envelope_resolution", lambda: None),
            (builder, "RC5_ASSET_SHA256", self.fixture.asset_sha),
            (builder, "RC5_WORLD_BUILDER_ARCHIVE_SHA256", self.fixture.world_sha),
            (builder, "RC5_PACKAGED_WORLD_SHA256", self.fixture.packaged_world_sha),
            (verifier, "BASE_SHA256", builder.sha256_bytes(self.fixture.base_blob)),
            (verifier, "BASE_BYTES", len(self.fixture.base_blob)),
            (verifier, "BASE_MOD_SHA256", self.fixture.base_mod_sha),
            (verifier, "LOST_CITIES_SHA256", builder.sha256_bytes(self.fixture.lost_blob)),
            (verifier, "LOST_CITIES_BYTES", len(self.fixture.lost_blob)),
            (verifier, "RC6_MP25_SHA256", builder.sha256_bytes(self.fixture.mp25_blob)),
            (verifier, "RC6_MP25_BYTES", len(self.fixture.mp25_blob)),
            (verifier, "require_safety_envelope_resolution", lambda: None),
            (verifier, "RC5_ASSET_SHA256", self.fixture.asset_sha),
            (verifier, "RC5_WORLD_BUILDER_ARCHIVE_SHA256", self.fixture.world_sha),
            (verifier, "RC5_PACKAGED_WORLD_SHA256", self.fixture.packaged_world_sha),
            (builder, "validate_source_commit", lambda value: value.lower()),
            (verifier, "validate_source_commit", lambda value: value.lower()),
            (builder, "read_committed_builder_blob", lambda _commit: b"canonical-builder-source\n"),
            (verifier, "read_committed_builder_blob", lambda _commit: b"canonical-builder-source\n"),
            (builder, "verify_bounded_world", lambda _root, _asset, _tree: self.fixture.world_facts),
            (verifier, "verify_bounded_world", lambda _root, _asset, _tree: self.fixture.world_facts),
        ]
        for module, name, value in patches:
            patcher = mock.patch.object(module, name, value)
            patcher.start(); self.addCleanup(patcher.stop)

    def build_and_publish(self) -> dict[str, bytes]:
        first = builder.build_candidate(self.fixture.builder_inputs())
        second = builder.build_candidate(self.fixture.builder_inputs())
        self.assertEqual(first, second)
        builder.publish_atomic(self.fixture.output, first)
        return first

    def test_00_two_builds_equal_and_independent_verifier_passes(self) -> None:
        files = self.build_and_publish()
        self.assertEqual(builder.OUTPUT_NAMES if hasattr(builder, "OUTPUT_NAMES") else set(files), set(files))
        result = verifier.verify_candidate(self.fixture.verifier_inputs(), self.fixture.output)
        self.assertEqual(11, result["files"])
        self.assertEqual(1, result["cloth_config_inputs"])
        self.assertFalse(result["live_qualified"])
        self.assertEqual(verifier.candidate_tree_sha256(files), result["candidate_tree_sha256"])
        self.assertRegex(result["candidate_tree_sha256"], r"^[0-9A-F]{64}$")

    def test_01_production_candidate_is_unconditionally_blocked(self) -> None:
        files = builder.build_candidate(self.fixture.builder_inputs())
        builder.publish_atomic(self.fixture.output, files)
        with self.assertRaisesRegex(builder.BuildError, "packaging is blocked unconditionally"):
            REAL_BUILDER_SAFETY_GATE()
        with self.assertRaisesRegex(verifier.VerifyError, "verification is blocked unconditionally"):
            REAL_VERIFIER_SAFETY_GATE()

    def test_02_historical_tools_expose_no_mutable_erratum_latch(self) -> None:
        self.assertFalse(hasattr(builder, "SAFETY_ENVELOPE_ERRATUM_ID"))
        self.assertFalse(hasattr(verifier, "SAFETY_ENVELOPE_ERRATUM_ID"))

    def test_10_client_has_one_save_three_exact_mods_and_6_6(self) -> None:
        files = builder.build_candidate(self.fixture.builder_inputs())
        entries = builder.read_zip(files[builder.MRPACK_NAME], "client", canonical=True,
                                   compression=zipfile.ZIP_DEFLATED)
        saves = [name for name in entries if name.startswith("overrides/saves/")]
        self.assertTrue(saves)
        self.assertTrue(all(name.startswith(builder.CLIENT_SAVE_ROOT + "/") for name in saves))
        self.assertEqual(self.fixture.mp25_blob, entries[builder.CLIENT_MOD_PATHS["mp25"]])
        self.assertEqual(self.fixture.lost_blob, entries[builder.CLIENT_MOD_PATHS["lost_cities"]])
        self.assertEqual(self.fixture.asset_blob, entries[builder.CLIENT_MOD_PATHS["asset"]])
        self.assertEqual(
            self.fixture.asset_profile,
            entries[builder.CLIENT_LOST_CITIES_PROFILE_PATH],
        )
        self.assertEqual(
            {builder.CLIENT_LOST_CITIES_PROFILE_PATH},
            {name for name in entries if name.startswith("overrides/config/lostcities/profiles/")},
        )
        options = entries[builder.OPTIONS_PATH].decode()
        self.assertEqual(1, options.count("renderDistance:6\n"))
        self.assertEqual(1, options.count("simulationDistance:6\n"))
        self.assertEqual(builder.pomkots_safety_config(),
                         entries[builder.CLIENT_POMKOTS_CONFIG_PATH])
        config = json.loads(entries[builder.CLIENT_POMKOTS_CONFIG_PATH])
        self.assertFalse(config["enableEntityBlockDestruction"])
        self.assertFalse(config["enablePlayerVehicleBlockDestruction"])
        self.assertEqual(
            builder.archive_license_paths(client=True),
            {name for name in entries if name.startswith(builder.CLIENT_LICENSE_ROOT + "/")},
        )
        self.assertTrue(all(
            not any(token in name.casefold() for token in builder.FORBIDDEN_QUALIFICATION_PATH_TOKENS)
            for name in entries
        ))

    def test_20_server_has_exact_runtime_one_cloth_and_world(self) -> None:
        files = builder.build_candidate(self.fixture.builder_inputs())
        entries = builder.read_zip(files[builder.SERVER_NAME], "server", canonical=True,
                                   compression=zipfile.ZIP_DEFLATED)
        mods = [name for name in entries if name.startswith("mods/")]
        self.assertEqual(6, len(mods))
        self.assertEqual(1, sum("cloth-config" in name for name in mods))
        self.assertEqual(1, entries["server.properties"].decode().count("view-distance=6\n"))
        self.assertEqual(builder.pomkots_safety_config(),
                         entries[builder.SERVER_POMKOTS_CONFIG_PATH])
        self.assertEqual(
            self.fixture.asset_profile,
            entries[builder.SERVER_LOST_CITIES_PROFILE_PATH],
        )
        self.assertEqual(
            {builder.SERVER_LOST_CITIES_PROFILE_PATH},
            {name for name in entries if name.startswith("config/lostcities/profiles/")},
        )
        self.assertTrue(any(name.startswith(builder.SERVER_SAVE_ROOT + "/") for name in entries))
        self.assertEqual(
            builder.archive_license_paths(client=False),
            {name for name in entries if name.startswith(builder.SERVER_LICENSE_ROOT + "/")},
        )
        client = builder.read_zip(files[builder.MRPACK_NAME], "client", canonical=True,
                                  compression=zipfile.ZIP_DEFLATED)
        for filename in builder.LICENSE_FILENAMES.values():
            self.assertEqual(
                client[f"{builder.CLIENT_LICENSE_ROOT}/{filename}"],
                entries[f"{builder.SERVER_LICENSE_ROOT}/{filename}"],
            )

    def test_21_license_bundle_is_source_bound_and_fails_closed(self) -> None:
        asset_entries = builder.read_zip(self.fixture.asset_blob, "asset", allow_directories=True)
        asset_notice = asset_entries["META-INF/THIRD_PARTY_NOTICES.md"]
        expected = builder.make_license_bundle(
            self.fixture.mp25_blob, self.fixture.lost_blob, self.fixture.asset_blob,
            asset_notice, self.fixture.dep_blobs,
        )
        independently_expected = verifier.reconstruct_license_bundle(
            self.fixture.mp25_blob, self.fixture.lost_blob, self.fixture.asset_blob,
            asset_notice, self.fixture.dep_blobs,
        )
        self.assertEqual(expected, independently_expected)
        self.assertEqual(set(builder.LICENSE_FILENAMES), set(expected))
        self.assertEqual(expected["architectury"], expected["cloth_config"])

        cloth_entries = builder.read_zip(self.fixture.cloth, "cloth", allow_directories=True)
        cloth_entries.pop("LICENSE.md")
        missing = dict(self.fixture.dep_blobs)
        missing["cloth_config"] = ordinary_zip(cloth_entries)
        for function, error in (
            (builder.make_license_bundle, builder.BuildError),
            (verifier.reconstruct_license_bundle, verifier.VerifyError),
        ):
            with self.subTest(function=function.__name__, drift="missing"), \
                    self.assertRaisesRegex(error, "missing required license"):
                function(self.fixture.mp25_blob, self.fixture.lost_blob,
                         self.fixture.asset_blob, asset_notice, missing)

        cloth_entries["LICENSE.md"] = b"not a license\n"
        mutated = dict(self.fixture.dep_blobs)
        mutated["cloth_config"] = ordinary_zip(cloth_entries)
        for function, error in (
            (builder.make_license_bundle, builder.BuildError),
            (verifier.reconstruct_license_bundle, verifier.VerifyError),
        ):
            with self.subTest(function=function.__name__, drift="mutated"), \
                    self.assertRaisesRegex(error, "expected license text"):
                function(self.fixture.mp25_blob, self.fixture.lost_blob,
                         self.fixture.asset_blob, asset_notice, mutated)

    def test_23_qualification_probe_cannot_enter_production_payloads(self) -> None:
        entries = builder.read_zip(self.fixture.mp25_blob, "mp25", allow_directories=True)
        entries["grcmcs/minecraft/mods/pomkotsmechs/qualification/AshenSpanAcceptanceProbe.class"] = b"probe"
        contaminated = ordinary_zip(entries)
        for function, error in (
            (builder.validate_mp25_jar, builder.BuildError),
            (verifier.inspect_mp25, verifier.VerifyError),
        ):
            with self.subTest(function=function.__name__), \
                    self.assertRaisesRegex(error, "qualification-only"):
                function(contaminated, builder.sha256_bytes(contaminated))

    def test_25_runtime_bound_rc6_rejects_self_consistent_input_substitution(self) -> None:
        original_mp25 = self.fixture.mp25.read_bytes()
        original_asset = self.fixture.asset.read_bytes()
        original_world = self.fixture.world_archive.read_bytes()

        mp25_entries = builder.read_zip(original_mp25, "mp25", allow_directories=True)
        mp25_entries["unrelated/Extra.class"] = b"substitution"
        substituted_mp25 = ordinary_zip(mp25_entries)
        self.fixture.mp25.write_bytes(substituted_mp25)

        asset_entries = builder.read_zip(original_asset, "asset", allow_directories=True)
        asset_entries["data/mecharena_sector01/unrelated.json"] = b"{}\n"
        substituted_asset = ordinary_zip(asset_entries)
        substituted_world = original_world + b"substitution"

        for module, inputs_type, loader, error in (
            (builder, builder.CandidateInputs, builder.load_inputs, builder.BuildError),
            (verifier, verifier.Inputs, verifier.load, verifier.VerifyError),
        ):
            base = self.fixture.builder_inputs() if module is builder else self.fixture.verifier_inputs()
            mp_inputs = inputs_type(
                base.base_mrpack, base.mp25_jar, builder.sha256_bytes(substituted_mp25),
                base.lost_cities_jar, base.asset_jar, base.asset_sha256, base.asset_receipt,
                base.world_dir, base.world_archive, base.world_sha256, base.world_receipt,
                base.architectury_jar, base.cloth_config_jar, base.geckolib_jar, base.source_commit,
            )
            with self.subTest(module=module.__name__, payload="mp25"), \
                    self.assertRaisesRegex(error, "exact RC6 production JAR"):
                loader(mp_inputs)

            self.fixture.mp25.write_bytes(original_mp25)
            self.fixture.asset.write_bytes(substituted_asset)
            asset_inputs = inputs_type(
                base.base_mrpack, base.mp25_jar, builder.sha256_bytes(original_mp25),
                base.lost_cities_jar, base.asset_jar, builder.sha256_bytes(substituted_asset),
                base.asset_receipt, base.world_dir, base.world_archive, base.world_sha256,
                base.world_receipt, base.architectury_jar, base.cloth_config_jar,
                base.geckolib_jar, base.source_commit,
            )
            with self.subTest(module=module.__name__, payload="asset"), \
                    self.assertRaisesRegex(error, "immutable RC5 Sector 01 JAR"):
                loader(asset_inputs)

            self.fixture.asset.write_bytes(original_asset)
            self.fixture.world_archive.write_bytes(substituted_world)
            world_inputs = inputs_type(
                base.base_mrpack, base.mp25_jar, builder.sha256_bytes(original_mp25),
                base.lost_cities_jar, base.asset_jar, builder.sha256_bytes(original_asset),
                base.asset_receipt, base.world_dir, base.world_archive,
                builder.sha256_bytes(substituted_world), base.world_receipt,
                base.architectury_jar, base.cloth_config_jar, base.geckolib_jar,
                base.source_commit,
            )
            with self.subTest(module=module.__name__, payload="world"), \
                    self.assertRaisesRegex(error, "immutable RC5 world-builder archive"):
                loader(world_inputs)

            self.fixture.world_archive.write_bytes(original_world)

    def test_22_asset_embedded_profile_is_exact_and_mandatory(self) -> None:
        entries = builder.read_zip(self.fixture.asset_blob, "asset", allow_directories=True)
        entries.pop(builder.ASSET_PROFILE_MEMBER)
        missing = ordinary_zip(entries)
        for module, function, error in (
            (builder, builder.validate_asset_jar, builder.BuildError),
            (verifier, verifier.inspect_asset, verifier.VerifyError),
        ):
            with self.subTest(module=module.__name__, drift="missing"), \
                    self.assertRaisesRegex(error, "missing"):
                function(missing, builder.sha256_bytes(missing))

        profile = json.loads(self.fixture.asset_profile)
        profile["public"] = True
        mutated = asset_jar(builder.canonical_json(profile))
        for module, function, error in (
            (builder, builder.validate_asset_jar, builder.BuildError),
            (verifier, verifier.inspect_asset, verifier.VerifyError),
        ):
            with self.subTest(module=module.__name__, drift="mutated"), \
                    self.assertRaisesRegex(error, "profile"):
                function(mutated, builder.sha256_bytes(mutated))

    def test_24_profile_boundary_and_manifest_provenance_are_exact(self) -> None:
        files = builder.build_candidate(self.fixture.builder_inputs())
        manifest = json.loads(files[builder.MANIFEST_NAME])
        receipt = json.loads(files[builder.RECEIPT_NAME])
        profile_record = builder.artifact_record(
            builder.ASSET_PROFILE_MEMBER, self.fixture.asset_profile
        )
        self.assertEqual(profile_record, manifest["inputs"]["asset_lost_cities_profile"])
        self.assertEqual(
            builder.CLIENT_LOST_CITIES_PROFILE_PATH,
            manifest["client_contract"]["lost_cities_profile"]["archive_path"],
        )
        self.assertEqual(
            builder.SERVER_LOST_CITIES_PROFILE_PATH,
            manifest["server_contract"]["lost_cities_profile"]["archive_path"],
        )
        self.assertIn(
            builder.CLIENT_LOST_CITIES_PROFILE_PATH,
            manifest["archive_contract"]["entries"][builder.MRPACK_NAME],
        )
        self.assertIn(
            builder.SERVER_LOST_CITIES_PROFILE_PATH,
            manifest["archive_contract"]["entries"][builder.SERVER_NAME],
        )
        self.assertEqual(
            builder.ASSET_PROFILE_SHA256,
            receipt["fixed_inputs"]["asset_lost_cities_profile_sha256"],
        )
        self.assertEqual(builder.RC5_CANDIDATE_ID, manifest["lineage"]["predecessor_candidate_id"])
        self.assertTrue(manifest["lineage"]["production_jar_changed"])
        self.assertFalse(manifest["lineage"]["authored_world_changed"])
        self.assertFalse(manifest["lineage"]["mission_content_roster_tactics_balance_changed"])
        self.assertTrue(manifest["lineage"]["solo_start_player_pad_collision_fixed"])
        self.assertEqual(builder.RC6_RUNTIME_SOURCE_COMMIT,
                         manifest["identity"]["runtime_source_commit"])
        self.assertEqual(builder.RC6_MP25_SHA256,
                         manifest["lineage"]["runtime_anchors"]["mp25_sha256"])
        self.assertEqual(builder.RC5_MP25_SHA256,
                         manifest["lineage"]["predecessor_runtime_anchors"]["mp25_sha256"])
        self.assertTrue(manifest["lineage"]["packaging_bytes_changed"])
        self.assertFalse(manifest["distribution_hardening"]["qualification_probe_shipped"])
        self.assertEqual(
            set(builder.LICENSE_FILENAMES),
            set(manifest["distribution_hardening"]["embedded_license_bundle"]),
        )
        self.assertEqual(
            {
                key: builder.sha256_bytes(blob)
                for key, blob in builder.make_license_bundle(
                    self.fixture.mp25_blob, self.fixture.lost_blob, self.fixture.asset_blob,
                    builder.read_zip(self.fixture.asset_blob, "asset", allow_directories=True)[
                        "META-INF/THIRD_PARTY_NOTICES.md"
                    ],
                    self.fixture.dep_blobs,
                ).items()
            },
            receipt["fixed_inputs"]["embedded_license_sha256"],
        )
        self.assertTrue(receipt["offline_gates"]["production_jar_changed_from_rc5"])
        self.assertTrue(receipt["offline_gates"]["solo_start_player_pad_collision_fixed"])
        self.assertFalse(receipt["offline_gates"]["authored_world_changed_from_rc5"])
        self.assertFalse(
            receipt["offline_gates"]["mission_content_roster_tactics_balance_changed_from_rc5"]
        )
        self.assertTrue(receipt["offline_gates"]["package_bytes_changed_from_rc5"])

        client_names = {
            f"{builder.CLIENT_SAVE_ROOT}/level.dat",
            builder.CLIENT_LOST_CITIES_PROFILE_PATH,
            "overrides/config/lostcities/profiles/rogue.json",
        }
        server_names = {
            f"{builder.SERVER_SAVE_ROOT}/level.dat",
            builder.SERVER_LOST_CITIES_PROFILE_PATH,
            "config/lostcities/profiles/rogue.json",
        }
        for module, function, error in (
            (builder, builder.validate_boundary, builder.BuildError),
            (verifier, verifier.boundary, verifier.VerifyError),
        ):
            for names, client in ((client_names, True), (server_names, False)):
                with self.subTest(module=module.__name__, client=client), \
                        self.assertRaisesRegex(error, "profile set"):
                    if module is builder:
                        function(names, client=client)
                    else:
                        function(names, client)

    def test_30_verifier_rejects_mutated_archive_even_with_receipt_present(self) -> None:
        self.build_and_publish()
        path = self.fixture.output / builder.MRPACK_NAME
        path.write_bytes(path.read_bytes() + b"tamper")
        with self.assertRaisesRegex(verifier.VerifyError, "differs from independent reconstruction"):
            verifier.verify_candidate(self.fixture.verifier_inputs(), self.fixture.output)

    def test_40_duplicate_cloth_config_record_fails_closed(self) -> None:
        entries = builder.read_zip(self.fixture.base_blob, "base", canonical=True,
                                   compression=zipfile.ZIP_STORED)
        index = json.loads(entries[builder.INDEX_PATH])
        duplicate = dict(index["files"][1]); duplicate["path"] = "mods/cloth-config-copy.jar"
        index["files"].append(duplicate)
        entries[builder.INDEX_PATH] = builder.canonical_json(index)
        blob = builder.make_canonical_zip(entries, compression=zipfile.ZIP_STORED)
        self.fixture.base.write_bytes(blob)
        with mock.patch.object(builder, "BASE_SHA256", builder.sha256_bytes(blob)), \
                mock.patch.object(builder, "BASE_BYTES", len(blob)):
            with self.assertRaisesRegex(builder.BuildError, "one exact Cloth Config"):
                builder.build_candidate(self.fixture.builder_inputs())

    def test_50_mosslorn_or_downloaded_city_world_path_is_rejected(self) -> None:
        forbidden = self.fixture.world / "data" / "Mosslorn-copy.dat"
        forbidden.write_bytes(b"forbidden")
        with self.assertRaisesRegex(builder.BuildError, "forbidden downloaded-city"):
            builder.build_candidate(self.fixture.builder_inputs())

    def test_55_generation_datapack_file_set_is_exact(self) -> None:
        extra = self.fixture.world / "datapacks" / builder.GENERATION_DATAPACK_NAME / "data" / "extra.json"
        extra.parent.mkdir(parents=True, exist_ok=True)
        extra.write_bytes(b"{}\n")
        for module, error in ((builder, builder.BuildError), (verifier, verifier.VerifyError)):
            with self.subTest(module=module.__name__, drift="extra"), \
                    self.assertRaisesRegex(error, "generation datapack file set drifted"):
                module.read_world_tree(self.fixture.world)
        extra.unlink()

        required = self.fixture.world / Path(sorted(builder.GENERATION_DATAPACK_PATHS)[0])
        required.unlink()
        for module, error in ((builder, builder.BuildError), (verifier, verifier.VerifyError)):
            with self.subTest(module=module.__name__, drift="missing"), \
                    self.assertRaisesRegex(error, "generation datapack file set drifted"):
                module.read_world_tree(self.fixture.world)

    def test_56_generation_datapack_content_is_pinned(self) -> None:
        required = self.fixture.world / Path(sorted(builder.GENERATION_DATAPACK_PATHS)[0])
        required.write_bytes(required.read_bytes() + b"tamper")
        for module, error in ((builder, builder.BuildError), (verifier, verifier.VerifyError)):
            with self.subTest(module=module.__name__), self.assertRaisesRegex(error, "generation datapack hash mismatch"):
                module.read_world_tree(self.fixture.world)

    def test_57_world_receipt_schema2_and_contract_are_strict(self) -> None:
        world_tree = builder.read_world_tree(self.fixture.world)
        receipt = json.loads(self.fixture.world_receipt.read_bytes())

        builder.validate_world_receipt(
            self.fixture.world_receipt.read_bytes(), self.fixture.world_blob,
            self.fixture.world_sha, self.fixture.asset_sha, world_tree,
            self.fixture.world_facts,
        )
        verifier.inspect_world_receipt(
            self.fixture.world_receipt.read_bytes(), self.fixture.world_blob,
            self.fixture.world_sha, self.fixture.asset_sha, world_tree,
            self.fixture.world_facts,
        )

        mutations = {
            "schema": lambda value: value.__setitem__("schema", 1),
            "builder": lambda value: value.__setitem__("builder", "ashen-span-world/1.0.0"),
            "contract": lambda value: value["contract"].__setitem__("dimension", "lostcities:lostcity"),
            "datapack": lambda value: value["generation_datapack"].__setitem__("sha256", "0" * 64),
            "runtime": lambda value: value["generation_runtime"].__setitem__(
                "java_executable_sha256", "0" * 64
            ),
        }
        for mutation, mutate in mutations.items():
            changed = json.loads(json.dumps(receipt))
            mutate(changed)
            blob = builder.canonical_json(changed)
            with self.subTest(module="builder", mutation=mutation), self.assertRaises(builder.BuildError):
                builder.validate_world_receipt(
                    blob, self.fixture.world_blob, self.fixture.world_sha,
                    self.fixture.asset_sha, world_tree, self.fixture.world_facts,
                )
            with self.subTest(module="verifier", mutation=mutation), self.assertRaises(verifier.VerifyError):
                verifier.inspect_world_receipt(
                    blob, self.fixture.world_blob, self.fixture.world_sha,
                    self.fixture.asset_sha, world_tree, self.fixture.world_facts,
                )

    def test_60_world_archive_must_match_curated_tree(self) -> None:
        self.fixture.world_archive.write_bytes(builder.make_canonical_zip({
            f"{builder.WORLD_ARCHIVE_ROOT}/level.dat": b"different"
        }))
        changed = self.fixture.world_archive.read_bytes()
        inputs = self.fixture.builder_inputs()
        inputs = builder.CandidateInputs(
            inputs.base_mrpack, inputs.mp25_jar, inputs.mp25_sha256, inputs.lost_cities_jar,
            inputs.asset_jar, inputs.asset_sha256, inputs.asset_receipt, inputs.world_dir,
            inputs.world_archive, builder.sha256_bytes(changed), inputs.world_receipt,
            inputs.architectury_jar, inputs.cloth_config_jar, inputs.geckolib_jar, inputs.source_commit,
        )
        with self.assertRaisesRegex(builder.BuildError, "immutable RC5 world-builder archive"):
            builder.build_candidate(inputs)

    def test_70_unsafe_archive_paths_and_case_collisions_are_rejected(self) -> None:
        for name in ("../escape", "/absolute", "C:/drive", "back\\slash"):
            with self.subTest(name=name), self.assertRaises(builder.BuildError):
                builder.validate_archive_path(name, "test")
        with self.assertRaisesRegex(builder.BuildError, "case-collide"):
            builder.make_canonical_zip({"A.txt": b"a", "a.TXT": b"b"})

    def test_72_archive_expansion_limit_is_enforced_before_materialization(self) -> None:
        oversized = ordinary_zip({"large.bin": b"x" * 2048})
        with self.assertRaisesRegex(builder.BuildError, "archive expands past"):
            builder.read_zip(oversized, "oversized", max_uncompressed_bytes=1024)
        with self.assertRaisesRegex(verifier.VerifyError, "archive expands past"):
            verifier.read_zip(oversized, "oversized", max_uncompressed_bytes=1024)

    def test_74_fake_one_region_world_is_rejected_by_physical_verifier(self) -> None:
        with self.assertRaises((builder.world_builder.BuildError, ValueError, OSError)):
            builder.world_builder.verify_world(
                self.fixture.world,
                builder.WORLD_PROFILE_SHA256,
                self.fixture.asset_sha.lower(),
                builder.WORLD_SOURCE_CONTRACT_SHA256,
            )

    def test_76_source_commit_must_resolve_to_a_real_commit(self) -> None:
        with self.assertRaisesRegex(builder.BuildError, "does not resolve"):
            REAL_BUILDER_VALIDATE_SOURCE_COMMIT("0123456789abcdef0123456789abcdef01234567")

    def test_77_physical_checkout_eol_gate_rejects_clean_filter_mismatch(self) -> None:
        listing = (
            "i/lf    w/lf    attr/text eol=lf\tgood.json\0"
            "i/lf    w/crlf  attr/text eol=lf\tbad.json\0"
            "i/crlf  w/crlf  attr/text eol=crlf\tgood.bat\0"
            "i/-text w/-text attr/-text\tasset.ogg\0"
        )
        expected = ["bad.json (crlf, expected lf)"]
        self.assertEqual(expected, builder.checkout_eol_mismatches(listing))
        self.assertEqual(expected, verifier.checkout_eol_mismatches(listing))

    def test_78_manifest_hashes_committed_builder_blob_not_checkout_bytes(self) -> None:
        committed = b"#!/usr/bin/env python3\n# canonical Git LF bytes\n"
        builder_reader = mock.Mock(return_value=committed)
        verifier_reader = mock.Mock(return_value=committed)
        source_commit = self.fixture.builder_inputs().source_commit
        with mock.patch.object(builder, "read_committed_builder_blob", builder_reader), \
                mock.patch.object(verifier, "read_committed_builder_blob", verifier_reader):
            files = builder.build_candidate(self.fixture.builder_inputs())
            builder.publish_atomic(self.fixture.output, files)
            result = verifier.verify_candidate(self.fixture.verifier_inputs(), self.fixture.output)

        manifest = json.loads(files[builder.MANIFEST_NAME])
        self.assertEqual(builder.sha256_bytes(committed), manifest["builder"]["sha256"])
        self.assertEqual(len(committed), manifest["builder"]["bytes"])
        builder_reader.assert_called_once_with(source_commit)
        verifier_reader.assert_called_once_with(source_commit)
        self.assertEqual(11, result["files"])

    def test_79_release_text_formats_have_checkout_independent_eol(self) -> None:
        attributes = (TOOLS.parent / ".gitattributes").read_text(encoding="utf-8")
        self.assertIn(".gitattributes text eol=lf", attributes)
        for pattern in ("*.java", "*.json", "*.toml", "*.mcmeta", "*.py", "*.md"):
            self.assertIn(f"{pattern} text eol=lf", attributes)
        self.assertIn("LICENSE text eol=lf", attributes)
        for pattern in ("*.jar", "*.zip", "*.mrpack", "*.nbt", "*.mca", "*.dat", "*.ogg"):
            self.assertIn(f"{pattern} binary", attributes)

    def test_80_exclusive_publish_refuses_existing_output(self) -> None:
        files = self.build_and_publish()
        before = {path.name: path.read_bytes() for path in self.fixture.output.iterdir()}
        with self.assertRaisesRegex(builder.BuildError, "refusing to overwrite"):
            builder.publish_atomic(self.fixture.output, files)
        self.assertEqual(before, {path.name: path.read_bytes() for path in self.fixture.output.iterdir()})

    def test_82_first_publish_creates_and_validates_missing_parent_chain(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            output = root / "modpack" / "candidates" / "operation-ashen-span-rc6"
            files = {"artifact.bin": b"candidate"}
            self.assertFalse(output.parent.exists())

            builder.publish_atomic(output, files)

            self.assertEqual(b"candidate", (output / "artifact.bin").read_bytes())
            self.assertTrue((root / "modpack" / "candidates").is_dir())
            with self.assertRaisesRegex(builder.BuildError, "refusing to overwrite"):
                builder.publish_atomic(output, files)

    def test_84_publish_rejects_unsafe_parent_chain_components(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            blocker = root / "modpack"
            blocker.write_bytes(b"not a directory")
            with self.assertRaisesRegex(builder.BuildError, "non-directory"):
                builder.publish_atomic(blocker / "candidates" / "rc5", {"artifact.bin": b"candidate"})

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            unsafe = root / "modpack"
            unsafe.mkdir()
            original = builder.is_link_or_reparse
            with mock.patch.object(
                builder,
                "is_link_or_reparse",
                side_effect=lambda path: Path(path) == unsafe or original(Path(path)),
            ), self.assertRaisesRegex(builder.BuildError, "unsafe component"):
                builder.publish_atomic(unsafe / "candidates" / "rc6", {"artifact.bin": b"candidate"})

    def test_90_wrong_explicit_identity_fails_before_packaging(self) -> None:
        inputs = self.fixture.builder_inputs()
        wrong = builder.CandidateInputs(
            inputs.base_mrpack, inputs.mp25_jar, "0" * 64, inputs.lost_cities_jar,
            inputs.asset_jar, inputs.asset_sha256, inputs.asset_receipt, inputs.world_dir,
            inputs.world_archive, inputs.world_sha256, inputs.world_receipt,
            inputs.architectury_jar, inputs.cloth_config_jar, inputs.geckolib_jar, inputs.source_commit,
        )
        with self.assertRaisesRegex(builder.BuildError, "exact RC6 production JAR"):
            builder.build_candidate(wrong)

    def test_95_verifier_source_is_independent(self) -> None:
        source = (TOOLS / "verify_ashen_span_mp25.py").read_text(encoding="utf-8")
        self.assertNotIn("import build_ashen_span_mp25", source)
        self.assertNotIn("from build_ashen_span_mp25", source)
        self.assertIn("independent", source.casefold())


if __name__ == "__main__":
    unittest.main(verbosity=2)
