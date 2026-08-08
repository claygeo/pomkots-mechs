# Operation Ashen Span fresh-profile audit

> **Install-path evidence only — do not launch or qualify RC5.** The audit below
> proves deterministic materialization, not a playable bounded mission. Normal
> player tickets violate the locked 680-chunk safety envelope; see
> [the safety-envelope blocker](SAFETY_ENVELOPE_BLOCKER.md). No releasable RC6
> currently exists.

`tools/verify_ashen_span_install.py` proves that an immutable Ashen Span candidate can
be expanded into a clean client profile without a launcher, account, Java process,
Minecraft client, GUI, or popup. It is a bounded package/install audit, not a gameplay
test and not a general Modrinth installer.

The auditor's trust anchor is outside the candidate itself. Immutable RC5 is pinned
in auditor source to candidate tree
`1E284A0555A421E5084B2A9425B70AA573C2ADBB382E0CCA8A6A3E8B012B38DB`, source
commit `d96b7b84688e925f311849d7c40f72a4f8a691c2`, and its exact 11-file root inventory.
Any later RC must also provide `--candidate-binding <independent-binding.json>`; that
schema-1 binding supplies its expected candidate ID, source commit, tree SHA-256, and
the exact locked 11 filenames. Candidate-local manifest/receipt hashes cannot replace
this independent binding.

## Disposable audit (default)

From the `pomkots-mechs` repository root, create an evidence directory and choose a
new receipt filename:

```powershell
New-Item -ItemType Directory -Force build | Out-Null
python tools\verify_ashen_span_install.py `
  --candidate-dir ..\modpack\candidates\ashen-span-mp25-rc5 `
  --receipt build\ashen-span-install-audit-rc5.json
```

The default profile is private temporary storage. It is deleted after its inventory
has been audited and the canonical JSON receipt has been assembled. The receipt parent
must already exist, and the receipt itself must not exist; the auditor never overwrites
evidence.

## Retain an audited profile

Add `--keep-profile` only when a materialized, still-unlaunched profile is useful:

```powershell
python tools\verify_ashen_span_install.py `
  --candidate-dir ..\modpack\candidates\ashen-span-mp25-rc5 `
  --receipt build\ashen-span-install-audit-kept.json `
  --keep-profile build\ashen-span-clean-profile
