"""No-network tests for the Operation Ashen Span fresh-profile auditor."""

from __future__ import annotations

import hashlib
import importlib.util
import io
import json
from pathlib import Path
import stat
import sys
import tempfile
import unittest
from unittest import mock
import urllib.error
import urllib.request
import zipfile


TOOLS = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("ashen_span_install_test", TOOLS / "verify_ashen_span_install.py")
if spec is None or spec.loader is None:
    raise RuntimeError("cannot load install auditor")
auditor = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = auditor
spec.loader.exec_module(auditor)

FIXED_TIME = "2026-08-08T12:00:00Z"
VERSION_ID = "0.8.0-operation-ashen-span-mp25-rc6"
CANDIDATE_ID = "operation-ashen-span-mp25-rc6"
DISPLAY_NAME = "Mech Arena 0.8 - Operation Ashen Span MP25 RC6"


def forge_jar(mod_id: str | None) -> bytes:
    entries: list[tuple[str, bytes, int | None]] = [("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\n", None)]
    if mod_id is not None:
        entries.append((
            "META-INF/mods.toml",
            (
                'modLoader="javafml"\nloaderVersion="[47,)"\nlicense="MIT"\n'
                f'[[mods]]\nmodId="{mod_id}"\nversion="1.0.0"\ndisplayName="{mod_id}"\n'
            ).encode(),
            None,
        ))
    return make_zip(entries)


