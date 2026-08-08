# Operation Ashen Span — unattended release-hardening plan

> **BLOCKED — no releasable RC6 exists. Do not build, launch, qualify, or publish
> RC5 or the proposed RC6.** A normal player at the locked 6/6 distance creates
> FULL chunks outside the locked 680-chunk safety envelope. This discovery
> supersedes the earlier implementation-clear verdict below. Work may resume only
> after an authoritative spec erratum and successful exact-pad one-build and
> six-build headless acceptance against a newly regenerated candidate. See
> [the safety-envelope blocker](docs/ashen-span-hardening/SAFETY_ENVELOPE_BLOCKER.md).

Date: 2026-08-08

Branch: `feat/ashen-span-release-hardening`

Immutable gameplay baseline: `d96b7b84688e925f311849d7c40f72a4f8a691c2`
Authority: `C:\Users\clayg\OneDrive\Desktop\mech-arena\OPERATION_ASHEN_SPAN_SPEC.md`

## Decision and review posture

The authoritative specification was reread in full before this plan. The existing
source, candidate, receipts, map/world builders, tests, server smoke, and sealed mp.24
anchors were then inspected directly. The normal interactive question loops in
`plan-ceo-review` and `plan-eng-review` cannot be used while the operator is away and
has explicitly forbidden popup-dependent work. Their unattended fallback is used:
locked requirements are auto-decided from the spec, three independent read-only agents
reviewed product scope, engineering architecture, and candidate integrity, and no new
gameplay premise is silently accepted.

Review mode was initially **HOLD_SCOPE**. RC5 contains the authored mission
implementation, but later real-player-ticket evidence invalidated its bounded-world
qualification. This phase remains scope-locked and may preserve diagnostic and release-
hardening work, but candidate production is blocked pending the authoritative erratum.

Three approaches were evaluated:

1. **Minimal recheck only.** Initially lowest risk because RC5 had passed hash,
   deterministic builder, independent verifier, GameTest, and no-player packaged-server
   contract checks; later player-ticket evidence proved those checks incomplete.
2. **Release hardening (selected).** Preserve RC5; add an unshipped actual-world
   acceptance probe, fresh-profile materializer, hash-bound validation kit, complete
   player/operator docs, and license/dependency evidence. Create RC6 because package
   bytes change and the actual-world probe may justify a narrowly scoped runtime fix.
3. **Broader game architecture.** Rejected because campaign shells, generic missions,
   new UI, progression, or new content contradict the locked vertical-slice decision
   and would add unplayed risk.

## What already exists

- Clean source at `d96b7b8`, pushed byte-for-byte to `fork/feat/ashen-span`.
- Immutable mp.25/RC5 candidate with exactly eleven contracted files and matching
  recorded SHA-256 values.
- Deterministic 22×14 authored asset source and 680-full-chunk bounded world, under
  the 32 MiB compressed budget.
- Full authored mission FSM, gates, shutters, sockets, fixed six-role/15-normal roster,
  dedicated Gatekeeper R-01 PMVC01 controller, service stop, and PMB04 finale.
- Six Garage Fleet builds, exact-zero ownership/descendant cleanup, crash-safe full
  `BlockState` gate ledger, retry/restore, and preserved DUEL/ROYALE hardening.
- 87 common JUnit tests, six Forge GameTests, 36 packaging/world tests, eight asset
  tests, full multi-loader build, and a real packaged-server boot/validate/stop smoke.
- Independent packager reconstruction, physical Anvil verification, runbook, rollback,
  notices, manifest, receipt, and explicit live-validation boundary.

RC5 remains an immutable historical candidate, not a finished or offline-qualified game
slice. Its no-player server smoke did not exercise normal player-distance tickets. The
proposed RC6 collision JAR fixes a separate startup defect but does not resolve the safety-
envelope contradiction and is not a releasable runtime.

## Measured gaps and chosen closures

