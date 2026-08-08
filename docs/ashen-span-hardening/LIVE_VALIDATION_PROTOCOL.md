# Operation Ashen Span live-validation protocol

> **BLOCKED — do not initialize, run, finalize, or treat this protocol as capable
> of certifying RC5 or the proposed RC6.** The locked 6/6 player-ticket footprint
> exceeds the locked 680-chunk safety envelope. Live validation cannot cure an
> offline contract failure. Resume only after an authoritative spec erratum, a
> regenerated candidate, and successful exact-pad one-build and six-build
> headless acceptance. See
> [the safety-envelope blocker](SAFETY_ENVELOPE_BLOCKER.md).

Status: suspended future protocol. No current candidate is eligible for live validation,
and no live run, performance result, balance result, screenshot, or compatibility claim
has been recorded.

Operation Ashen Span is one authored Minecraft Java 1.20.1 Forge mission. The
expected player run is 10–13 minutes. It is not a campaign or standalone game. This
protocol is designed to qualify a future exact candidate without changing it; it cannot
qualify RC5 or the intermediate collision-fix artifact.

## 1. Create an immutable session

Disabled while blocked. The command below documents the future interface only; running it
for RC5 or the intermediate collision-fix artifact must not produce a certifiable session.

Use a fresh validation directory for every attempt. Never initialize inside the
candidate or reuse a prior kit. First run the independent candidate verifier described
in `docs/ashen-span-candidate/README.md` and retain its printed candidate-tree SHA-256
outside both the candidate and validation directory.

```powershell
python .\tools\ashen_span_qualification.py init `
  --candidate-dir <exact-candidate-directory> `
  --candidate-tree-sha256 <hash-printed-by-independent-verifier> `
  --output-dir <new-validation-session-directory>
```

Initialization verifies the externally recorded tree anchor, `MANIFEST.json`,
`OFFLINE_BUILD_RECEIPT.json`, every entry in `SHA256SUMS.txt`, the exact candidate-
specific filenames, the MRPack's internal identity, and every candidate file. It then
creates `SESSION.json`, six build records, recovery/performance/soak records, and an
empty `attachments/` directory. Existing output is rejected.

Fill only observations made against the bound candidate. Do not copy a result from a
different RC. Do not put account names, UUIDs, IP addresses, chat, or credentials in
the evidence kit.

## 2. Record the environment

Fill the five null values in `SESSION.json`:

- launcher and fresh-profile identity;
- operating system and Java version;
- display resolution;
- keyboard/mouse or controller control scheme.

Null is deliberately valid while work is pending. It is never a pass.

## 3. Complete the six Garage Fleet runs

Run the builds in order on fresh restored state:

1. Vanguard
2. Siege
3. Skirmisher
4. Artillery
5. Duelist
6. Trooper

For each `build-0N.json`, record UTC start/end, mission duration, exact phase order,
elapsed time at every phase, both boss-fight durations, victory, both named bosses,
the service stop, restored gates, and zero remaining mission entities/ledger entries.
Start/end time and reported duration must agree within one second. A pass requires a
duration from 600 through 780 seconds inclusive and this order:

```text
GARAGE, P1, P2, P3, P4, P5A, P5B, SERVICE, P6, VICTORY
```

The player must separately mark controls, visual readability, audio cues, and balance.
Those human fields cannot be derived from logs. Put each defect in `human.issues` and
set `verdict` to `fail`; never mark pass and describe an unresolved defect only in
notes. Attach at least one corresponding log or capture using a safe path such as
`attachments/build-01.log`, then list that exact path in `attachment_paths`.

## 4. Recovery, performance, and soak

`recovery.json` covers retry, disconnect/rejoin, full server restart/restore,
gate-ledger cleanup, and exact-zero mission cleanup. Use disposable copies when testing
failure paths.

`performance.json` records CPU, GPU, RAM, sample duration, average FPS, one-percent-low
FPS, and p95 frame time. Use at least five continuous minutes covering active combat.
The tool deliberately has no invented hardware-wide FPS threshold. The validator must
explicitly accept or reject FPS and frame pacing on the target machine and attach the
measurement export.

The legacy `soak.json` schema requires at least 60 minutes, four region files, 680 terrain
chunks before and after, zero chunks outside the safety envelope, zero mission entities,
zero gate ledger entries, and no unexpected world growth. That 680/6/6 combination is now
proven unsatisfiable and cannot be marked pass. After the erratum, regenerate the schema
and expected inventory from the new authoritative contract. Record total world bytes
before and after; they are evidence, not required to be identical because player/session
data can legitimately change. Attach the before/after inventory and server/client log.

## 5. Audit without writing

Disabled for current candidates. A read-only status command may describe historical data,
but it must never return or imply PASS for RC5 or the proposed RC6.

```powershell
python .\tools\ashen_span_qualification.py status `
  --candidate-dir <exact-candidate-directory> `
  --candidate-tree-sha256 <hash-printed-by-independent-verifier> `
  --kit-dir <validation-session-directory> `
  --unfinalized
