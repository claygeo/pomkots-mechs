from __future__ import annotations

import hashlib
import io
import json
from pathlib import Path
import stat
import tempfile
import unittest
from unittest import mock
import zipfile

from tools import run_ashen_span_acceptance as acceptance


def valid_probe_result(build_limit: int = 1) -> dict[str, object]:
    builds = []
    for index in range(1, build_limit + 1):
        builds.append({
            "build": index,
            "name": acceptance.BUILD_NAMES[index - 1],
            "command_path": "arena solo start",
            "brigadier_start": True,
            "brigadier_retry": True,
            "initial_template": True,
            "retry_template": True,
            "phase_order": list(acceptance.EXPECTED_PHASE_ORDER),
            "normal_roots": 15,
            "gatekeeper_entity": "pomkotsmechs:arena_rival_pmvc01",
            "span_warden_entity": "pomkotsmechs:pmb04",
            "pmb04_hitbox_lineage": True,
            "service_template_restored": True,
            "service_resources_degraded": True,
            "root_ownership_metadata": True,
            "staged_socket_contract": True,
            "gatekeeper_uuid_continuity": True,
            "ownership_registry_empty": True,
            "ownership_secondary_indexes_empty": True,
            "retry_gate_restore": True,
            "retry_mutation_restore": True,
            "retry_exact_zero": True,
            "final_gate_restore": True,
            "mission_gate_ledger_empty": True,
            "forced_chunks_restored": True,
            "victory_outcome": True,
            "exact_zero": True,
            "player_dismounted": True,
            "player_position_restore": True,
            "player_restore": True,
            "durable_restore_cleared": True,
            "ticks": 2,
        })
    return {
        "schema_version": 2,
        "probe_version": "1.1.0",
        "candidate_id": acceptance.CANDIDATE_ID,
        "source_commit": acceptance.SOURCE_COMMIT,
        "runtime_source_commit": acceptance.SOURCE_COMMIT,
        "mission_id": acceptance.MISSION_ID,
        "map_id": acceptance.MAP_ID,
        "seed": acceptance.MISSION_SEED,
        "status": "PASS",
        "total_ticks": 500,
        "builds": builds,
        "build_limit": build_limit,
        "all_six_builds": build_limit == 6,
        "safety_chunks": 680,
        "world_claim": acceptance.WORLD_CLAIM,
    }


