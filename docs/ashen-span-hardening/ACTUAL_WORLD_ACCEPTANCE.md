# Actual candidate-world acceptance

Status: **qualification harness implemented; certification blocked by a locked
6/6-radius versus 680-chunk-envelope contradiction**.

This lane runs the real dedicated Forge server from an exclusive disposable copy
of the candidate server overlay. It never launches a client or GUI. The
qualification mod is a separate, property-gated source set and artifact; normal
`build`, `remapJar`, client, server, MRPack, and overlay assembly do not depend on
it. The external runner rejects any candidate containing the qualification token
or JAR, including inside nested ZIP/JAR payloads.

## Trust and isolation contract

- RC5 debugging is accepted only against the immutable hard-coded 11-file
  inventory and hashes. RC5 is sealed and is never rewritten.
- The disabled runner retains a frozen pre-erratum RC6-shaped binding so historical
  diagnostics cannot substitute a different artifact. It requires
  `--candidate-tree-sha256`, the independently recorded domain-separated digest of
  exactly 11 physical files, plus the old proposed filenames/identity, runtime commit
  `5a35ec3d9a69fdd4d88ed7a0b21b28bc1b18ecfb`, and production JAR SHA-256
  `0263191D695C2CBB136B883CC62DAF1354C1B2013FFCCEDDDE54CE9DD63600F4`
  (10,704,165 bytes). The physical standalone JAR, client MRPack copy, server
  overlay copy, manifest anchor, receipt anchor, and fixed-input anchor must all
  agree. This is diagnostic provenance, not a future RC6 contract; the artifact
  predates the LF checkout policy and every anchor must be replaced after the erratum.
- Extraction rejects absolute/traversal paths, backslashes, Windows drive or
  alternate-stream syntax in every component, duplicate/case-colliding members,
  symlinks, and expansion beyond the fixed cap.
- Candidate and acceptance-output roots must be wholly disjoint. Immediately before
  any PASS receipt, the runner rebinds the complete 11-file candidate tree and rejects
  any concurrent drift, including changes that do not contain the probe token.
- The pinned Forge 47.3.3 library tree and Java executable are hashed before use.
  `online-mode=false`, status/query/RCON are disabled, and Forge version checking
  is disabled in a deterministic `config/fml.toml`. The console must contain the
  disabled marker and must not contain an outbound version-check start marker.
- The candidate world is measured before server launch as the exact 680 full
  chunks. Afterward, all original chunks must remain full, there must be zero
  extra full chunks, and the exact non-full prototype inventory is pinned by
  coordinate, status, and digest. No generated chunk may be hidden or deleted to
  manufacture a pass.
- A PASS receipt is written only after the server exits normally, JSON and console
  evidence validate, the complete candidate rebind passes, and the exclusive scratch runtime passes a final
  containment/reparse audit and is completely removed. Failure preserves scratch
  and evidence for diagnosis.

## Runtime assertions

For Garage Fleet builds 1–6, the probe invokes the production Brigadier
`arena solo start` and `arena solo retry` paths through a server-side FakePlayer.
It verifies selected templates at first start and retry, the seven authored phases
and 15 normal roots, every staged unit's exact slot/type/socket/facing, continuity
of the preplaced 5A Gatekeeper UUID through its 5B ownership promotion, gate
matrices, durable gate/shutter restoration, root ownership metadata,
degraded-then-restored service health/energy/ammo/fuel, PMB04 plus its supplemental
hitbox lineage, the authored victory outcome, retry cleanup, final exact-zero
residue, dismount, original position/yaw/game-mode restore, empty pending restore,
and empty mission-gate ledger. Before claiming exact zero it also checks the
captured ownership registry, its root/descendant/tombstone maps, the Hooks
last-known-position index, and the active Hooks pointer directly. Each build ledger
records its full start-to-cleanup elapsed tick interval; the external validator
rejects intervals whose sum exceeds the total probe runtime.

The probe retires admitted roots to accelerate the deterministic state machine.
It does **not** simulate player aim, combat tactics, input handling, ledge behavior,
balance, FPS, compatibility, or soak. Those claims remain assigned to existing
GameTests or to the separate live-validation goal.

Forge FakePlayer drops connection teleports, so the qualification subclass applies
the same-dimension server-side move required for restore verification without
packets or client spoofing. `ServerLevel.addNewPlayer`, however, registers the
FakePlayer with the same vanilla player-distance ticket machinery as a real player;
the harness must not suppress that behavior to manufacture a bounded-world pass.
Qualification-only chunk handling never uses persistent forced chunks.

## Commands

Build the unshipped probe and run its unit suite:

```powershell
.\gradlew.bat :forge:compileQualificationJava :forge:remapQualificationJar --no-daemon
python -m unittest tools.tests.test_ashen_span_acceptance -v
```

Run the RC6 one-build scale gate first, substituting the independently recorded
tree digest and candidate path:

```powershell
python tools/run_ashen_span_acceptance.py `
  --candidate <RC6-CANDIDATE> `
  --candidate-tree-sha256 <64-HEX-TREE-DIGEST> `
  --post-proto-sha256 <64-HEX-PINNED-PROTO-DIGEST> `
  --forge-runtime build/ashen-span-dedicated-smoke-bounded-final `
  --output build/ashen-span-acceptance/rc6-one-build `
  --build-limit 1 --timeout 1200
```

Only after that receipt passes, repeat with a new output path and
`--build-limit 6`. Output paths must be new children of
`build/ashen-span-acceptance`; the runner refuses reuse.

## Measured development evidence

- `rc5-one-build-v15`: the probe reached authored victory and clean server stop,
  proving the corrected service observation. The external runner correctly
  withheld a receipt because qualification chunk forcing expanded the scratch
  world from 680 to 1,407 chunk records, including 17 forbidden extra full chunks.
- `rc5-one-build-v16`: persistent forcing was removed; the run failed closed at
  `RUN_PHASES` after nine normal roots and preserved evidence. Its post-run
  inventory showed the same expansion, isolating the remaining synthetic-player
  chunk-ticket effect rather than permitting an invariant relaxation.
- `rc5-one-build-v18`: non-generating `getChunk(FULL, false)` retained the exact
  680 full chunks and produced a deterministic 168-record `structure_starts`
  prototype cache (`bf2511d301062cdfc374771a9445110a0005c4c62d202ef02ec5cbce8f1038ce`),
  but the connectionless probe could not hydrate the stored deployment chunk and
  failed before mission start.
- `rc5-one-build-v20`: only packaged deployment/Drop Deck chunks were hydrated
  before player registration and no persistent forcing was used. The real 6/6
  player ticket still produced 17 forbidden full chunks exactly at
  `x=-18,z=-8..8`. The retained scratch inventory contains 1,407 records:
  697 full plus 710 non-full (`structure_starts=629`, `biomes=34`, `carvers=26`,
  `initialize_light=21`), prototype digest
  `07cd255e20b228424d80338ad5f129961e42bc8dbe597f7e6f797de50b75cb83`,
  bounds `x=-29..15,z=-19..19`, and 5,893,857 world bytes. The probe itself
  failed closed at `RUN_PHASES`; no acceptance receipt was written.

These RC5 runs are harness development evidence only. They used the sealed RC5
runtime and an offset pilot to diagnose the already-fixed RC5 deployment collision;
they are not RC5 qualification. Final RC6 acceptance must place the pilot at the
exact packaged `PLAYER_PAD`/world spawn and exercise the reviewed block-only
deployment collision fix.

## Genuine offline blocker

The locked geometry cannot satisfy all current terms under vanilla 1.20.1 ticket
semantics:

1. `OPERATION_ASHEN_SPAN_SPEC.md` lines 57–65 lock safety chunks
   `x=-17..16,z=-10..9`, dedicated view/simulation distance 6/6, and zero silent
   generation.
2. Forge's merged Minecraft 1.20.1 source sets player tickets to entity-ticking
   level 31 (`DistanceManager.java:53,459`) and adds one for every chunk whose
   Chebyshev player distance is at most the configured view distance
   (`DistanceManager.java:448-499`).
3. `ChunkLevel.java:10-12,39-46` defines entity-ticking 31, block-ticking 32,
   and full 33. `ChunkTracker.java` propagates each neighboring level by one in
   all eight directions. A view distance of 6 therefore necessarily requests a
   full-chunk halo through Chebyshev radius 8.
4. The exact player pad `(-160,83,0)` is chunk `(-10,0)`. Its western safety
   margin is only seven chunks, so radius 8 requests the observed `x=-18` strip.
   At the locked playable edges the safety margin is six chunks, meaning an
   effective view distance no greater than 4 would be required to avoid extra
   full chunks throughout the route.

There is no vanilla-safe policy that clips this halo while retaining effective
6/6 behavior: lowering effective view distance, moving the pad/containment,
expanding or pre-generating beyond the locked envelope, or suppressing real player
tickets each changes a locked term or gameplay fidelity. The smallest expanded
FULL footprint for every position allowed by the playable containment is
`x=-19..18,z=-12..11` (38×24, 912 full chunks), but that is only a lower bound:
vanilla tickets also request lower-status dependency chunks beyond the FULL
footprint. An authoritative erratum must therefore define and prebuild a proven
complete dependency envelope, or explicitly permit and bind a deterministic
non-FULL collar. The builder, map contract, verifier, candidate, and one-build/
six-build acceptance anchors must then be regenerated together. Until that
decision is made, the runner correctly rejects the 17 extra full chunks and no
RC6 PASS may be claimed. See
[SAFETY_ENVELOPE_BLOCKER.md](SAFETY_ENVELOPE_BLOCKER.md) for the full decision
record.
