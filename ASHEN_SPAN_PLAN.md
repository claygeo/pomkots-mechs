# Operation Ashen Span — Offline mp.25/RC5 Engineering Plan

> **SUPERSEDED RELEASE VERDICT — BLOCKED.** The historical implementation and
> no-player smoke evidence below remain measured facts, but they do not qualify RC5.
> Later pinned-source analysis and headless same-chunk reproduction proved that genuine
> 6/6 player tickets create 17 forbidden FULL chunks outside the locked 680-chunk safety
> envelope. Do not launch, qualify, package RC6, or publish until an authoritative spec
> erratum is implemented and a regenerated candidate passes exact-pad one-build and
> six-build headless acceptance. See
> [the safety-envelope blocker](docs/ashen-span-hardening/SAFETY_ENVELOPE_BLOCKER.md).

Date: 2026-07-16
Branch: `feat/ashen-span`
Baseline: `fec9e9dfba10ecb17b2734a842339d0b8908b2c5`
Authority: `../OPERATION_ASHEN_SPAN_SPEC.md`

## Review posture

The full authoritative specification was read before planning. The required
`clayton-workflow-gate` and `plan-eng-review` instructions were also read in full.
The normal interactive `plan-eng-review` question loop is unavailable in this Codex
Default-mode session, and the operator explicitly authorized an autonomous fallback.
This plan therefore records the decisions directly, applies an adversarial architecture
review, and incorporates a separate read-only subagent review of the actual code.

The requested scope is locked. It is intentionally larger than the normal eight-file
review threshold because map source, mission runtime, entity control, tests, packages,
and evidence are all part of one offline acceptance boundary. No scope reduction is
accepted; implementation is split into independently verifiable layers instead.

Baseline `gradlew build` passed across common, Fabric, and Forge before implementation.
The pre-existing dirty changes in `ArenaManager.java`, `ArenaManagerTest.java`, and
`ARENA_ASSET_AUDIT.md` are operator-owned and must be preserved.

## Architecture decision

Keep DUEL, ROYALE, their restore paths, and the generic random `SpawnDirector` intact.
Only SOLO routes through a new authored `AshenSpanDirector`. Mission start has a strict
validation boundary: the map contract is validated before match claim, journaling,
teleport, gate mutation, entity creation, or any player-state change.

```text
/arena solo start 1..6
        |
        v
AshenSpanContract.validate (read-only, fail closed)
  dimension / map metadata / origin / markers / chunks / bounds / 6x6 / config
        |
        +-- invalid --> one actionable error; zero mutation
        |
        v
ArenaManager claims match + journals restore
        |
        v
AshenSpanDirector
  fixed phase table -> stage roots -> objective -> reveal -> activate
        |                                      |
        v                                      v
MissionGateLedger                       ArenaOwnershipRegistry
full BlockState restore                 roots before ADD; descendants/caps
        |                                      |
        +------------------- cleanup <---------+
                         exact zero
```

The authored phase state machine is explicit and table-driven, but not a general wave
framework:

```text
GARAGE -> P1 -> P2 -> P3 -> P4 -> P5A -> P5B -> SERVICE -> P6 -> VICTORY
   |       |     |     |     |      |       |        |       |
   +-------+-----+-----+-----+------+-------+--------+-------+-> DEFEAT

Advance requires: prior hostile roots == 0 AND hostile descendants == 0,
then the authored trigger volume. Double KO evaluates player/mech loss first.
```

## Existing behavior reused

- `ArenaManager` lifecycle hooks, restore journal, idempotent cleanup, ADD guard, quit,
  boot cleanup, retry, and player/mech defeat ordering.
- `GarageFleet` as the sole six-build template authority.
- `SpawnDirector` placement hardening and relocate-once/replace-once/retire policy,
  extracted or exposed without changing its random SOLO behavior for other modes.
- Existing PMVC renderer, attributes, inventory schema, weapon action code, lock targets,
  and machine/alert cues.
- PMB04 behavior and inactive boot sequence; only ownership, naming, authored placement,
  and mission activation are added.
- mp.24 deterministic archive mechanics as a design reference only. Sealed files and
  aliases are never edited.

## Runtime components

### `AshenSpanContract`

- Fixed IDs: `cold_ruin_sector_01`, `operation_ashen_span`, asset version, origin `(0,0)`.
- Validate Overworld dimension, physical metadata marker, complete marker set, exact
  content and containment rectangles, required pre-generated chunk availability without
  force-loading, and effective view/simulation radius at most six.
- Validate `mobGriefing=false` and Pomkots entity/player vehicle block destruction off.
- Return a structured result suitable for deterministic unit tests and one actionable
  command error. No writes occur in this component.

