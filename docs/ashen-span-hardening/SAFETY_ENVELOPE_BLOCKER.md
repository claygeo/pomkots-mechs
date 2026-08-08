# Operation Ashen Span safety-envelope blocker

Status: **blocked pending an authoritative spec erratum**. No production fix was
implemented. Immutable mp.25/RC5 remains sealed and unchanged; the later
`5a35ec3` deployment-pad collision fix does not change this ticket behavior and is
not, by itself, a releasable RC6 runtime.

## Locked terms in conflict

`OPERATION_ASHEN_SPAN_SPEC.md` lines 56–65 simultaneously lock:

- a 34×20 / 680-chunk safety envelope at `x=-17..16`, `z=-10..9`;
- playable containment at `x=-168..167`, `z=-56..55`;
- real dedicated and integrated `view-distance=6` and
  `simulation-distance=6`; and
- no generated chunks beyond that envelope.

The authored player pad is `(-160,83,0)`. Its chunk is `(-10,0)`.

## Pinned-source proof

**Measured source identity.** This repository pins Minecraft 1.20.1 and Forge
1.20.1-47.3.3 in `gradle.properties`. The locally resolved mapped merged source
JAR used for this review has SHA-256
`4C1BBA0935E2304E88AF13633721838B6C2FF13999E82F3B53DD6A28DE1636A2`.

**Inspected/derived pinned-source behavior.** In that pinned source:

1. `ChunkMap.updatePlayerStatus` registers every non-ignored survival player with
   `DistanceManager.addPlayer`.
2. `DistanceManager.PlayerTicketTracker` is a
   `FixedPlayerDistanceChunkTracker`. At view distance 6, its eight-neighbor
   graph selects every chunk at Chebyshev distance at most 6 and creates a
   `TicketType.PLAYER` ticket at `PLAYER_TICKET_LEVEL`.
3. `PLAYER_TICKET_LEVEL` is
   `ChunkLevel.byStatus(FullChunkStatus.ENTITY_TICKING)`, which is level 31.
4. `ChunkTracker.computeLevelFromNeighbor` propagates to all eight adjacent
   chunks with `neighbor level + 1`.
5. `ChunkLevel.fullStatus` maps levels 31, 32, and 33 to
   `ENTITY_TICKING`, `BLOCK_TICKING`, and `FULL`. A level-31 player-ticket
   source therefore necessarily has a two-chunk FULL dependency halo.

The required FULL ticket/status footprint is consequently `view distance 6 +
dependency halo 2 = 8` chunks. At player chunk `(-10,0)`, the deterministic
FULL union is:

```text
x = -10 - 8 .. -10 + 8 = -18 .. -2
z =   0 - 8 ..   0 + 8 =  -8 ..  8
```

Only the `x=-18` column lies outside the locked envelope, so the predicted
breach is exactly 17 FULL chunks: `(-18,-8)` through `(-18,8)`.

The existing `AshenSpanSpawnPreparationMixin` cannot prevent this. It suppresses
the one-time `MinecraftServer.prepareLevels` START ticket and target, while the
breach begins later in the required normal-player registration path above.
Forge's `ChunkTicketLevelUpdatedEvent` is explicitly non-cancellable.

## Headless reproduction

**Measured.** The retained `rc5-one-build-v20` headless session started from an
Anvil inventory of exactly 680 FULL chunks with bounds `x=-17..16`,
`z=-10..9`. It registered a survival `FakePlayer` through
`ServerLevel.addNewPlayer` at `(-148,83,0)`. That probe offset and the exact
player pad are both in chunk `(-10,0)`, so they exercise the same vanilla player
ticket source.

After orderly server shutdown, a read-only Anvil inventory found 1,407 stored
chunk entries. There were 727 entries outside the contract, including exactly
17 persisted FULL chunks:

```text
(-18,-8), (-18,-7), ... (-18,7), (-18,8)
```

The other 710 outside entries were lower generation statuses: 629
`structure_starts`, 34 `biomes`, 26 `carvers`, and 21 `initialize_light`.
The probe later failed an unrelated phase timeout, but shutdown completed and the
persisted chunk inventory exactly matched the source-derived 17-FULL prediction.

**Inferred from pinned source and measured same-chunk reproduction.** Registering
at the exact `PLAYER_PAD` produces the identical 17-FULL breach because player
tickets are keyed by chunk position, and both coordinates map to `(-10,0)`.

