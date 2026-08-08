from __future__ import annotations

import contextlib
import datetime as dt
import hashlib
import io
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest import mock
import zipfile

from tools import ashen_span_qualification as qualification


CREATED = "2026-08-08T12:00:00Z"
COMMIT = "d96b7b84688e925f311849d7c40f72a4f8a691c2"
RC6_COMMIT = qualification.RC6_RUNTIME_SOURCE_COMMIT


class AshenSpanQualificationTest(unittest.TestCase):
    def setUp(self) -> None:
        erratum = mock.patch.object(
            qualification, "SAFETY_ENVELOPE_ERRATUM_ID", "TEST-ONLY-ERRATUM"
        )
        erratum.start()
        self.addCleanup(erratum.stop)
        eligible = mock.patch.object(
            qualification, "QUALIFIABLE_POST_ERRATUM_CANDIDATE_ID",
            "operation-ashen-span-mp25-rc5",
        )
        eligible.start()
        self.addCleanup(eligible.stop)
        historical = mock.patch.object(
            qualification, "CURRENT_PRE_ERRATUM_CANDIDATE_IDS", frozenset()
        )
        historical.start()
        self.addCleanup(historical.stop)
        self.temp = Path(tempfile.mkdtemp(prefix="ashen-span-qualification-test-"))
        self.candidate = self.temp / "candidate"
        self._make_candidate(self.candidate)
        self.candidate_anchor = self._candidate_anchor(self.candidate)

    def tearDown(self) -> None:
        shutil.rmtree(self.temp)

    @staticmethod
    def _sha(data: bytes) -> str:
        return hashlib.sha256(data).hexdigest().upper()

    @staticmethod
    def _mrpack_bytes(version_id: str, display_name: str) -> bytes:
        output = io.BytesIO()
        with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("modrinth.index.json", json.dumps({
                "formatVersion": 1,
                "game": "minecraft",
                "name": display_name,
                "versionId": version_id,
            }, sort_keys=True))
        return output.getvalue()

    def _make_candidate(self, root: Path) -> None:
        root.mkdir(parents=True)
        payloads = {
            "mech-arena-0.8.0-operation-ashen-span-mp25-rc5.mrpack": self._mrpack_bytes(
                "0.8.0-operation-ashen-span-mp25-rc5",
                "Mech Arena 0.8 - Operation Ashen Span MP25 RC5",
            ),
            "mech-arena-operation-ashen-span-mp25-rc5-server-overlay.zip": b"server-overlay",
            "cold_ruin_sector_01-mp25-world.zip": b"bounded-world",
            "pomkotsmechs-forge-0.0.1-alpha.7-mp.25.jar": b"pomkots-mp25",
            "ASHEN_SPAN_LIVE_VALIDATION.md": b"pending\n",
            "RUNBOOK.md": b"runbook\n",
            "ROLLBACK.md": b"rollback\n",
            "THIRD_PARTY_NOTICES.md": b"notices\n",
        }
        for name, data in payloads.items():
            (root / name).write_bytes(data)
        artifacts = {}
        for name in payloads:
            data = payloads[name]
            artifacts[name] = {"bytes": len(data), "file": name, "sha256": self._sha(data)}
        qualification.write_canonical(root / "MANIFEST.json", {
            "artifacts": artifacts,
            "candidate_id": "operation-ashen-span-mp25-rc5",
            "identity": {
                "display_name": "Mech Arena 0.8 - Operation Ashen Span MP25 RC5",
                "forge": "47.3.3",
                "map_id": "cold_ruin_sector_01",
                "minecraft": "1.20.1",
                "mission_id": "operation_ashen_span",
                "source_commit": COMMIT,
                "version_id": "0.8.0-operation-ashen-span-mp25-rc5",
            },
            "schema_version": 1,
            "status": "offline-candidate-not-live-qualified",
        })
        qualification.write_canonical(root / "OFFLINE_BUILD_RECEIPT.json", {
            "candidate_id": "operation-ashen-span-mp25-rc5",
            "schema_version": 1,
            "source_commit": COMMIT,
            "status": "offline-only-not-live-qualified",
        })
        self._refresh_sums(root)

    def _make_rc6_candidate(self, root: Path) -> tuple[str, str]:
        shutil.copytree(self.candidate, root)
        old_mrpack = "mech-arena-0.8.0-operation-ashen-span-mp25-rc5.mrpack"
        new_mrpack = "mech-arena-0.8.0-operation-ashen-span-mp25-rc6.mrpack"
        old_server = "mech-arena-operation-ashen-span-mp25-rc5-server-overlay.zip"
        new_server = "mech-arena-operation-ashen-span-mp25-rc6-server-overlay.zip"
        (root / old_mrpack).unlink()
        (root / new_mrpack).write_bytes(self._mrpack_bytes(
            "0.8.0-operation-ashen-span-mp25-rc6",
            "Mech Arena 0.8 - Operation Ashen Span MP25 RC6",
        ))
        (root / old_server).rename(root / new_server)

        manifest = self._read(root / "MANIFEST.json")
        manifest["candidate_id"] = "operation-ashen-span-mp25-rc6"
        manifest["identity"].update({
            "display_name": "Mech Arena 0.8 - Operation Ashen Span MP25 RC6",
            "runtime_source_commit": RC6_COMMIT,
            "version_id": "0.8.0-operation-ashen-span-mp25-rc6",
        })
        artifacts = manifest["artifacts"]
        artifacts.pop(old_mrpack)
        artifacts.pop(old_server)
        for name in (new_mrpack, new_server):
            data = (root / name).read_bytes()
            artifacts[name] = {"bytes": len(data), "file": name, "sha256": self._sha(data)}
        self._write(root / "MANIFEST.json", manifest)

        receipt = self._read(root / "OFFLINE_BUILD_RECEIPT.json")
        receipt.update({
            "candidate_id": "operation-ashen-span-mp25-rc6",
            "runtime_source_commit": RC6_COMMIT,
        })
        self._write(root / "OFFLINE_BUILD_RECEIPT.json", receipt)
        self._refresh_sums(root)
        return new_mrpack, new_server

    def _refresh_sums(self, root: Path) -> None:
        lines = []
        for path in sorted(root.iterdir(), key=lambda item: item.name):
            if path.name == "SHA256SUMS.txt":
                continue
            lines.append(f"{qualification.sha256_file(path)} *{path.name}\n")
        (root / "SHA256SUMS.txt").write_text("".join(lines), encoding="utf-8", newline="\n")

    @staticmethod
    def _candidate_anchor(root: Path) -> str:
        inventory = [qualification.file_fact(path, path.name) for path in root.iterdir()]
        return qualification.candidate_tree_sha256(inventory)

    def _init(self, name: str = "kit") -> Path:
        kit = self.temp / name
        qualification.initialize_kit(
            self.candidate, kit, "session-001", CREATED, self.candidate_anchor,
        )
        return kit

    def _read(self, path: Path) -> dict:
        return json.loads(path.read_text(encoding="utf-8"))

    def _write(self, path: Path, value: dict) -> None:
        qualification.write_canonical(path, value)

    def _attach(self, kit: Path, record: dict, name: str) -> None:
        relative = f"attachments/{name}"
        (kit / relative).write_bytes(("evidence:" + name).encode("utf-8"))
        record["attachment_paths"] = [relative]

    def _main(self, argv: list[str]) -> int:
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            return qualification.main(argv)

    def _inspect_unfinalized(self, kit: Path) -> dict:
        return qualification.inspect_kit(
            self.candidate, kit, self.candidate_anchor, allow_unfinalized=True,
        )

    def _finalize(self, kit: Path) -> dict:
        return qualification.finalize_kit(self.candidate, kit, self.candidate_anchor)

    def _complete_pass(self, kit: Path) -> None:
        session = self._read(kit / "SESSION.json")
        session["environment"] = {
            "client_launcher": "Fresh launcher profile",
            "control_scheme": "Keyboard and mouse",
            "display_resolution": "2560x1440",
            "java_version": "17.0.19",
            "operating_system": "Windows 11",
        }
        self._write(kit / "SESSION.json", session)
        for number in range(1, 7):
            path = kit / f"build-{number:02d}.json"
            record = self._read(path)
            record["verdict"] = "pass"
            record["human"].update({
                "audio_cues_pass": True,
                "balance_pass": True,
                "controls_pass": True,
                "visual_readability_pass": True,
            })
            duration = 660 + number
            started = dt.datetime(2026, 8, 8, 12, 0, tzinfo=dt.timezone.utc) + dt.timedelta(minutes=(number - 1) * 15)
            ended = started + dt.timedelta(seconds=duration)
            phase_seconds = (0, 60, 120, 180, 240, 300, 420, 500, 600, duration)
            record["machine"].update({
                "cleanup_entity_count": 0,
                "cleanup_gate_ledger_entries": 0,
                "ended_utc": ended.strftime("%Y-%m-%dT%H:%M:%SZ"),
                "gatekeeper_r01_duration_seconds": 75,
                "gate_restore_pass": True,
                "gatekeeper_r01_defeated": True,
                "mission_completed": True,
                "mission_duration_seconds": duration,
                "mission_outcome": "victory",
                "phase_elapsed_seconds": dict(zip(qualification.EXPECTED_PHASE_ORDER, phase_seconds)),
                "phase_order": list(qualification.EXPECTED_PHASE_ORDER),
                "service_checkpoint_used": True,
                "span_warden_duration_seconds": 90,
                "span_warden_defeated": True,
                "started_utc": started.strftime("%Y-%m-%dT%H:%M:%SZ"),
            })
            self._attach(kit, record, f"build-{number:02d}.log")
            self._write(path, record)
        recovery = self._read(kit / "recovery.json")
        recovery["verdict"] = "pass"
        recovery["human"].update({
            "disconnect_flow_pass": True,
            "restart_restore_observed_pass": True,
            "retry_flow_pass": True,
        })
        recovery["machine"].update({
            "disconnect_restore_pass": True,
            "gate_ledger_entries_after": 0,
            "mission_entity_count_after": 0,
            "restart_restore_pass": True,
            "retry_restore_pass": True,
        })
        self._attach(kit, recovery, "recovery.log")
        self._write(kit / "recovery.json", recovery)
        performance = self._read(kit / "performance.json")
        performance["verdict"] = "pass"
        performance["human"].update({"fps_acceptance_pass": True, "frame_pacing_pass": True})
        performance["machine"].update({
            "cpu": "Fixture CPU",
            "fps_average": 90.0,
            "fps_one_percent_low": 61.0,
            "frame_time_p95_ms": 17.0,
            "gpu": "Fixture GPU",
            "ram_gib": 32,
            "sample_duration_seconds": 600,
        })
        self._attach(kit, performance, "performance.csv")
        self._write(kit / "performance.json", performance)
        soak = self._read(kit / "soak.json")
        soak["verdict"] = "pass"
        soak["human"].update({"residue_review_pass": True, "soak_experience_pass": True})
        soak["machine"].update({
            "duration_minutes": 60,
            "gate_ledger_entries_after": 0,
            "mission_entity_count_after": 0,
            "outside_envelope_chunks_after": 0,
            "region_file_count_after": 4,
            "region_file_count_before": 4,
            "terrain_chunk_count_after": 680,
            "terrain_chunk_count_before": 680,
            "unexpected_world_growth": False,
            "world_bytes_after": 3000000,
            "world_bytes_before": 2999000,
        })
        self._attach(kit, soak, "soak.log")
        self._write(kit / "soak.json", soak)

    def test_init_is_exclusive_and_creates_six_blank_builds(self) -> None:
        kit = self._init()
        self.assertEqual(6, len(list(kit.glob("build-*.json"))))
        self.assertEqual("pending", self._inspect_unfinalized(kit)["status"])
        for number in range(1, 7):
            record = self._read(kit / f"build-{number:02d}.json")
            self.assertIsNone(record["verdict"])
            self.assertIsNone(record["human"]["balance_pass"])
        with self.assertRaisesRegex(qualification.QualificationError, "refusing existing"):
            qualification.initialize_kit(
                self.candidate, kit, "session-002", CREATED, self.candidate_anchor,
            )

    def test_initializer_cannot_write_inside_or_over_candidate(self) -> None:
        before = sorted(path.name for path in self.candidate.iterdir())
        for name, output in (
            ("equal", self.candidate),
            ("inside", self.candidate / "qualification-kit"),
            ("ancestor", self.candidate.parent),
        ):
            with self.subTest(name=name), self.assertRaisesRegex(
                    qualification.QualificationError, "paths must be disjoint"):
                qualification.initialize_kit(
                    self.candidate, output, "session-overlap", CREATED,
                    self.candidate_anchor,
                )
        self.assertEqual(before, sorted(path.name for path in self.candidate.iterdir()))

    def test_current_contract_cannot_initialize_finalize_or_report_pass(self) -> None:
        kit = self._init("authorized-fixture")
        self._complete_pass(kit)
        blocked_output = self.temp / "blocked-init"
        with mock.patch.object(qualification, "SAFETY_ENVELOPE_ERRATUM_ID", None):
            with self.assertRaisesRegex(qualification.QualificationError, "cannot initialize"):
                qualification.initialize_kit(
                    self.candidate, blocked_output, "session-blocked", CREATED,
                    self.candidate_anchor,
                )
            with self.assertRaisesRegex(qualification.QualificationError, "cannot report"):
                self._finalize(kit)
            with self.assertRaisesRegex(qualification.QualificationError, "cannot report"):
                self._inspect_unfinalized(kit)
        self.assertFalse(blocked_output.exists())
        self.assertFalse((kit / qualification.FINAL_RECEIPT).exists())

    def test_erratum_alone_never_makes_rc5_or_proposed_rc6_eligible(self) -> None:
        historical = frozenset({
            "operation-ashen-span-mp25-rc5",
            "operation-ashen-span-mp25-rc6",
        })
        with mock.patch.object(qualification, "SAFETY_ENVELOPE_ERRATUM_ID", "ERRATUM-X"), \
                mock.patch.object(qualification, "CURRENT_PRE_ERRATUM_CANDIDATE_IDS", historical), \
                mock.patch.object(
                    qualification, "QUALIFIABLE_POST_ERRATUM_CANDIDATE_ID",
                    "operation-ashen-span-mp25-rc5",
                ):
            with self.assertRaisesRegex(qualification.QualificationError,
                                        "historical candidate.*permanently ineligible"):
                qualification.initialize_kit(
                    self.candidate, self.temp / "historical-rc5", "session-historical",
                    CREATED, self.candidate_anchor,
                )
            rc6 = self.temp / "historical-rc6-candidate"
            self._make_rc6_candidate(rc6)
            with self.assertRaisesRegex(qualification.QualificationError,
                                        "historical candidate.*permanently ineligible"):
                qualification.initialize_kit(
                    rc6, self.temp / "historical-rc6", "session-historical",
                    CREATED, self._candidate_anchor(rc6),
                )

    def test_initializer_is_byte_deterministic_for_fixed_inputs(self) -> None:
        first = self._init("first")
        second = self.temp / "second"
        qualification.initialize_kit(
            self.candidate, second, "session-001", CREATED, self.candidate_anchor,
        )
        first_files = {path.name: path.read_bytes() for path in first.iterdir() if path.is_file()}
        second_files = {path.name: path.read_bytes() for path in second.iterdir() if path.is_file()}
        self.assertEqual(first_files, second_files)

    def test_candidate_drift_is_rejected_before_receipt_write(self) -> None:
        kit = self._init()
        jar = self.candidate / "pomkotsmechs-forge-0.0.1-alpha.7-mp.25.jar"
        jar.write_bytes(jar.read_bytes() + b"drift")
        with self.assertRaisesRegex(qualification.QualificationError, "independently recorded"):
            self._finalize(kit)
        self.assertFalse((kit / "FINAL_RECEIPT.json").exists())

    def test_candidate_identity_and_receipt_status_are_locked(self) -> None:
        cases = (
            ("manifest-version", "MANIFEST.json", ("identity", "version_id"), "wrong-version"),
            ("manifest-map", "MANIFEST.json", ("identity", "map_id"), "another_map"),
            ("manifest-mission", "MANIFEST.json", ("identity", "mission_id"), "another_mission"),
            ("manifest-candidate", "MANIFEST.json", ("candidate_id",), "operation-ashen-span-mp25-rc7"),
            ("receipt-status", "OFFLINE_BUILD_RECEIPT.json", ("status",), "live-qualified"),
        )
        for name, filename, keys, replacement in cases:
            with self.subTest(name=name):
                candidate = self.temp / name
                shutil.copytree(self.candidate, candidate)
                value = self._read(candidate / filename)
                target = value
                for key in keys[:-1]:
                    target = target[key]
                target[keys[-1]] = replacement
                self._write(candidate / filename, value)
                self._refresh_sums(candidate)
                with self.assertRaises(qualification.QualificationError):
                    qualification.bind_candidate(candidate, self._candidate_anchor(candidate))

    def test_rc5_payloads_cannot_masquerade_as_rc6(self) -> None:
        candidate = self.temp / "masquerade"
        shutil.copytree(self.candidate, candidate)
        manifest = self._read(candidate / "MANIFEST.json")
        manifest["candidate_id"] = "operation-ashen-span-mp25-rc6"
        manifest["identity"].update({
            "display_name": "Mech Arena 0.8 - Operation Ashen Span MP25 RC6",
            "runtime_source_commit": RC6_COMMIT,
            "version_id": "0.8.0-operation-ashen-span-mp25-rc6",
        })
        self._write(candidate / "MANIFEST.json", manifest)
        receipt = self._read(candidate / "OFFLINE_BUILD_RECEIPT.json")
        receipt.update({"candidate_id": "operation-ashen-span-mp25-rc6", "runtime_source_commit": RC6_COMMIT})
        self._write(candidate / "OFFLINE_BUILD_RECEIPT.json", receipt)
        self._refresh_sums(candidate)
        with self.assertRaisesRegex(qualification.QualificationError, "independently recorded"):
            qualification.bind_candidate(candidate, self.candidate_anchor)

    def test_exact_rc6_candidate_shape_is_supported(self) -> None:
        candidate = self.temp / "rc6"
        new_mrpack, _ = self._make_rc6_candidate(candidate)

        with self.assertRaisesRegex(qualification.QualificationError, "independently recorded"):
            qualification.bind_candidate(candidate, self.candidate_anchor)
        binding = qualification.bind_candidate(candidate, self._candidate_anchor(candidate))
        self.assertEqual("operation-ashen-span-mp25-rc6", binding["candidate_id"])
        self.assertEqual(new_mrpack, binding["primary_artifacts"]["client_mrpack"]["file"])

    def test_pending_finalization_is_honest_and_exclusive(self) -> None:
        kit = self._init()
        receipt = self._finalize(kit)
        self.assertEqual("pending", receipt["status"])
        self.assertFalse(receipt["human_fields_complete"])
        with self.assertRaisesRegex(qualification.QualificationError, "refusing to overwrite"):
            self._finalize(kit)

    def test_complete_records_finalize_pass_with_attachment_hashes(self) -> None:
        kit = self._init()
        self._complete_pass(kit)
        receipt = self._finalize(kit)
        self.assertEqual("pass", receipt["status"])
        self.assertTrue(receipt["human_fields_complete"])
        self.assertEqual(9, len(receipt["attachment_files"]))
        self.assertRegex(receipt["evidence_tree_sha256"], r"^[0-9A-F]{64}$")
        receipt_sha = qualification.sha256_file(kit / "FINAL_RECEIPT.json")
        with self.assertRaisesRegex(qualification.QualificationError, "independently recorded"):
            qualification.inspect_kit(self.candidate, kit, self.candidate_anchor)
        inspection = qualification.inspect_kit(
            self.candidate, kit, self.candidate_anchor, receipt_sha,
        )
        self.assertTrue(inspection["finalized"])
        self.assertEqual(receipt["status"], inspection["status"])

    def test_final_receipt_rejects_post_finalization_evidence_mutation(self) -> None:
        kit = self._init()
        self._complete_pass(kit)
        self._finalize(kit)
        receipt_sha = qualification.sha256_file(kit / "FINAL_RECEIPT.json")
        attachment = kit / "attachments" / "build-01.log"
        attachment.write_bytes(attachment.read_bytes() + b"mutated")
        with self.assertRaisesRegex(qualification.QualificationError, "no longer matches"):
            qualification.inspect_kit(
                self.candidate, kit, self.candidate_anchor, receipt_sha,
            )
        self.assertEqual(1, self._main([
            "status", "--candidate-dir", str(self.candidate),
            "--candidate-tree-sha256", self.candidate_anchor, "--kit-dir", str(kit),
            "--receipt-sha256", receipt_sha,
        ]))

    def test_final_receipt_rejects_receipt_mutation_and_noncanonical_bytes(self) -> None:
        kit = self._init()
        self._finalize(kit)
        receipt_path = kit / "FINAL_RECEIPT.json"
        receipt_sha = qualification.sha256_file(receipt_path)
        receipt = self._read(receipt_path)
        receipt["status"] = "pass"
        self._write(receipt_path, receipt)
        with self.assertRaisesRegex(qualification.QualificationError, "SHA-256 differs"):
            qualification.inspect_kit(
                self.candidate, kit, self.candidate_anchor, receipt_sha,
            )

        second = self._init("noncanonical")
        self._finalize(second)
        second_receipt = second / "FINAL_RECEIPT.json"
        second_receipt.write_bytes(second_receipt.read_bytes().replace(b"\n", b"\r\n"))
        second_sha = qualification.sha256_file(second_receipt)
        with self.assertRaisesRegex(qualification.QualificationError, "not canonical"):
            qualification.inspect_kit(
                self.candidate, second, self.candidate_anchor, second_sha,
            )

    def test_supplied_receipt_anchor_rejects_deleted_receipt(self) -> None:
        kit = self._init()
        self._complete_pass(kit)
        self._finalize(kit)
        receipt_path = kit / "FINAL_RECEIPT.json"
        receipt_sha = qualification.sha256_file(receipt_path)
        receipt_path.unlink()
        with self.assertRaisesRegex(qualification.QualificationError, "is missing"):
            qualification.inspect_kit(
                self.candidate, kit, self.candidate_anchor, receipt_sha,
            )
        self.assertEqual(1, self._main([
            "status", "--candidate-dir", str(self.candidate),
            "--candidate-tree-sha256", self.candidate_anchor,
            "--kit-dir", str(kit), "--receipt-sha256", receipt_sha,
        ]))

    def test_explicit_fail_requires_issue_and_dominates_pending(self) -> None:
        kit = self._init()
        record = self._read(kit / "build-01.json")
        record["verdict"] = "fail"
        with self.assertRaisesRegex(qualification.QualificationError, "requires at least one issue"):
            self._write(kit / "build-01.json", record)
            self._inspect_unfinalized(kit)
        record["human"]["issues"] = ["Gatekeeper audio cue was inaudible."]
        self._write(kit / "build-01.json", record)
        self.assertEqual("fail", self._inspect_unfinalized(kit)["status"])
        receipt = self._finalize(kit)
        self.assertEqual("fail", receipt["status"])
        self.assertFalse(receipt["human_fields_complete"])

    def test_pass_claim_fails_closed_when_duration_is_outside_target(self) -> None:
        kit = self._init()
        self._complete_pass(kit)
        record = self._read(kit / "build-03.json")
        record["machine"]["mission_duration_seconds"] = 599
        started = dt.datetime.strptime(record["machine"]["started_utc"], "%Y-%m-%dT%H:%M:%SZ")
        record["machine"]["ended_utc"] = (started + dt.timedelta(seconds=599)).strftime("%Y-%m-%dT%H:%M:%SZ")
        record["machine"]["phase_elapsed_seconds"]["P6"] = 550
        record["machine"]["phase_elapsed_seconds"]["VICTORY"] = 599
        self._write(kit / "build-03.json", record)
        with self.assertRaisesRegex(qualification.QualificationError, "claims pass"):
            self._finalize(kit)
        self.assertFalse((kit / "FINAL_RECEIPT.json").exists())

    def test_contradictory_machine_timing_is_rejected(self) -> None:
        kit = self._init()
        self._complete_pass(kit)
        record = self._read(kit / "build-02.json")
        record["machine"]["ended_utc"] = "2026-08-08T12:30:00Z"
        self._write(kit / "build-02.json", record)
        with self.assertRaisesRegex(qualification.QualificationError, "timestamps contradict"):
            self._inspect_unfinalized(kit)

    def test_performance_pass_requires_five_minute_sample(self) -> None:
        kit = self._init()
        self._complete_pass(kit)
        record = self._read(kit / "performance.json")
        record["machine"]["sample_duration_seconds"] = 299
        self._write(kit / "performance.json", record)
        with self.assertRaisesRegex(qualification.QualificationError, "claims pass"):
            self._inspect_unfinalized(kit)

    def test_unreferenced_and_missing_attachments_are_rejected(self) -> None:
        kit = self._init()
        (kit / "attachments" / "orphan.log").write_text("orphan", encoding="utf-8")
        with self.assertRaisesRegex(qualification.QualificationError, "unreferenced"):
            self._inspect_unfinalized(kit)
        (kit / "attachments" / "orphan.log").unlink()
        record = self._read(kit / "build-01.json")
        record["attachment_paths"] = ["attachments/missing.log"]
        self._write(kit / "build-01.json", record)
        with self.assertRaisesRegex(qualification.QualificationError, "missing"):
            self._inspect_unfinalized(kit)

    def test_attachment_traversal_is_rejected(self) -> None:
        kit = self._init()
        record = self._read(kit / "build-01.json")
        record["attachment_paths"] = ["attachments/../outside.log"]
        self._write(kit / "build-01.json", record)
        with self.assertRaisesRegex(qualification.QualificationError, "unsafe attachment"):
            self._inspect_unfinalized(kit)

    def test_missing_or_duplicate_json_structure_is_rejected(self) -> None:
        kit = self._init()
        (kit / "build-06.json").unlink()
        with self.assertRaisesRegex(qualification.QualificationError, "missing required records"):
            self._inspect_unfinalized(kit)
        kit = self._init("duplicate")
        (kit / "recovery.json").write_text('{"schema_version":1,"schema_version":1}', encoding="utf-8")
        with self.assertRaisesRegex(qualification.QualificationError, "duplicate JSON key"):
            self._inspect_unfinalized(kit)

    def test_final_receipt_is_deterministic_for_identical_kits(self) -> None:
        first = self._init("first")
        second = self.temp / "second"
        qualification.initialize_kit(
            self.candidate, second, "session-001", CREATED, self.candidate_anchor,
        )
        self._complete_pass(first)
        self._complete_pass(second)
        self._finalize(first)
        self._finalize(second)
        self.assertEqual((first / "FINAL_RECEIPT.json").read_bytes(), (second / "FINAL_RECEIPT.json").read_bytes())

    def test_cli_exit_codes_distinguish_pending_pass_fail_and_invalid(self) -> None:
        pending = self._init("pending")
        self.assertEqual(2, self._main([
            "status", "--candidate-dir", str(self.candidate),
            "--candidate-tree-sha256", self.candidate_anchor,
            "--kit-dir", str(pending), "--unfinalized",
        ]))
        self.assertEqual(2, self._main([
            "finalize", "--candidate-dir", str(self.candidate),
            "--candidate-tree-sha256", self.candidate_anchor,
            "--kit-dir", str(pending),
        ]))

        passed = self._init("passed")
        self._complete_pass(passed)
        self.assertEqual(0, self._main([
            "status", "--candidate-dir", str(self.candidate),
            "--candidate-tree-sha256", self.candidate_anchor,
            "--kit-dir", str(passed), "--unfinalized",
        ]))

        failed = self._init("failed")
        record = self._read(failed / "build-01.json")
        record["verdict"] = "fail"
        record["human"]["issues"] = ["Observed failure."]
        self._write(failed / "build-01.json", record)
        self.assertEqual(3, self._main([
            "status", "--candidate-dir", str(self.candidate),
            "--candidate-tree-sha256", self.candidate_anchor,
            "--kit-dir", str(failed), "--unfinalized",
        ]))

        invalid = self._init("invalid")
        (invalid / "build-04.json").unlink()
        self.assertEqual(1, self._main([
            "status", "--candidate-dir", str(self.candidate),
            "--candidate-tree-sha256", self.candidate_anchor,
            "--kit-dir", str(invalid), "--unfinalized",
        ]))


if __name__ == "__main__":
    unittest.main()