```

The kept destination and receipt must both be new. The destination's parent must exist.
If any validation, download, hash, archive, or mod-inventory gate fails, the newly
created profile is removed and a canonical `status: "fail"` receipt is written. Existing
profiles are never merged, repaired, or deleted. Receipt and retained-profile paths
must be wholly disjoint from the immutable candidate root: neither equal to it, inside
it, nor an ancestor containing it.

## Fail-closed contract

Before network access or profile writes, the auditor checks:

- the complete candidate root is the exact externally bound 11-file inventory and its
  domain-separated tree SHA-256 matches the immutable RC5 anchor or explicit later-RC
  binding;
- manifest, offline receipt, source commit, candidate ID, RC number, display/version
  IDs, and actual MRPack size/SHA-256 all agree;
- the candidate remains offline-only and binds Minecraft 1.20.1, Forge 47.3.3,
  `cold_ruin_sector_01`, and `operation_ashen_span`;
- the MRPack has one root index, exactly nine indexed client dependencies, and only
  `overrides`/`client-overrides` payload files;
- every archive and output path is normalized, traversal-free, non-reserved,
  Unicode-NFC, and unique under case-insensitive comparison;
- ZIP members are bounded, unencrypted ordinary files/directories, never symlinks or
  special files, and the expanded override total is bounded;
- indexed files are direct `mods/*.jar` outputs and cannot collide with an override;
- override mod JARs exactly match the manifest's embedded-mod list.

Downloads are restricted to `https://cdn.modrinth.com` on the default HTTPS port.
Credentials, fragments, HTTP URLs, nonapproved hosts, encoded responses, and redirect
downgrades are rejected. Redirects are capped at five and every hop is revalidated.
Each download is streamed with a timeout and must match the index's exact byte count,
SHA-1, and SHA-512 before its temporary file is published.

The final profile walk rejects links, special files, missing files, and extra files.
Every top-level mod JAR must expose recognized Forge, Fabric, or Quilt metadata. The
receipt records each JAR's SHA-256 and mod IDs, and duplicate mod IDs across JARs fail
the audit with both paths named.

Default limits are 512 MiB per candidate file, 1 GiB for the bound candidate root,
128 MiB per override, 512 MiB total overrides, 64 MiB per indexed
download, 256 MiB total indexed downloads, 10,000 MRPack members, and a 30-second
request timeout. `--timeout-seconds` may change only the positive request timeout.

## Receipt interpretation

Auditor `1.1.0` emits install-audit receipt schema `2`, whose added candidate-binding
record is required for a current PASS.

A pass receipt binds:

- the independent binding authority and canonical SHA-256, expected/measured candidate
  tree, exact expected filenames, and measured per-file byte/SHA-256 inventory;
- candidate manifest, offline receipt, and MRPack SHA-256;
- candidate/version/map/mission/runtime identities;
- UTC audit time and network policy;
- source and final HTTPS URL, HTTP status, byte count, SHA-1, and SHA-512 for all nine
  downloads;
- exact profile file count/bytes/SHA-256 inventory;
- every JAR and its unique mod IDs;
- whether the profile was retained or securely discarded.

Both pass and fail receipts explicitly record `launched: false` and
`live_qualified: false`. A pass proves fresh-profile materialization and dependency
availability at the recorded instant. It does not prove launcher import, client startup,
Microsoft/Mojang authentication, rendering, controls, FPS, pacing, combat balance, or
mission completion.

## Measured RC5 runs

On 2026-08-08 at `23:43:27Z`, auditor `1.1.0` completed a new disposable run against
the immutable 11-file RC5 root with no launch:

- candidate/tree: `operation-ashen-span-mp25-rc5` /
  `1E284A0555A421E5084B2A9425B70AA573C2ADBB382E0CCA8A6A3E8B012B38DB`;
- binding authority/hash: `auditor-builtin-immutable-rc5` /
  `AB2784566E095E2C29C115438B81A388CC45898731E95F06900EF83F975D1653`;
- hosted dependencies: 9/9 returned HTTP 200 and matched exact size, SHA-1, and SHA-512;
- materialized profile: 34 files, 12 JARs, zero duplicate mod IDs;
- profile mode: `temporary-discarded`; `launched: false`;
- receipt: `build/ashen-span-install-audit-rc5-20260808-bound-final.json`;
- receipt SHA-256: `800C38338A9536C4FD9539CDF00586324BD6EA35091AAB4C1989F593224DE4FC`.

This is current-schema install/materialization evidence only. It does not remove the
safety-envelope blocker or make RC5 playable/qualified.

The predecessor auditor (`1.0.0`) also ran at `21:43:10Z`:

- candidate: `operation-ashen-span-mp25-rc5`;
- MRPack SHA-256: `bcdd30312061b608b1d5087573a4f51a50e1d898196ff0578f5cb2b5fd3d815b`;
- hosted dependencies: 9/9 returned HTTP 200 and matched exact size, SHA-1, and SHA-512;
- materialized profile: 34 files, 12 JARs, zero duplicate mod IDs;
- profile mode: `temporary-discarded`;
- receipt: `build/ashen-span-install-audit-rc5-20260808-final.json`;
- receipt SHA-256: `9BBC325E3357023C35BC3FB86E1B333EEE493DF440C7A51BD4A655E6D3300C6E`.

That earlier immutable receipt remains measured dependency-availability history, but it
predates the external candidate-tree binding added in auditor `1.1.0`; it is not a
current trust-boundary PASS.

Hosted availability is time-sensitive. Repeat the audit for a later candidate or later
qualification session and keep each receipt under a new name.

## Focused regression suite

```powershell
python -m unittest tools.tests.test_ashen_span_install -v
```

The suite uses injected in-memory HTTP responses only. It makes no real network calls
and covers success, canonical receipts, exclusive outputs, cleanup, candidate drift,
identity mismatch, unsafe and colliding paths, symlinks, HTTPS/host/redirect policy,
content encoding, indexed bounds, exact size, SHA-1, SHA-512, missing metadata, duplicate
mod IDs, the exact nine-dependency contract, missing/wrong external bindings, arbitrary
source commits, forged three-file candidates, immutable RC5 impersonation, and
candidate parse/tree races.
Candidate-overlapping receipt/profile outputs are also rejected before any write or
network request.