## Route-wide FULL lower bound, not only the spawn symptom

**Inferred from locked geometry and pinned ticket math.** Every player position
allowed by the playable block containment occupies chunks `x=-11..10`,
`z=-4..3`. Adding the required eight-chunk FULL footprint at every allowed edge
yields:

```text
x = -19 .. 18  (38 chunks)
z = -12 .. 11  (24 chunks)
total = 38 * 24 = 912 chunks
block envelope = 608 * 384
```

The locked 680-chunk envelope is two chunks too small on every side of the
declared playable containment. Fixing only the initial west-pad column would not
make the complete containment safe.

This 912-chunk figure is only the minimum FULL footprint. Ticket levels beyond
33 continue through lower `ChunkStatus` dependencies. The measured v20 result's
710 non-FULL entries demonstrate that strict zero stored/generated chunks outside
the boundary is a larger requirement; moving only the FULL boundary moves that
lower-status collar rather than proving its absence.

## Unsafe workarounds ruled out

- Clipping `ChunkTracker` propagation at the rectangle would break the vanilla
  chunk-level graph and the futures/status invariants used by loading, ticking,
  and unloading. Making it world-identity-conditional does not make that safe.
- Suppressing boundary PLAYER tickets, lowering the internal view radius while
  reporting 6, or sending only already-present chunks would silently reduce the
  effective view. `ChunkMap.updateChunkTracking` requires a ticking chunk for
  normal delivery.
- Treating the pilot as an ignored spectator removes the tickets by also removing
  the normal playable survival path.
- Refusing generation at the generator boundary leaves required player-ticket
  futures incomplete or failed. Creating void substitutes still creates FULL
  chunks outside the contract.
- A square world border cannot enforce the locked rectangle and does not repair
  ticket dependencies. The spec also expressly rejects this substitution.
- Marking generated chunks unsaved, deleting/cropping them at shutdown, or hiding
  them from the verifier is after-the-fact data destruction, not prevention.
- A global ticket/static override risks other worlds and the preserved
  DUEL/ROYALE modes and introduces exactly the cross-world state leak forbidden by
  the hardening contract.

## Required spec erratum

### A. Expand and prove the complete dependency world — recommended

Retain genuine 6/6 behavior and authorize a larger bounded world. The
route-wide `x=-19..18`, `z=-12..11` rectangle (38×24 / 912 chunks, 608×384
blocks) is the lower bound for FULL chunks, not a sufficient final boundary.
Determine the complete lower-status dependency collar from the pinned ticket and
`ChunkStatus` graph and confirm it with a fresh headless prototype. Then choose
one explicit contract:

- prebuild a larger envelope that contains the entire proven ticket/status
  dependency footprint; or
- retain the 912-FULL lower bound while expressly permitting and deterministically
  binding a declared non-FULL collar, including its exact coordinates, statuses,
  and inventory hash.

Regenerate/crop the world to the authorized contract, update fixed metadata and
verification, remeasure the 32 MiB budget, and prove that a full allowed-route run
creates or promotes nothing beyond the declared inventory. This preserves the
authored route, pad, combat, and vanilla chunk semantics.

### B. Lower the real effective distances

Amend the 6/6 lock and pin a configuration proven to fit. A 4-chunk view radius
has a 6-chunk FULL footprint after the vanilla halo, which exactly fits the
current margins; `view-distance=4` and `simulation-distance=4` is the conservative
candidate. It requires complete headless and later live requalification and
reduces presentation and simulation reach. It contains the FULL footprint only;
the erratum must still authorize and bind the lower-status collar or enlarge the
world to contain the complete dependency graph.

### C. Shrink or shift the reachable route

With genuine 6/6 and the current envelope, player centers would have to remain in
chunks `x=-9..8`, `z=-2..1`. The locked pad, west approach, and east finale do not
fit, so this option requires a material map/route redesign and violates the
current authored-geometry lock. These center bounds likewise address only the
FULL footprint, so a strict zero-extra-chunk contract still needs a proven
dependency-envelope or declared-collar rule.

Until one erratum is authorized, producing an RC6 that claims both exact 6/6 and
exact-zero generation outside the 680-chunk world would be a false qualification.