| Priority | Gap | Closure in this branch |
|---|---|---|
| P1 | No real command-path mission run on the frozen candidate world | Unshipped Forge qualification probe plus disposable actual-overlay runner for builds 1–6 |
| P1 | Live checklist is not hash-bound or auditable | Candidate-bound session schema, per-build ledgers, attachment hashes, and fail-closed finalizer |
| P1 | Runbook is verifier-oriented, not player/operator-oriented | Player quickstart, dedicated-server install/rollback/troubleshooting, known limits, release-page draft |
| P1 | Server overlay does not carry a complete explicit license bundle | Canonical dependency matrix and pinned license files in RC6 client/server payloads |
| P2 | MRPack index structure is checked but hosted dependencies are not materialized | No-launch clean-profile downloader verifies HTTPS, size, SHA-1, SHA-512, archive paths, and duplicate mod IDs |
| P2 | Two valid world-tree hashes look contradictory | Name raw curated-tree and package-rooted tree algorithms explicitly in docs/receipts |
| P2 | Public platform permissions changed after RC5 | Dated, source-linked publication-readiness checklist; no upload or legal claim |

## System boundaries

```text
                         immutable inputs
  mp.24 anchors ------+  RC5 11 files  +------ d96b7b8 source
                      |                |
                      v                v
              identity / hash baseline
                      |
          +-----------+-------------+
          |                         |
          v                         v
 fresh-profile materializer   disposable server overlay
 HTTPS -> size/hash ->         + unshipped qualification probe
 safe extract -> mod IDs       -> six real command-path runs
          |                         |
          +-----------+-------------+
                      v
           canonical evidence receipts
                      |
            +---------+----------+
            |                    |
            v                    v
   BLOCKED RC6 work        disabled validation kit
   no candidate emitted    no PASS until erratum
```

The qualification probe is not a shipped gameplay feature:

```text
normal runtime                    qualification runtime
---------------                   ---------------------
Pomkots mp.25 JAR                 exact Pomkots mp.25 JAR
no probe JAR                      + ashen_span_qualification JAR
no qualification property        -DashenSpan.qualification=true
player controls mission           Forge FakePlayer drives real command path
                                  probe writes JSON, server stops cleanly
```

The production MRPack and server overlay must never contain the probe JAR, probe mod ID,
qualification property, synthetic player data, or generated evidence files.

## Actual-world acceptance architecture

The Forge module gains a separate `qualification` source set and remapped probe JAR.
It compiles against production outputs but is excluded from `remapJar`, normal runtime
classpath, MRPack, and server overlay. The external runner:

1. Verifies RC5/RC6 candidate identity before any extraction.
2. Creates a new exclusive scratch directory; existing paths are never overwritten.
3. Copies the known Forge 47.3.3 runtime and extracts the exact server overlay safely.
4. Records the initial four-region Anvil inventory and package-rooted world digest.
5. Adds only the qualification probe and enables its explicit JVM property.
6. Boots with Java 17 and `nogui`; no launcher, client, browser, login, or popup exists.
7. On the authenticated Sector 01 world, the probe creates a Forge `FakePlayer` and
   invokes the same Brigadier `/arena solo start <1-6> <seed>` route as a player.
8. For each build it advances authored trigger positions, waits for staged roots to
   reveal, deterministically retires admitted hostiles and descendants, and asserts
   gate/shutter state, service restoration, Gatekeeper/PMB04 identity, terminal outcome,
   retry behavior, player restoration, and exact-zero mission tags.
9. Each build starts from restored gate/world state. Any timeout or unexpected entity,
   phase order, result, or residue fails the probe.
10. The probe emits canonical JSON and requests clean stop. The runner verifies exit 0,
    log markers, unchanged 680-full-chunk inventory/bounds, and hashes all evidence.

The probe accelerates combat by retiring admitted enemies; it does not measure balance,
damage tuning, tactics feel, or 10–13 minute pacing. Those remain human-only.

## Fresh-profile materialization architecture

```text
MRPack
  |
  +-- modrinth.index.json -- validate schema/path/HTTPS/env/size/hashes
  |                               |
  |                               v
  |                      stream each hosted file
  |                      SHA-1 + SHA-512 + bytes
  |
  +-- overrides ---------- safe normalized extraction
                                  |
                                  v
                         clean materialized profile
                         1 save / expected mods / no collisions
                                  |
                                  v
                         canonical audit receipt
```