```

Exit codes are `0` for pass, `2` for pending, `3` for an explicit fail, and `1` for
invalid evidence or candidate drift. `--unfinalized` is an explicit assertion that no
final receipt has ever existed; after finalization, use the anchored command below.
Pending is an honest state, not a test failure to edit around.

## 6. Finalize once

Disabled for current candidates. Do not create `FINAL_RECEIPT.json` until the erratum,
new candidate, and required headless acceptance receipt are bound into the protocol.

```powershell
python .\tools\ashen_span_qualification.py finalize `
  --candidate-dir <exact-candidate-directory> `
  --candidate-tree-sha256 <hash-printed-by-independent-verifier> `
  --kit-dir <validation-session-directory>
```

Finalization re-hashes the candidate and all referenced attachments. It rejects
missing/extra records, duplicate keys, unsafe paths, unreferenced attachments,
contradictory pass claims, or any candidate drift. It writes `FINAL_RECEIPT.json`
exclusively, prints that receipt's SHA-256, and returns the same status codes as
`status`. Record the printed hash outside the validation-session directory; the
receipt deliberately contains no self-asserted finalization time.

After finalization, every later `status` call must include the independently recorded
anchor:

```powershell
python .\tools\ashen_span_qualification.py status `
  --candidate-dir <exact-candidate-directory> `
  --candidate-tree-sha256 <hash-printed-by-independent-verifier> `
  --kit-dir <validation-session-directory> `
  --receipt-sha256 <hash-printed-by-finalize>
```

The tool validates that anchor and the receipt's exact canonical schema against the
current candidate, records, attachment hashes, verdicts, and evidence-tree digest.
Any post-finalization edit makes the kit invalid instead of silently issuing a fresh
verdict that disagrees with the externally anchored receipt.

A finalized pending or failed kit is an immutable snapshot. Do not overwrite its
receipt; initialize a new session for a later attempt. Only a future receipt whose status
is `pass`, and which is also bound to successful post-erratum headless acceptance, proves
that all required machine and human fields were explicit. It does not
turn this one mission into a campaign, certify unrelated hardware, or grant publication
permission.

## World hash vocabulary

Two hashes describe different path domains and must not be substituted:

- Raw curated-tree SHA-256
  `537AF7F1AB05B5070CED69697CEA04330E89177C9E7B810DA60BAE24A88A1223`
  hashes the ten frozen world files under the builder archive root
  `cold_ruin_sector_01/`.
- Package-rooted tree SHA-256
  `976E4F5B3BC4C4E2F9B552D00AFA6277BDD1D0436BF56A8A476C6F53D8E190EB`
  hashes the same world payload after deterministic rebasing to
  `saves/cold_ruin_sector_01/` for client/server packages.

The bytes are not contradictory; the canonical relative paths are part of each tree
hash algorithm.