### `AshenSpanDefinition`

- Immutable fixed phase roster, objective, trigger, gates, sockets, recovery anchors,
  staging/reveal rule, and exact expected counts.
- Normal roster totals exactly 15; R-01 and PMB04 are exclusive phases.
- Content, playable, and safety-envelope constants are single-source and exposed to the
  package verifier through a canonical contract JSON generated from the same values.

### `AshenSpanDirector`

- Owns the phase FSM, fixed origin, rectangular containment, objective cards, staging,
  activation, one-time service, boss outcome, summary, and recovery budgets.
- Pre-register every root before `addFreshEntity`.
- Normal units remain invulnerable, targetless, and no-AI while staged behind authored
  shutters. R-01 and PMB04 receive only the narrow activation camera exemption.
- Maximum active normals four, projectiles 48, effects five.
- Never force-load chunks, choose radial sockets, add duel enemies, or mutate terrain.
- Clamp/return the full mech AABB inside `x=-168..167`, `z=-56..55`; never use a square
  world border as the containment implementation.

### `ArenaOwnershipRegistry`

- Per-match root records contain phase, roster slot, lineage, and replacement budget.
- Descendants carry root UUID and kind: projectile, effect, or boss hitbox.
- Ownership propagates from vanilla projectile owner, Pomkots shooter, boss-hitbox
  parent, explicit ownerless effect callback, and recovery replacement lineage.
- Root registration precedes ADD. Every descendant is tagged for stale ADD rejection,
  cap accounting, boot cleanup, and exact-zero phase gating.
- A descendant never counts as a hostile root. Rival/boss phase completion waits for
  their hostile projectiles/effects to reach exact zero.

### Mission gate ledger

- Separate from ROYALE cage storage.
- Before first mutation, persist dimension, position, and the complete serialized
  original `BlockState`, including properties.
- Idempotently restore on retry, stop, defeat, victory cleanup, server boot, and failed
  start after ledger creation.

### Gatekeeper R-01

- Register `arena_rival_pmvc01` as a distinct entity type extending PMVC01, with the
  existing renderer and PMVC attributes.
- Protected base hooks keep ordinary mechs passenger-controlled. The rival overrides
  control source, no-passenger motion/animation, targeting, and release handling.
- Synchronized autonomous active/tactic state exists on both logical sides.
- Loadout is exact: Muknvali head/body/arms, Aldebaran legs, Shiga/Narita, Shakuji L2
  with two magazines, Shinobazu L1 with two, Suwa L1 with one, empty other slots,
  24 pellets. Set 400 max HP only after the PMVC parts recalculation and fill to 400.
- No mount, inventory UI, capture, persistence as loot, or death drops.
- Deterministic distance-band tactics use only direct movement/input hooks and hard
  lock. Target validity requires active player mech, life, dimension, <=150 blocks, and
  line of sight. Forward/ground footprint probes reject gaps. Never jump, hover,
  navigate generally, or mutate blocks.
- One weapon can be active. Every tactic transition, relocate, deactivate, death, and
  cleanup emits an explicit all-zero release and clears locks/projectiles/state.
- Suwa receives a safe no-player animation activation hook; no client packet spoofing.

### Garage service

- Add a template restore API keyed by the selected zero-based Garage Fleet build.
- Reset health, exact template weapon/ammo inventory, weapon manager rounds/reload
  state, fuel inventory and partial fuel state, then synchronize once.
- Execute once per run only after R-01 and all owned descendants are zero.
- Do not restore arbitrary pre-run inventory or duplicate user state.

### PMB04 finale

- Pre-place PMB04 inactive at `(144,73,0)`, name it `SPAN WARDEN`, synchronize its boss
  bar, and activate only after service and player `x>=88`.
- Register the boss root before ADD. Propagate ownership to first-tick hitbox,
  Earthraise, grenades, missiles, explosions, and replacements.
- Victory requires boss death while both player and mech live. Double KO is defeat.
  Exhausted boss recovery is defeat.

## Map and asset pipeline

`sector01-src/` is a retained resource-only low-code Forge mod. Its deterministic
builder emits `mecharena_sector01-1.0.0.jar` with custom Lost Cities palette, parts,
buildings, city style, world style, and one predefined city.

The district uses one zero-floor, tall authored part per content chunk. This is supported
by Lost Cities 7.4.13: a predefined building forces city status and the generator passes
the part's full slice count to `generatePart`. Random city chance remains zero. Each of
the 308 content chunks is therefore exact, while safety-only chunks remain inert.