All writes use a new exclusive output or a private temporary directory. Downloads are
bounded by indexed sizes plus a small fixed allowance, redirects must remain HTTPS,
archive traversal/case collisions/reparse points are rejected, partial files are never
published, and content is verified before rename. The default audit deletes its private
materialization only after the receipt is written; `--keep-profile` requires an explicit
new destination.

Online availability is time-sensitive evidence, not a package guarantee. The receipt
records UTC time, final URL, HTTP status, bytes, SHA-1, SHA-512, and exact MRPack hash.

## Qualification evidence model

```text
SESSION (candidate hashes + environment)
  |
  +-- build-01.json ... build-06.json
  |      machine fields: start/end, outcome, phase/boss timings, log hash
  |      human fields: controls, readability, audio, balance, issues
  |
  +-- recovery.json (retry, disconnect, restart/restore)
  +-- performance.json (hardware, JVM, resolution, FPS/frame pacing evidence)
  +-- soak.json (duration, before/after chunk inventory and world bytes)
  +-- attachments/ (screenshots, logs, captures; every file hashed)
  |
  +-- FINAL_RECEIPT.json
         PASS only after post-erratum headless acceptance and all machine/human gates
```

Initialization binds a session to the candidate's manifest and four primary hashes and
refuses existing output. Finalization is read-only over inputs except for an exclusive
receipt write. `null`, missing, contradictory, duplicate-build, unrecognized attachment,
or changed candidate data fails closed. A pending kit is valid evidence of *pending*,
never evidence of pass.

## Probe-discovered intermediate runtime fix

RC5 remains byte-for-byte untouched. The actual-world probe first discovered that the frozen
world spawns a fresh player exactly at `PLAYER_PAD`, while solo startup's broad entity
collision call rejects that same player before its following filtered obstruction check
can exclude the caller. Commit `5a35ec3` therefore contains an intermediate mp.25 JAR with one
scope-locked fix: test blocks separately and retain the explicit non-player entity
obstruction check. The map, world, Lost Cities 7.4.13, mission, roster, tactics, six
builds, balance values, and all DUEL/ROYALE behavior remain unchanged.

That fix is necessary but not sufficient. Subsequent headless evidence proved that normal
6/6 player tickets create 17 forbidden FULL chunks outside the locked envelope at mission
start, with route-wide geometry requiring a larger envelope. The intermediate JAR must not
be labeled RC6, packaged as a candidate, launched, or supplied to live validation.

The isolated runtime source commit is
`5a35ec3d9a69fdd4d88ed7a0b21b28bc1b18ecfb`. A clean detached worktree produced
the 10,704,165-byte runtime JAR with SHA-256
`0263191D695C2CBB136B883CC62DAF1354C1B2013FFCCEDDDE54CE9DD63600F4`;
qualification classes are absent from all 1,401 JAR members. That artifact predates
the repository's LF checkout policy and is retained only as historical diagnostic
provenance; it is not the canonical post-erratum runtime. A clean checkout of the future
authorized source must produce and pin a replacement hash.

The planned post-erratum RC6 packaging work would also change these distribution-facing
bytes:

- expanded player and server runbook;
- explicit raw curated-tree versus package-rooted tree hash labels;
- dependency/license matrix;
- pinned Architectury and Cloth Config LGPL-3.0 text;
- pinned GeckoLib, Lost Cities, and Pomkots/asset MIT notices already available from
  authoritative package inputs;
- dated public-platform permission checklist and honest live-only status.

The in-progress builder and independent verifier pin the intermediate runtime source/hash,
but candidate creation and verification must fail closed while the spec conflict remains.
After an erratum, both tools, the world/asset contracts, exact license paths/bytes/hashes,
and all candidate identities must be regenerated and reviewed together. No RC6 output
directory may be created from the current 680/6/6 inputs.

No document declares the original authored content's copyright owner beyond evidence
already present in the repository. Public upload remains blocked until the operator
supplies the platform's required ownership/permission declarations.

## Failure and rescue registry