def make_zip(entries: list[tuple[str, bytes, int | None]]) -> bytes:
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, blob, unix_mode in entries:
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.compress_type = zipfile.ZIP_DEFLATED
            mode = (stat.S_IFREG | 0o644) if unix_mode is None else unix_mode
            info.external_attr = mode << 16
            archive.writestr(info, blob, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
    return output.getvalue()


class FakeResponse(io.BytesIO):
    def __init__(self, blob: bytes, url: str, *, status: int = 200,
                 content_length: str | None = None, encoding: str | None = None) -> None:
        super().__init__(blob)
        self.status = status
        self._url = url
        self.headers: dict[str, str] = {}
        if content_length is not False:  # type: ignore[comparison-overlap]
            self.headers["Content-Length"] = str(len(blob)) if content_length is None else content_length
        if encoding is not None:
            self.headers["Content-Encoding"] = encoding

    def geturl(self) -> str:
        return self._url

    def __enter__(self) -> "FakeResponse":
        return self

    def __exit__(self, exc_type: object, exc: object, traceback: object) -> None:
        self.close()


class FakeOpener:
    def __init__(self, blobs: dict[str, bytes], *, final_urls: dict[str, str] | None = None,
                 statuses: dict[str, int] | None = None, lengths: dict[str, str] | None = None,
                 encodings: dict[str, str] | None = None) -> None:
        self.blobs = blobs
        self.final_urls = final_urls or {}
        self.statuses = statuses or {}
        self.lengths = lengths or {}
        self.encodings = encodings or {}
        self.calls: list[tuple[str, float]] = []

    def open(self, url: str, timeout: float) -> FakeResponse:
        self.calls.append((url, timeout))
        if url not in self.blobs:
            raise urllib.error.URLError("fixture has no response")
        return FakeResponse(
            self.blobs[url],
            self.final_urls.get(url, url),
            status=self.statuses.get(url, 200),
            content_length=self.lengths.get(url),
            encoding=self.encodings.get(url),
        )


class Fixture:
    def __init__(self, root: Path) -> None:
        self.root = root
        self.candidate = root / "candidate"
        self.candidate.mkdir()
        self.rc_number = 6
        self.candidate_id = CANDIDATE_ID
        self.version_id = VERSION_ID
        self.display_name = DISPLAY_NAME
        self.dep_blobs = [forge_jar(f"dependency_{index}") for index in range(9)]
        self.urls = [
            f"https://cdn.modrinth.com/data/fixture/versions/fixture/dependency-{index}.jar"
            for index in range(9)
        ]
        self.index: dict[str, object] = {
            "dependencies": {"forge": "47.3.3", "minecraft": "1.20.1"},
            "files": [self.record(index) for index in range(9)],
            "formatVersion": 1,
            "game": "minecraft",
            "name": self.display_name,
            "summary": "offline fixture",
            "versionId": self.version_id,
        }
        self.embedded = {
            "pomkotsmechs.jar": forge_jar("pomkotsmechs"),
            "lostcities.jar": forge_jar("lostcities"),
            "mecharena_sector01.jar": forge_jar("mecharena_sector01"),
        }
        self.overrides: list[tuple[str, bytes, int | None]] = [
            (f"overrides/mods/{name}", blob, None) for name, blob in self.embedded.items()
        ]
        self.overrides.append(("overrides/config/pomkotsmechs.json", b"{}\n", None))
        self.source_commit = "a" * 40
        self.write_candidate()

    def record(self, index: int) -> dict[str, object]:
        blob = self.dep_blobs[index]
        return {
            "downloads": [self.urls[index]],
            "env": {"client": "required", "server": "required"},
            "fileSize": len(blob),
            "hashes": {
                "sha1": hashlib.sha1(blob).hexdigest(),
                "sha512": hashlib.sha512(blob).hexdigest(),
            },
            "path": f"mods/dependency-{index}.jar",
        }

    def refresh_record(self, index: int) -> None:
        files = self.index["files"]
        assert isinstance(files, list)
        files[index] = self.record(index)

    def write_candidate(self) -> None:
        entries = [(auditor.INDEX_NAME, auditor.canonical_json(self.index), None), *self.overrides]
        mrpack = make_zip(entries)
        mrpack_name = f"mech-arena-0.8.0-operation-ashen-span-mp25-rc{self.rc_number}.mrpack"
        for old in self.candidate.glob("*.mrpack"):
            if old.name != mrpack_name:
                old.unlink()
        mrpack_sha = hashlib.sha256(mrpack).hexdigest().upper()
        artifact = {"bytes": len(mrpack), "file": mrpack_name, "sha256": mrpack_sha}
        manifest = {
            "artifacts": {mrpack_name: artifact},
            "candidate_id": self.candidate_id,
            "client_contract": {"embedded_mods": sorted(self.embedded)},
            "identity": {
                "display_name": self.display_name,
                "forge": "47.3.3",
                "map_id": "cold_ruin_sector_01",
                "minecraft": "1.20.1",
                "mission_id": "operation_ashen_span",
                "source_commit": self.source_commit,
                "version_id": self.version_id,
            },
            "schema_version": 1,
            "status": "offline-candidate-not-live-qualified",
        }
        receipt = {
            "candidate_id": self.candidate_id,
            "outputs": {mrpack_name: artifact},
            "schema_version": 1,
            "source_commit": self.source_commit,
            "status": "offline-only-not-live-qualified",
        }
        (self.candidate / mrpack_name).write_bytes(mrpack)
        (self.candidate / auditor.MANIFEST_NAME).write_bytes(auditor.canonical_json(manifest))
        (self.candidate / auditor.OFFLINE_RECEIPT_NAME).write_bytes(auditor.canonical_json(receipt))
        supporting_files = {
            "ASHEN_SPAN_LIVE_VALIDATION.md": b"fixture live validation boundary\n",
            "ROLLBACK.md": b"fixture rollback\n",
            "RUNBOOK.md": b"fixture runbook\n",
            "SHA256SUMS.txt": b"fixture sums are not a trusted binding\n",
            "THIRD_PARTY_NOTICES.md": b"fixture notices\n",
            "cold_ruin_sector_01-mp25-world.zip": b"fixture world archive\n",
            f"mech-arena-operation-ashen-span-mp25-rc{self.rc_number}-server-overlay.zip": b"fixture server overlay\n",
            "pomkotsmechs-forge-0.0.1-alpha.7-mp.25.jar": b"fixture outer runtime\n",
        }
        for old in self.candidate.glob("mech-arena-operation-ashen-span-mp25-rc*-server-overlay.zip"):
            if old.name not in supporting_files:
                old.unlink()
        for name, blob in supporting_files.items():
            (self.candidate / name).write_bytes(blob)

    def binding(self, *, source_commit: str | None = None,
                tree_sha256: str | None = None) -> auditor.ExpectedCandidateBinding:
        inventory = auditor.locked_candidate_inventory(self.candidate_id)
        measured_tree, _ = auditor.measure_candidate_tree(self.candidate, inventory, auditor.Limits())
        return auditor.parse_expected_candidate_binding({
            "authority": "test-independent-verifier",
            "candidate_id": self.candidate_id,
            "candidate_tree_sha256": tree_sha256 or measured_tree,
            "inventory": list(inventory),
            "schema_version": 1,
            "source_commit": source_commit or self.source_commit,
        })

    def rewrite_as_rc5(self) -> None:
        self.rc_number = 5
        self.candidate_id = auditor.RC5_CANDIDATE_ID
        self.version_id = "0.8.0-operation-ashen-span-mp25-rc5"
        self.display_name = "Mech Arena 0.8 - Operation Ashen Span MP25 RC5"
        self.source_commit = auditor.RC5_SOURCE_COMMIT
        self.index["versionId"] = self.version_id
        self.index["name"] = self.display_name
        self.write_candidate()

    def opener(self, **kwargs: object) -> FakeOpener:
        return FakeOpener(dict(zip(self.urls, self.dep_blobs)), **kwargs)


class InstallAuditTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.fixture = Fixture(self.root)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def run_audit(self, *, opener: FakeOpener | None = None, keep: bool = False,
                  limits: auditor.Limits = auditor.Limits(), receipt_name: str = "audit.json",
                  binding: auditor.ExpectedCandidateBinding | None | object = ...):
        receipt = self.root / receipt_name
        profile = self.root / f"profile-{receipt_name}" if keep else None
        result = auditor.run_install_audit(
            self.fixture.candidate,
            receipt,
            profile,
            opener=opener or self.fixture.opener(),
            limits=limits,
            expected_binding=self.fixture.binding() if binding is ... else binding,
            clock=lambda: FIXED_TIME,
        )
        return result, receipt, profile

    def assert_failure(self, result: dict[str, object], text: str) -> None:
        self.assertEqual("fail", result["status"])
        failure = result["failure"]
        self.assertIsInstance(failure, dict)
        self.assertIn(text, str(failure["message"]))
        self.assertFalse(result["evidence_status"]["launched"])

    def test_disposable_profile_passes_with_nine_mocked_downloads(self) -> None:
        opener = self.fixture.opener()
        result, receipt, profile = self.run_audit(opener=opener)
        self.assertEqual("pass", result["status"])
        self.assertEqual("temporary-discarded", result["materialization"]["mode"])
        self.assertEqual(9, len(result["network"]["downloads"]))
        self.assertEqual(12, result["profile"]["jar_count"])
        self.assertEqual(9, len(opener.calls))
        self.assertIsNone(profile)
        self.assertEqual(auditor.canonical_json(result), receipt.read_bytes())

    def test_pass_receipt_records_external_candidate_binding(self) -> None:
        expected = self.fixture.binding()
        result, _, _ = self.run_audit(binding=expected)
        self.assertEqual("pass", result["status"])
        binding = result["candidate_binding"]
        self.assertEqual("external-explicit", binding["mode"])
        self.assertEqual(expected.authority, binding["authority"])
        self.assertEqual(
            hashlib.sha256(auditor.canonical_json(auditor.candidate_binding_document(expected))).hexdigest().upper(),
            binding["binding_canonical_sha256"],
        )
        self.assertEqual(expected.source_commit, binding["source_commit"])
        self.assertEqual(expected.candidate_tree_sha256, binding["expected_candidate_tree_sha256"])
        self.assertEqual(expected.candidate_tree_sha256, binding["measured_candidate_tree_sha256"])
        self.assertEqual(list(expected.inventory), binding["expected_inventory"])
        self.assertEqual(11, len(binding["measured_inventory"]))

    def test_synthetic_future_candidate_without_external_binding_cannot_mint_pass(self) -> None:
        opener = self.fixture.opener()
        result, _, _ = self.run_audit(opener=opener, binding=None)
        self.assert_failure(result, "require an explicit external candidate binding")
        self.assertEqual("candidate_binding", result["failure"]["stage"])
        self.assertEqual([], opener.calls)

    def test_candidate_with_arbitrary_source_cannot_override_external_binding(self) -> None:
        opener = self.fixture.opener()
        expected = self.fixture.binding(source_commit="b" * 40)
        result, _, _ = self.run_audit(opener=opener, binding=expected)
        self.assert_failure(result, "source commit differs from the external candidate binding")
        self.assertEqual([], opener.calls)

    def test_wrong_external_tree_binding_fails_before_network(self) -> None:
        opener = self.fixture.opener()
        expected = self.fixture.binding(tree_sha256="0" * 64)
        result, _, _ = self.run_audit(opener=opener, binding=expected)
        self.assert_failure(result, "tree differs from the external immutable candidate binding")
        self.assertEqual([], opener.calls)

    def test_parsed_candidate_bytes_must_match_later_tree_measurement(self) -> None:
        loaded = auditor.load_candidate(self.fixture.candidate, auditor.Limits())
        mrpack = self.fixture.candidate / loaded.mrpack_name
        mrpack.write_bytes(mrpack.read_bytes() + b"changed-after-parse")
        expected = self.fixture.binding()
        with self.assertRaisesRegex(auditor.AuditError, "changed between parsing and immutable tree binding"):
            auditor.bind_candidate(loaded, expected, auditor.Limits())

    def test_matching_three_file_binding_cannot_weaken_exact_inventory(self) -> None:
        keep = {auditor.MANIFEST_NAME, auditor.OFFLINE_RECEIPT_NAME, next(self.fixture.candidate.glob("*.mrpack")).name}
        for child in self.fixture.candidate.iterdir():
            if child.name not in keep:
                child.unlink()
        facts = []
        for child in sorted(self.fixture.candidate.iterdir(), key=lambda item: item.name):
            blob = child.read_bytes()
            facts.append(auditor.CandidateFileFact(child.name, len(blob), hashlib.sha256(blob).hexdigest().upper()))
        forged = auditor.ExpectedCandidateBinding(
            authority="self-consistent-but-untrusted",
            candidate_id=self.fixture.candidate_id,
            source_commit=self.fixture.source_commit,
            candidate_tree_sha256=auditor.candidate_tree_sha256(facts),
            inventory=tuple(sorted(keep)),
        )
        opener = self.fixture.opener()
        result, _, _ = self.run_audit(opener=opener, binding=forged)
        self.assert_failure(result, "exact 11-file Ashen Span RC inventory")
        self.assertEqual([], opener.calls)

    def test_synthetic_rc5_cannot_override_builtin_immutable_tree(self) -> None:
        self.fixture.rewrite_as_rc5()
        opener = self.fixture.opener()
        result, _, _ = self.run_audit(opener=opener, binding=None)
        self.assert_failure(result, "tree differs from the external immutable candidate binding")
        self.assertEqual("candidate_binding", result["failure"]["stage"])
        self.assertEqual([], opener.calls)

    def test_kept_profile_is_new_complete_and_receipt_is_canonical(self) -> None:
        result, receipt, profile = self.run_audit(keep=True)
        assert profile is not None
        self.assertEqual("pass", result["status"])
        self.assertTrue(profile.is_dir())
        self.assertTrue((profile / "mods" / "pomkotsmechs.jar").is_file())
        self.assertTrue((profile / "mods" / "dependency-8.jar").is_file())
        self.assertEqual(result["profile"]["file_count"], sum(path.is_file() for path in profile.rglob("*")))
        self.assertEqual(auditor.canonical_json(json.loads(receipt.read_bytes())), receipt.read_bytes())

    def test_same_inputs_and_clock_produce_identical_canonical_receipts(self) -> None:
        _, first, _ = self.run_audit(receipt_name="first.json")
        _, second, _ = self.run_audit(receipt_name="second.json")
        self.assertEqual(first.read_bytes(), second.read_bytes())

    def test_existing_receipt_is_never_overwritten(self) -> None:
        receipt = self.root / "audit.json"
        receipt.write_bytes(b"sentinel")
        with self.assertRaisesRegex(auditor.AuditError, "already exists"):
            auditor.run_install_audit(
                self.fixture.candidate, receipt, opener=self.fixture.opener(), clock=lambda: FIXED_TIME
            )
        self.assertEqual(b"sentinel", receipt.read_bytes())

    def test_receipt_output_must_be_disjoint_from_immutable_candidate(self) -> None:
        before = {child.name: hashlib.sha256(child.read_bytes()).hexdigest()
                  for child in self.fixture.candidate.iterdir()}
        for label, receipt in (
            ("inside", self.fixture.candidate / "AUDIT.json"),
            ("equal", self.fixture.candidate),
            ("containing", self.fixture.candidate.parent),
        ):
            with self.subTest(label=label):
                existed = receipt.exists()
                opener = self.fixture.opener()
                with self.assertRaisesRegex(auditor.AuditError, "disjoint from the immutable candidate"):
                    auditor.run_install_audit(
                        self.fixture.candidate,
                        receipt,
                        opener=opener,
                        expected_binding=self.fixture.binding(),
                        clock=lambda: FIXED_TIME,
                    )
                self.assertEqual(existed, receipt.exists())
                self.assertEqual([], opener.calls)
        self.assertEqual(11, len(list(self.fixture.candidate.iterdir())))
        self.assertEqual(before, {
            child.name: hashlib.sha256(child.read_bytes()).hexdigest()
            for child in self.fixture.candidate.iterdir()
        })

    def test_kept_profile_must_neither_contain_nor_be_inside_candidate(self) -> None:
        before = {child.name: hashlib.sha256(child.read_bytes()).hexdigest()
                  for child in self.fixture.candidate.iterdir()}
        for label, profile in (
            ("inside", self.fixture.candidate / "profile"),
            ("equal", self.fixture.candidate),
            ("containing", self.fixture.candidate.parent),
        ):
            with self.subTest(label=label):
                opener = self.fixture.opener()
                receipt = self.root / f"{label}-audit.json"
                with self.assertRaisesRegex(auditor.AuditError, "disjoint from the immutable candidate"):
                    auditor.run_install_audit(
                        self.fixture.candidate,
                        receipt,
                        profile,
                        opener=opener,
                        expected_binding=self.fixture.binding(),
                        clock=lambda: FIXED_TIME,
                    )
                self.assertFalse(receipt.exists())
                self.assertEqual([], opener.calls)
        self.assertEqual(11, len(list(self.fixture.candidate.iterdir())))
        self.assertEqual(before, {
            child.name: hashlib.sha256(child.read_bytes()).hexdigest()
            for child in self.fixture.candidate.iterdir()
        })

    def test_existing_profile_is_never_used_or_overwritten(self) -> None:
        profile = self.root / "profile"
        profile.mkdir()
        sentinel = profile / "sentinel"
        sentinel.write_bytes(b"keep")
        with self.assertRaisesRegex(auditor.AuditError, "already exists"):
            auditor.run_install_audit(
                self.fixture.candidate, self.root / "audit.json", profile,
                opener=self.fixture.opener(), clock=lambda: FIXED_TIME,
            )
        self.assertEqual(b"keep", sentinel.read_bytes())

    def test_receipt_publish_failure_removes_new_kept_profile(self) -> None:
        profile = self.root / "profile"
        with mock.patch.object(auditor, "write_exclusive", side_effect=OSError("receipt failure")):
            with self.assertRaisesRegex(OSError, "receipt failure"):
                auditor.run_install_audit(
                    self.fixture.candidate,
                    self.root / "audit.json",
                    profile,
                    opener=self.fixture.opener(),
                    expected_binding=self.fixture.binding(),
                    clock=lambda: FIXED_TIME,
                )
        self.assertFalse(profile.exists())

    def test_candidate_hash_mismatch_writes_fail_receipt_before_network(self) -> None:
        mrpack = next(self.fixture.candidate.glob("*.mrpack"))
        mrpack.write_bytes(mrpack.read_bytes() + b"drift")
        opener = self.fixture.opener()
        result, receipt, _ = self.run_audit(opener=opener)
        self.assert_failure(result, "do not match manifest/receipt identity")
        self.assertEqual([], opener.calls)
        self.assertEqual(auditor.canonical_json(result), receipt.read_bytes())

    def test_candidate_and_index_version_identity_mismatch_fails(self) -> None:
        self.fixture.index["versionId"] = "0.8.0-operation-ashen-span-mp25-rc5"
        self.fixture.write_candidate()
        result, _, _ = self.run_audit()
        self.assert_failure(result, "versionId disagrees")

    def test_http_index_url_is_rejected_before_network(self) -> None:
        files = self.fixture.index["files"]
        assert isinstance(files, list)
        files[0]["downloads"] = ["http://cdn.modrinth.com/not-https.jar"]
        self.fixture.write_candidate()
        opener = self.fixture.opener()
        result, _, _ = self.run_audit(opener=opener)
        self.assert_failure(result, "must use HTTPS")
        self.assertEqual([], opener.calls)

    def test_unapproved_https_host_is_rejected(self) -> None:
        files = self.fixture.index["files"]
        assert isinstance(files, list)
        files[0]["downloads"] = ["https://example.invalid/dependency.jar"]
        self.fixture.write_candidate()
        result, _, _ = self.run_audit()
        self.assert_failure(result, "host is not approved")

    def test_redirect_downgrade_is_rejected_and_profile_removed(self) -> None:
        final_urls = {self.fixture.urls[0]: "http://cdn.modrinth.com/dependency-0.jar"}
        opener = self.fixture.opener(final_urls=final_urls)
        result, _, profile = self.run_audit(opener=opener, keep=True)
        self.assert_failure(result, "final download URL must use HTTPS")
        assert profile is not None
        self.assertFalse(profile.exists())

    def test_redirect_handler_rejects_downgrade_before_following(self) -> None:
        handler = auditor.HttpsOnlyRedirectHandler()
        request = urllib.request.Request(self.fixture.urls[0])
        with self.assertRaisesRegex(auditor.AuditError, "redirect URL must use HTTPS"):
            handler.redirect_request(request, io.BytesIO(), 302, "Found", {}, "http://cdn.modrinth.com/x")

    def test_wrong_download_size_fails_closed(self) -> None:
        blobs = dict(zip(self.fixture.urls, self.fixture.dep_blobs))
        blobs[self.fixture.urls[0]] += b"extra"
        result, _, _ = self.run_audit(opener=FakeOpener(blobs))
        self.assert_failure(result, "Content-Length disagrees")

    def test_wrong_sha1_fails_closed(self) -> None:
        files = self.fixture.index["files"]
        assert isinstance(files, list)
        files[0]["hashes"]["sha1"] = "0" * 40
        self.fixture.write_candidate()
        result, _, _ = self.run_audit()
        self.assert_failure(result, "SHA-1 disagrees")

    def test_wrong_sha512_fails_closed(self) -> None:
        files = self.fixture.index["files"]
        assert isinstance(files, list)
        files[0]["hashes"]["sha512"] = "0" * 128
        self.fixture.write_candidate()
        result, _, _ = self.run_audit()
        self.assert_failure(result, "SHA-512 disagrees")

    def test_content_encoding_is_rejected(self) -> None:
        result, _, _ = self.run_audit(opener=self.fixture.opener(encodings={self.fixture.urls[0]: "gzip"}))
        self.assert_failure(result, "content encoding")

    def test_indexed_size_bound_is_enforced_before_network(self) -> None:
        maximum = len(self.fixture.dep_blobs[0]) - 1
        opener = self.fixture.opener()
        result, _, _ = self.run_audit(
            opener=opener,
            limits=auditor.Limits(max_indexed_file_bytes=maximum),
        )
        self.assert_failure(result, "fileSize is invalid or exceeds")
        self.assertEqual([], opener.calls)

    def test_index_path_traversal_is_rejected(self) -> None:
        files = self.fixture.index["files"]
        assert isinstance(files, list)
        files[0]["path"] = "../outside.jar"
        self.fixture.write_candidate()
        result, _, _ = self.run_audit()
        self.assert_failure(result, "traversal")

    def test_override_path_traversal_is_rejected(self) -> None:
        self.fixture.overrides.append(("overrides/../outside.txt", b"escape", None))
        self.fixture.write_candidate()
        result, _, _ = self.run_audit()
        self.assert_failure(result, "traversal")
        self.assertFalse((self.root / "outside.txt").exists())

    def test_archive_case_collision_is_rejected(self) -> None:
        self.fixture.overrides.extend([
            ("overrides/config/Case.txt", b"one", None),
            ("overrides/config/case.txt", b"two", None),
        ])
        self.fixture.write_candidate()
        result, _, _ = self.run_audit()
        self.assert_failure(result, "archive member collision")

    def test_override_and_index_output_collision_is_rejected(self) -> None:
        self.fixture.overrides.append(("overrides/mods/dependency-0.jar", forge_jar("other"), None))
        self.fixture.write_candidate()
        result, _, _ = self.run_audit()
        self.assert_failure(result, "output path collision")

    def test_symlink_archive_member_is_rejected(self) -> None:
        self.fixture.overrides.append(("overrides/config/link", b"target", stat.S_IFLNK | 0o777))
        self.fixture.write_candidate()
        result, _, _ = self.run_audit()
        self.assert_failure(result, "symlink MRPack member")

    def test_duplicate_mod_id_across_jars_fails_and_cleans_profile(self) -> None:
        self.fixture.dep_blobs[1] = forge_jar("dependency_0")
        self.fixture.refresh_record(1)
        self.fixture.write_candidate()
        result, _, profile = self.run_audit(keep=True)
        self.assert_failure(result, "duplicate mod ID dependency_0")
        assert profile is not None
        self.assertFalse(profile.exists())

    def test_jar_without_mod_metadata_fails(self) -> None:
        self.fixture.dep_blobs[2] = forge_jar(None)
        self.fixture.refresh_record(2)
        self.fixture.write_candidate()
        result, _, _ = self.run_audit()
        self.assert_failure(result, "no recognized mod metadata")

    def test_fewer_than_nine_indexed_dependencies_fails(self) -> None:
        files = self.fixture.index["files"]
        assert isinstance(files, list)
        files.pop()
        self.fixture.write_candidate()
        result, _, _ = self.run_audit()
        self.assert_failure(result, "exactly 9")


if __name__ == "__main__":
    unittest.main()