- Content chunks: `x=-11..10`, `z=-7..6` (308).
- Safety chunks: `x=-17..16`, `z=-10..9` (680).
- World source uses a fixed seed/profile, no stock loot/spawners/explosions/foliage,
  no fluids/random highways/rails, fixed time/weather, and explicit map markers.
- First generate and validate one representative chunk; only then generate the full
  district.
- Generate in a new isolated headless Forge runtime. Never read from or write to the
  active Mosslorn server world.
- Pre-generate exactly the safety rectangle, save cleanly, trim region/entities/poi,
  remove volatile runtime files, and archive canonically. No populated chunk may fall
  outside the 680-chunk set. Compressed world must be <=33,554,432 bytes.

## Package architecture

Create only `modpack/candidates/ashen-span-mp25-rc5/`. The candidate contains a
deterministic MRPack, matched server overlay, Pomkots mp.25 JAR, asset JAR, world ZIP,
manifest, SHA256SUMS, notices, offline receipt, runbook, rollback, and live-validation
boundary.

- Embed exact `lostcities-1.20-7.4.13.jar` on client and server; SHA-256
  `DA5AE1B0C0D0C8066F2971C9ADB57D18657C4844AAD6CBA4F9F07F7946E30A95`.
- Include the Lost Cities MIT notice and asset provenance.
- Pin integrated render/simulation to 6/6 and server view/simulation to 6/6.
- The one allowed save subtree is `saves/cold_ruin_sector_01`; reject Mosslorn,
  downloaded-city names, extra saves, and extra populated chunks.
- Canonical JSON, sorted archive paths, fixed timestamps/modes, two-build byte equality,
  exclusive staging, and independent verifier reconstruction are mandatory.
- Package reconstruction from the frozen world tree is deterministic. Do not claim
  byte-identical world regeneration unless two independent generations prove it.
- Status is `offline-candidate-live-validation-pending`; balance, FPS, compatibility,
  screenshots, and soak remain unmeasured.

## Failure modes and handling

| Failure | Required behavior |
|---|---|
| Map marker/profile/version mismatch | Fail before journal or mutation with one error |
| Radius above six or missing chunk | Fail closed; never generate or force-load |
| Every authored socket invalid | Recovery anchor, relocate once, replace once, retire/fail |
| Staged normal tries to acquire target | Base monster staging hook clears target and freezes |
| Descendant ADD has no admitted lineage | Reject/discard; never infer from proximity |
| Projectile/effect cap reached | Reject descendant and preserve mission state |
| Rival loses target/ledge probe | Release all input/locks and hold safe position |
| Rival dies during continuous fire | Release, clear descendants, no drops |
| Service invoked twice | No-op with deterministic once-only flag |
| Gate restore interrupted | Durable full-state ledger remains for boot cleanup |
| Boss and player die same tick | Defeat because loss is evaluated first |
| World/package exceeds bounds/budget | Verifier fails; candidate is not emitted |
| Artifact staging already exists | No overwrite; fail with actionable cleanup path |

## Test coverage map

```text
COMMAND  ->  CONTRACT  ->  JOURNAL/START  ->  PHASE FSM  ->  CLEANUP
  |            |               |                |              |
1..6        IDs/origin       no mutation     order/roster    exact zero
retry       markers/chunks   dirty restore   gates/triggers  boot restore
            radius/config                    service/outcome

ROOT ADD -> OWNERSHIP -> DESCENDANTS -> CAPS -> PHASE CLEAR
   |           |         projectile    48       roots=0
pre-ADD      lineage       effect         5     descendants=0
normal/rival/boss          hitbox

RIVAL LOADOUT -> RECALC -> 400 HP -> TACTICS -> RELEASE/NO DROP
```

Required deterministic JUnit tests:

- Exact bounds, phases, triggers, gates, sockets, recoveries, objectives, fixed roster,
  15-normal total, and exclusive rival/boss phases.
- Map-contract validation order and zero-mutation failure results.
- Public build normalization 1–6, internal retry build/seed stability.
- Ownership pre-ADD ordering, every ancestry source, replacement lineage, caps 48/5,
  stale ADD rejection, and exact-zero semantics.
- Full gate `BlockState` serialization/round trip/idempotent restore.
- Staging activation and narrow reveal exemption.
- Rival registered type/state, exact parts/levels/ammo/fuel/HP, deterministic tactics,
  target rejection, ledge rejection, one-weapon rule, all release paths, no mount/drop.
- Service exact reset and once-only behavior for all six builds.
- PMB04 hitbox/projectile/effect ownership, boot trigger, victory, defeat, double KO,
  exhausted recovery, retry, cleanup, and summary fields.
- Regression tests preserving DUEL/ROYALE, restore ownership, ADD guard, and cleanup.

Strongest feasible headless integration:

- Add/configure Forge GameTest or a noninteractive Forge server harness.
- Load the bounded template and run all six build starts through contract validation,
  mount, authored phase triggers, gate transitions, lineage, rival/service, PMB04
  outcome, retry, gate restore, and exact-zero cleanup.
- If Loom cannot expose GameTest under the current Architectury version, record the
  precise tool limitation and run the same assertions through a scripted dedicated
  server command harness. Interactive Minecraft is prohibited.

Package tests independently verify asset schemas, exact geometry contract, chunk set,
world budget, embedded JAR identity, 6/6 settings, notices, canonical archives,
determinism, forbidden payloads, and sealed mp.24 hashes.

## Implementation sequence

1. Add pure mission definition/contract/ownership/gate-ledger models and unit tests.
2. Add PMVC control hooks, registered rival type/controller/loadout, service reset, and
   focused entity/tactics tests.
3. Add director staging/FSM/containment and integrate SOLO start/tick/ADD/cleanup while
   preserving DUEL/ROYALE.
4. Add PMB04 descendant hooks and headless integration coverage.
5. Build one-chunk asset proof, then full deterministic asset source and bounded world.
6. Build independent mp.25 packager/verifier/tests and documentation/evidence.
7. Run full Gradle, GameTest/integration, package/verifier, and sealed-baseline suites.
8. Run `review` and `investigate`, resolve actionable findings, rebuild artifacts, verify
   hashes, commit intentionally, and push only `feat/ashen-span` to `fork`.

Parallel work is safe only after the core contract is fixed: runtime/entity changes,
asset/world tooling, and package verification can proceed in separate file sets. Final
integration, artifacts, review, and evidence remain sequential.

## NOT in scope

- Generic waves, random bosses, endless survival, campaign/progression, crafting, or a
  reusable AI/navigation framework: contradict the vertical-slice decision.
- Future City, Mosslorn, or any downloaded city save: provenance and compatibility risk.
- Rooftop/space content, PMB02/03/05/06 missions, PMS08/09, PMT qualification,
  destructible bridge, capture/escort, or public multiplayer: explicitly deferred.
- New GUI/HUD, models, textures, shaders, music, voice, or animation dependency: the
  mission uses existing title/actionbar/chat and presentation assets.
- Release, aliases, upstream PR, deployment, or live play were excluded. The work stopped
  at a historical offline candidate, which later player-ticket evidence invalidated for
  bounded-world qualification.

## Review report

| Review | Status | Findings |
|---|---|---|
| Workflow gate | Applied | Full gstack pipeline and outside-voice checkpoint required |
| Eng review | CLEAR WITH LOCKED MITIGATIONS (fallback) | Validation ordering, staging, ownership, service reset, gate durability, rival release, PMB04 ancestry |
| Independent subagent | CLEAR WITH LOCKED MITIGATIONS | Code-grounded architecture and test seams incorporated |
| Baseline build | PASS | Common, Fabric, and Forge build successful |
| Final diff review | CLEAR | Independent runtime/spec and packaging reviews found no P0/P1 issue or scope violation |
| Investigation | COMPLETE | Root causes fixed; full Gradle, GameTest, Python, world, and dedicated-server gates pass |
| Superseding player-ticket audit | **BLOCKED** | Normal 6/6 player registration generates 17 forbidden FULL chunks outside the 680-chunk envelope; prior clearance is not a release verdict |

## Investigation report

The adversarial investigation found and closed five candidate-blocking defects:

1. staged normals could be perceived before their authored reveal;
2. PMB04 recovery could leave an admitted hitbox lineage alive;
3. first publication could fail when its safe parent directory did not exist;
4. the custom Lost Cities profile was verified in the asset but omitted from the
   client and server runtime paths; and
5. server startup used a mutable chunk-status cache and then vanilla's START
   ticket persisted a proto-chunk halo outside the locked world.

The fixes add physical reveal shutters and staging suppression, complete boss
descendant cleanup, safe exclusive parent creation, byte-exact profile
materialization, persisted-NBT contract checks, and a narrowly authenticated
Sector 01 startup-ticket policy. The final source gates pass: 25/25 Gradle build
tasks, 15/15 offlineCheck tasks, 87 common JUnit tests, six Forge GameTests, 36
packaging/world tests, eight asset tests, and a dedicated server boot/validate/stop
smoke whose Anvil inventory remained exactly 680 full chunks. Remaining uncertainty was
then believed to be restricted to the explicitly separate live balance, FPS,
compatibility, presentation, and soak gates. That conclusion is superseded: the smoke
registered no player, and later real player-ticket evidence exposed the unresolved
offline safety-envelope contradiction described at the top of this file.