| Failure | Detection | Required rescue behavior |
|---|---|---|
| Baseline hash drift | immutable baseline preflight | stop before writes; identify exact file |
| RC5 destination mutation attempt | path/identity guard | refuse; RC5 is input-only |
| Hosted URL unavailable or redirected off HTTPS | materializer network policy | fail receipt; retain no partial profile |
| Download size/hash mismatch | streaming digest | discard temporary file; name dependency |
| Unsafe/case-colliding MRPack path | normalization pass | fail before extraction |
| Duplicate/missing mod ID | JAR metadata inventory | fail profile audit with both paths |
| Probe appears in production archive | package verifier forbidden-member check | reject candidate |
| Probe starts on wrong map or without property | map/property gate | write failure evidence; make zero mission mutation |
| Real solo command returns 0 | per-build probe state | fail that build; clean/stop; retain evidence |
| Phase/reveal/gate timeout | bounded tick deadline | fail with phase, tags, coordinates, gate state |
| Service template differs | exact Garage Fleet comparison | fail build; do not continue to boss |
| Cleanup leaves mission entity or gate ledger | post-run exact-zero scan | fail run and final receipt |
| Server hangs or crashes | external process deadline | terminate only scratch process; hash logs/crash reports |
| World gains/losses chunks | before/after Anvil inventory | fail; preserve scratch evidence |
| Human validation field absent | kit finalizer schema | status remains pending |
| Candidate changes after kit init | final hash recheck | refuse finalization; create a new session |
| RC6 build/verify disagreement | independent reconstruction | no candidate publication |
| Public permission proof absent | publication checklist | do not upload, release, or claim readiness |

## Test coverage map

```text
unit tests
  materializer: URL/path/hash/size/redirect/collision/duplicate IDs/receipt
  evidence kit: exclusivity/schema/all-six/attachments/hash drift/pending vs pass
  packaging: future RC6 IDs/licenses/docs/probe exclusion/determinism/mutations

Forge qualification
  real world -> real command -> all six builds -> triggers -> gates -> service
             -> Gatekeeper -> PMB04 -> retry -> cleanup -> restoration

external integration (current result: BLOCKED)
  actual server overlay -> normal player ticket -> 17 forbidden FULL chunks -> FAIL
  future erratum -> regenerated world -> one-build/six-build acceptance required

regression
  player at PLAYER_PAD starts/mounts; real non-player blocker still rejects
  common/fabric/forge build + 88 JUnit + seven GameTests
  asset 8 + packaging/world 36+ + independent verifier + sealed hashes
```

Every new Python failure path gets a deterministic unit test with no real network. One
real indexed dependency is downloaded and verified before the nine-file availability
batch; the final online receipt is recorded separately because availability can change.

The strongest acceptance runner is attempted. If Forge remapping or FakePlayer behavior
cannot be made reliable without changing production behavior, the exact tool failure is
recorded and the fallback is a packaged-server contract smoke plus model/GameTest gates;
the missing end-to-end claim remains explicitly open rather than being simulated on paper.

## Security, privacy, and performance

- No credentials, launcher profiles, browser cookies, Microsoft/Mojang login, RCON, or
  production server are used.
- Qualification runs only on exclusive scratch copies under the repository build tree.
- The probe records no player name, UUID, IP address, or chat content; its FakePlayer
  identity is fixed and synthetic.
- Network access is limited to the nine indexed HTTPS files and read-only Modrinth
  metadata. No upload occurs.
- Download and archive expansion are size-bounded; canonical receipts contain hashes,
  not third-party binary copies.
- The probe has per-state and whole-run tick deadlines and cannot become an endless
  headless server.
- Qualification-harness runtime cost is exactly zero because the probe is not shipped;
  the production collision fix replaces one startup-only collision predicate.

## Deployment and rollback

```text
d96b7b8 / RC5 (immutable)
        |
        +-- hardening branch commits (tools, probe, docs, RC6 packager)
        |         |
        |         +-- tests fail -> fix branch only; RC5 remains byte-identical history
        |         |
        |         +-- tests pass -> still BLOCKED pending spec erratum
        |
        +-- rollback = abandon hardening branch; do not launch blocked RC5
```

Push only `feat/ashen-span-release-hardening` to `fork`. Do not alter
`feat/ashen-span`, mp.24, RC5, aliases, releases, origin/upstream branches, or PRs.