class AcceptanceRunnerTests(unittest.TestCase):
    def test_safe_extract_one_file(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            archive = root / "overlay.zip"
            with zipfile.ZipFile(archive, "w") as out:
                out.writestr("mods/example.jar", b"jar")
            destination = root / "output"
            destination.mkdir()
            self.assertEqual(acceptance.safe_extract(archive, destination), ["mods/example.jar"])
            self.assertEqual((destination / "mods" / "example.jar").read_bytes(), b"jar")

    def test_safe_extract_rejects_traversal(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            archive = root / "bad.zip"
            with zipfile.ZipFile(archive, "w") as out:
                out.writestr("../escape.txt", b"no")
            destination = root / "output"
            destination.mkdir()
            with self.assertRaisesRegex(acceptance.AcceptanceError, "unsafe"):
                acceptance.safe_extract(archive, destination)

    def test_safe_extract_rejects_case_collision(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            archive = root / "bad.zip"
            with zipfile.ZipFile(archive, "w") as out:
                out.writestr("mods/A.jar", b"a")
                out.writestr("mods/a.jar", b"b")
            destination = root / "output"
            destination.mkdir()
            with self.assertRaisesRegex(acceptance.AcceptanceError, "case-colliding"):
                acceptance.safe_extract(archive, destination)

    def test_safe_extract_rejects_symlink(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            archive = root / "bad.zip"
            info = zipfile.ZipInfo("mods/link.jar")
            info.create_system = 3
            info.external_attr = (stat.S_IFLNK | 0o777) << 16
            with zipfile.ZipFile(archive, "w") as out:
                out.writestr(info, "target")
            destination = root / "output"
            destination.mkdir()
            with self.assertRaisesRegex(acceptance.AcceptanceError, "symlink"):
                acceptance.safe_extract(archive, destination)

    def test_safe_extract_rejects_alternate_stream_in_nested_component(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            archive = root / "bad.zip"
            with zipfile.ZipFile(archive, "w") as out:
                out.writestr("mods/normal.jar:stream", b"no")
            destination = root / "output"
            destination.mkdir()
            with self.assertRaisesRegex(acceptance.AcceptanceError, "alternate-stream"):
                acceptance.safe_extract(archive, destination)

    def test_probe_detection_descends_into_nested_jar(self) -> None:
        nested_stream = io.BytesIO()
        with zipfile.ZipFile(nested_stream, "w") as nested:
            nested.writestr("META-INF/mods.toml", 'modId="ashen_span_qualification"')
        outer_stream = io.BytesIO()
        with zipfile.ZipFile(outer_stream, "w") as outer:
            outer.writestr("mods/probe.jar", nested_stream.getvalue())
        self.assertTrue(acceptance.archive_has_probe(outer_stream.getvalue()))

    def test_probe_detection_accepts_unrelated_archive(self) -> None:
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, "w") as archive:
            archive.writestr("mods/normal.jar", b"normal")
        self.assertFalse(acceptance.archive_has_probe(stream.getvalue()))

    def test_receipt_output_hashes_are_physical(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            outputs = {}
            for index in range(9):
                name = f"file-{index}.bin"
                data = f"payload-{index}".encode()
                (root / name).write_bytes(data)
                outputs[name] = {
                    "file": name,
                    "bytes": len(data),
                    "sha256": hashlib.sha256(data).hexdigest().upper(),
                }
            receipt = {
                "schema_version": 1,
                "candidate_id": acceptance.CANDIDATE_ID,
                "source_commit": "d96b7b84688e925f311849d7c40f72a4f8a691c2",
                "outputs": outputs,
            }
            original = acceptance.RECEIPT_OUTPUT_FILES
            acceptance.RECEIPT_OUTPUT_FILES = set(outputs)
            try:
                measured = acceptance.verify_receipt_outputs(root, receipt)
                self.assertEqual(len(measured), 9)
                (root / "file-3.bin").write_bytes(b"drift")
                with self.assertRaisesRegex(acceptance.AcceptanceError, "drift"):
                    acceptance.verify_receipt_outputs(root, receipt)
            finally:
                acceptance.RECEIPT_OUTPUT_FILES = original

    def test_self_authored_substitute_candidate_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            candidate = Path(raw)
            for name in acceptance.EXPECTED_CANDIDATE_FILES:
                (candidate / name).write_bytes(b"substitute")
            self_authored = {
                "schema_version": 1,
                "candidate_id": acceptance.CANDIDATE_ID,
                "source_commit": acceptance.SOURCE_COMMIT,
                "outputs": {},
            }
            (candidate / "OFFLINE_BUILD_RECEIPT.json").write_text(
                json.dumps(self_authored), encoding="utf-8")
            with self.assertRaisesRegex(acceptance.AcceptanceError,
                                        "sealed candidate (size|hash) mismatch"):
                acceptance.verify_candidate(candidate)

    def test_acceptance_output_and_candidate_paths_must_be_disjoint(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            candidate = root / "candidate"
            candidate.mkdir()
            for output in (candidate, candidate / "session", root):
                with self.subTest(output=output), self.assertRaisesRegex(
                        acceptance.AcceptanceError, "paths must be disjoint"):
                    acceptance.require_disjoint_paths(
                        candidate, output, "candidate and acceptance output")
            acceptance.require_disjoint_paths(
                candidate, root / "sibling", "candidate and acceptance output")

    def test_final_candidate_rebind_rejects_nonprobe_drift_before_receipt(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            candidate = root / "candidate"
            candidate.mkdir()
            payload = candidate / "RUNBOOK.md"
            payload.write_bytes(b"initial")
            receipt_path = root / "ACTUAL_WORLD_ACCEPTANCE_RECEIPT.json"
            baseline = (
                {"candidate_id": acceptance.CANDIDATE_ID},
                {"RUNBOOK.md": {"sha256": hashlib.sha256(b"initial").hexdigest()}},
                candidate / "overlay.zip",
                {"candidate_tree_sha256": acceptance.RC5_CANDIDATE_TREE_SHA256},
            )

            def bind_from_payload(_candidate: Path, _tree: str | None):
                digest = hashlib.sha256(payload.read_bytes()).hexdigest()
                return (
                    baseline[0],
                    {"RUNBOOK.md": {"sha256": digest}},
                    baseline[2],
                    baseline[3],
                )

            with mock.patch.object(
                    acceptance, "bind_candidate_for_run", side_effect=bind_from_payload):
                payload.write_bytes(b"drift-without-probe-token")
                with self.assertRaisesRegex(
                        acceptance.AcceptanceError, "candidate changed during"):
                    acceptance.require_candidate_unchanged(candidate, None, baseline)
            self.assertFalse(receipt_path.exists())

    def test_server_properties_are_locked_for_no_gui_local_run(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            path = Path(raw) / "server.properties"
            path.write_text("online-mode=true\nwhite-list=true\nlevel-name=wrong\n", encoding="utf-8")
            acceptance.write_server_properties(path)
            values = dict(line.split("=", 1) for line in path.read_text(encoding="utf-8").splitlines())
            self.assertEqual(values["online-mode"], "false")
            self.assertEqual(values["white-list"], "false")
            self.assertEqual(values["server-ip"], "127.0.0.1")
            self.assertEqual(values["server-port"], "0")
            self.assertEqual(values["level-name"], "saves/cold_ruin_sector_01")

    def test_fml_config_disables_outbound_version_check(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            path = Path(raw) / "config" / "fml.toml"
            acceptance.write_fml_config(path)
            text = path.read_text(encoding="utf-8")
            self.assertIn("versionCheck = false", text)
            self.assertNotIn("versionCheck = true", text)

    def test_java_command_is_explicitly_property_gated_and_nogui(self) -> None:
        command = acceptance.java_command(Path("java"), Path("probe.json"), acceptance.CANDIDATE_ID, 1)
        self.assertIn("-DashenSpan.qualification=true", command)
        self.assertIn("nogui", command)
        self.assertTrue(any(part.startswith("-DashenSpan.qualification.output=") for part in command))
        self.assertIn("-DashenSpan.qualification.buildLimit=1", command)
        self.assertTrue(any(part.startswith("@libraries/net/minecraftforge/forge/1.20.1-47.3.3/")
                            for part in command))

    def test_expected_inventory_is_exact_680_chunk_rectangle(self) -> None:
        self.assertEqual(len(acceptance.EXPECTED_CHUNKS), 680)
        self.assertEqual(min(x for x, _ in acceptance.EXPECTED_CHUNKS), -17)
        self.assertEqual(max(x for x, _ in acceptance.EXPECTED_CHUNKS), 16)
        self.assertEqual(min(z for _, z in acceptance.EXPECTED_CHUNKS), -10)
        self.assertEqual(max(z for _, z in acceptance.EXPECTED_CHUNKS), 9)

    def test_world_inventory_returns_canonical_exact_rectangle(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            world = Path(raw)
            region = world / "region"
            region.mkdir()
            for name in ("r.-1.-1.mca", "r.-1.0.mca", "r.0.-1.mca", "r.0.0.mca"):
                (region / name).write_bytes(name.encode())
            entries = {coord: object() for coord in acceptance.EXPECTED_CHUNKS}
            with mock.patch.object(acceptance.anvil, "inventory", return_value=entries), \
                    mock.patch.object(acceptance.anvil, "status", return_value="full"):
                result = acceptance.world_inventory(world)
            self.assertEqual(result["chunk_count"], 680)
            self.assertEqual(result["full_chunk_count"], 680)
            self.assertEqual(set(result["region_files"]), {
                "r.-1.-1.mca", "r.-1.0.mca", "r.0.-1.mca", "r.0.0.mca",
            })

    def test_post_world_inventory_binds_proto_and_forbids_extra_full(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            world = Path(raw)
            region = world / "region"
            region.mkdir()
            (region / "r.0.0.mca").write_bytes(b"region")
            full = object()
            proto = object()
            entries = {coord: full for coord in acceptance.EXPECTED_CHUNKS}
            entries[(17, 0)] = proto
            digest = hashlib.sha256(b"17,0,structure_starts\n").hexdigest()
            with mock.patch.object(acceptance.anvil, "inventory", return_value=entries), \
                    mock.patch.object(acceptance.anvil, "status",
                                      side_effect=lambda entry: "structure_starts" if entry is proto else "full"):
                result = acceptance.post_world_inventory(world, digest)
            self.assertEqual(result["contract_full_chunk_count"], 680)
            self.assertEqual(result["proto_chunk_count"], 1)
            self.assertEqual(result["extra_full_chunk_count"], 0)
            with mock.patch.object(acceptance.anvil, "inventory", return_value=entries), \
                    mock.patch.object(acceptance.anvil, "status", return_value="full"):
                with self.assertRaisesRegex(acceptance.AcceptanceError, "extra FULL"):
                    acceptance.post_world_inventory(world, digest)

    def test_complete_one_build_probe_result_is_accepted(self) -> None:
        acceptance.validate_probe_result(valid_probe_result(), 1, acceptance.CANDIDATE_ID)

    def test_incomplete_probe_result_is_rejected(self) -> None:
        result = valid_probe_result()
        del result["builds"][0]["exact_zero"]  # type: ignore[index]
        with self.assertRaisesRegex(acceptance.AcceptanceError, "fields mismatch"):
            acceptance.validate_probe_result(result, 1, acceptance.CANDIDATE_ID)

    def test_extra_probe_result_field_is_rejected(self) -> None:
        result = valid_probe_result()
        result["unreviewed"] = True
        with self.assertRaisesRegex(acceptance.AcceptanceError, "extra=.*unreviewed"):
            acceptance.validate_probe_result(result, 1, acceptance.CANDIDATE_ID)

    def test_contradictory_probe_pass_is_rejected(self) -> None:
        result = valid_probe_result()
        result["builds"][0]["service_template_restored"] = False  # type: ignore[index]
        with self.assertRaisesRegex(acceptance.AcceptanceError,
                                    "service_template_restored was not exactly true"):
            acceptance.validate_probe_result(result, 1, acceptance.CANDIDATE_ID)

    def test_probe_pass_requires_staging_and_direct_registry_cleanup(self) -> None:
        for field in (
                "staged_socket_contract", "gatekeeper_uuid_continuity",
                "ownership_registry_empty", "ownership_secondary_indexes_empty"):
            result = valid_probe_result()
            result["builds"][0][field] = False  # type: ignore[index]
            with self.subTest(field=field), self.assertRaisesRegex(
                    acceptance.AcceptanceError, rf"{field} was not exactly true"):
                acceptance.validate_probe_result(result, 1, acceptance.CANDIDATE_ID)

    def test_per_build_elapsed_ticks_must_fit_total_interval(self) -> None:
        result = valid_probe_result(build_limit=2)
        result["total_ticks"] = 3
        result["builds"][0]["ticks"] = 2  # type: ignore[index]
        result["builds"][1]["ticks"] = 2  # type: ignore[index]
        with self.assertRaisesRegex(acceptance.AcceptanceError,
                                    "elapsed ticks exceed total_ticks"):
            acceptance.validate_probe_result(result, 2, acceptance.CANDIDATE_ID)

    def test_probe_binding_is_rejected_when_candidate_differs(self) -> None:
        with self.assertRaisesRegex(acceptance.AcceptanceError, "candidate_id mismatch"):
            acceptance.validate_probe_result(valid_probe_result(), 1, "substitute-candidate")

    def test_tree_bound_rc6_requires_reviewed_runtime_commit(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            candidate = Path(raw)
            (candidate / "MANIFEST.json").write_text(json.dumps({
                "identity": {
                    "source_commit": "a" * 40,
                    "runtime_source_commit": "b" * 40,
                },
            }), encoding="utf-8")
            (candidate / "OFFLINE_BUILD_RECEIPT.json").write_text(json.dumps({
                "source_commit": "a" * 40,
                "runtime_source_commit": "b" * 40,
            }), encoding="utf-8")
            binding = {"candidate_id": acceptance.RC6_CANDIDATE_ID}
            with mock.patch.object(acceptance, "bind_candidate_tree", return_value=binding) as bind:
                with self.assertRaisesRegex(acceptance.AcceptanceError,
                                            "reviewed production-fix commit"):
                    acceptance.verify_tree_bound_rc6(candidate, "C" * 64)
            bind.assert_called_once_with(candidate, "C" * 64)

    def test_console_requires_every_noncontradictory_marker(self) -> None:
        console = "\n".join([
            "ASHEN_SPAN_QUALIFICATION_ARMED",
            "Global Forge version check system disabled, no further processing.",
            "ASHEN_SPAN_QUALIFICATION_MAP_PASS chunks=680 markers=25",
            "ASHEN_SPAN_QUALIFICATION_BUILD_PASS build=1 name=Vanguard",
            "[Arena] SOLO VICTORY — SPAN WARDEN DESTROYED.",
            "ASHEN_SPAN_QUALIFICATION_PASS output=result.json",
        ])
        acceptance.validate_console_log(console, 1)
        with self.assertRaisesRegex(acceptance.AcceptanceError, "MAP_PASS"):
            acceptance.validate_console_log(console.replace("MAP_PASS", "MAP_MISSING"), 1)
        with self.assertRaisesRegex(acceptance.AcceptanceError, "contradicts PASS"):
            acceptance.validate_console_log(console + "\nASHEN_SPAN_QUALIFICATION_FAIL", 1)
        with self.assertRaisesRegex(acceptance.AcceptanceError, "SOLO DEFEAT"):
            acceptance.validate_console_log(console + "\n[Arena] SOLO DEFEAT", 1)
        with self.assertRaisesRegex(acceptance.AcceptanceError, "SOLO RUN ABORTED"):
            acceptance.validate_console_log(console + "\n[Arena] SOLO RUN ABORTED", 1)

    def test_probe_uses_exact_packaged_player_pad_without_forcing_chunks(self) -> None:
        source = (acceptance.REPO_ROOT / "forge" / "src" / "qualification" / "java"
                  / "grcmcs" / "minecraft" / "mods" / "pomkotsmechs" / "arena"
                  / "AshenSpanAcceptanceProbe.java").read_text(encoding="utf-8")
        self.assertIn("pilot.setPos(AshenSpanDefinition.PLAYER_PAD.x(),", source)
        self.assertNotIn("PLAYER_PAD.x() + 12", source)
        self.assertNotIn("setChunkForced", source)

    def test_probe_evidence_uses_full_build_interval_and_internal_indexes(self) -> None:
        source = (acceptance.REPO_ROOT / "forge" / "src" / "qualification" / "java"
                  / "grcmcs" / "minecraft" / "mods" / "pomkotsmechs" / "arena"
                  / "AshenSpanAcceptanceProbe.java").read_text(encoding="utf-8")
        self.assertIn('evidence.put("ticks", elapsedTicks);', source)
        self.assertNotIn('evidence.put("ticks", stepTicks);', source)
        self.assertIn("verifyExactStagedPhase", source)
        self.assertIn("stagedGatekeeperRootId", source)
        self.assertIn("ashenSpan$getGoneRootBodies().isEmpty()", source)
        self.assertIn("ashenSpan$getLastKnownPositions().isEmpty()", source)
        metadata = (acceptance.REPO_ROOT / "forge" / "src" / "qualification"
                    / "resources" / "META-INF" / "mods.toml").read_text(encoding="utf-8")
        self.assertIn('version="1.1.0"', metadata)


if __name__ == "__main__":
    unittest.main()