## Implementation workstreams

| Lane | Work | Dependencies | Parallel? |
|---|---|---|---|
| A | Fresh-profile materializer, dependency audit, unit tests | immutable baseline | yes |
| B | Qualification source set/probe, server runner, actual-world evidence | immutable baseline, Forge runtime | yes |
| C | Evidence-kit schema/CLI/tests and player/public docs | immutable baseline | yes |
| D | RC6 packager/verifier license/doc changes | A/C contracts locked | after A/C |
| E | Full gates, review, investigation, candidate build/smoke/verify | A–D | sequential |

Concrete files are expected under `tools/`, `tools/tests/`,
`forge/src/qualification/`, `docs/ashen-span-hardening/`, and the existing RC6-modified
packager/verifier/test files. Production Java under `common/src/main` and
`forge/src/main` is frozen unless the actual-world probe demonstrates a defect.

## Acceptance criteria

- RC5 and all three sealed mp.24 anchors retain exact hashes.
- Fresh-profile audit materializes all nine indexed dependencies with exact size,
  SHA-1, and SHA-512 and produces a deterministic receipt.
- All six real solo start commands pass on a disposable copy of the candidate world,
  or a genuine reproducible tooling blocker is documented without a false claim.
- Probe is absent from every shipped archive and normal classpath.
- Live-validation initialization/finalization remains disabled until it binds a successful
  post-erratum headless acceptance receipt.
- Player/server quickstart, rollback, troubleshooting, release-page draft, dependency
  matrix, and public-permission checklist exist.
- Multi-loader build, common tests, existing and new Forge checks, Python suites,
  asset/world tests, diff check, review, and investigation pass; package builder,
  verifier, and qualification fail closed while the blocker remains.
- No RC6 candidate may be emitted until an authoritative erratum is implemented and the
  exact-pad one-build and six-build headless acceptance runs prove the revised contract.
- Hardening branch is intentionally committed and pushed only to `fork`.

## NOT in scope

- New levels, campaign shell, story expansion, generic waves, random bosses, endless
  survival, crafting, progression, loot, or a general AI/navigation framework.
- Gameplay/balance changes, roster changes, map changes, new HUD/UI, new art/audio,
  controller remapping, or performance claims without live evidence.
- Shipping the qualification probe or synthetic evidence.
- Interactive Minecraft, Modrinth/Prism launch, login, screenshots, live client testing,
  or asking the absent operator to press a popup.
- Public upload, Modrinth project creation, release, alias change, upstream PR, or legal
  representation beyond recorded license/permission evidence.
- Calling an MRPack a standalone executable or the server overlay a complete Forge
  installation.

## GSTACK REVIEW REPORT

| Review | Trigger | Why | Runs | Status | Findings |
|--------|---------|-----|------|--------|----------|
| CEO Review | `/plan-ceo-review` | Scope & strategy | 2 | CLEAR (unattended fallback) | HOLD scope; choose release hardening over recheck-only or broader architecture |
| Codex Review | independent subagents | Independent second opinions | 3 | CLEAR WITH LOCKED MITIGATIONS | RC5 integrity clear; actual-world, evidence, runbook, dependency, and license gaps locked into plan |
| Eng Review | `/plan-eng-review` | Architecture & tests (required) | 2 | CLEAR (unattended fallback) | Property-gated unshipped probe, clean-profile materialization, exact-zero/world-growth gates |
| Design Review | `/plan-design-review` | UI/UX gaps | 0 | SKIPPED | No UI or visual changes; live visual quality remains human-only |
| DX Review | `/plan-devex-review` | Developer experience gaps | 0 | SKIPPED | Player/operator documentation is covered directly; no developer API is added |

- **UNRESOLVED:** 1 authoritative product decision: amend the safety envelope, real
  effective distances, or authored route. Live sensory gates and public ownership
  declarations remain separate later inputs.
- **SUPERSEDING VERDICT:** **BLOCKED.** Earlier CEO/ENG implementation clearance was
  invalidated by pinned-source analysis and the measured 17-FULL-chunk breach. No launch,
  qualification PASS, RC6 packaging, or publication is allowed before the erratum and new
  headless acceptance evidence.
