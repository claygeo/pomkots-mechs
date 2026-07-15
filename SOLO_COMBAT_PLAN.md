# Solo Combat Vertical Slice — fallback engineering review

Date: 2026-07-14
Branch: `feat/solo-combat`
Baseline: `f8ead7c` (`mp.21`), clean worktree, Java 17 compile passing

Formal `/plan-eng-review` status: **BLOCKED — AskUserQuestion unavailable.**
This document is the workflow-gate fallback: a code-grounded internal engineering
review plus independent entity, arena, and map audits. It is not represented as a
completed interactive gstack review.

## Outcome

Build one complete solo run before scaling content:

1. `/arena solo start <build> [seed]` immediately journals and deploys one player
   in any of the six Garage Fleet builds.
2. Three paced enemy-mech archetypes spawn through a deterministic threat budget.
3. A real PMB boss arrives after the waves.
4. Victory, defeat, retry, stop, crash cleanup, and player restoration converge on
   the existing arena teardown path.
5. Cold Ruin Balanced and all texture/animation assets remain frozen.

Mosslorn remains the first venue. A replacement map is not in this slice.

## Scope challenge

The complete goal includes endless variety, map research, save/rejoin continuation,
live performance calibration, and release packaging. Implementing all of that before
proving one encounter loop would hide lifecycle failures behind content volume.

The first slice therefore includes the final architecture and data format, but only:

- six selectable player builds;
- three enemy archetypes (`pms01`, `pms03`, `pms02`);
- three short waves;
- one boss (`pmb01mk2`);
- one human player and optional observers;
- conservative fixed caps pending live measurement.

After this slice passes, additional enemies, weights, waves, elites, and bosses are
data changes. Save/rejoin continuation and a second map remain later gates.

## NOT in scope

- New textures, shaders, models, animation controllers, or particle systems.
- Crafting, salvage, hunger, foraging, lore, quests, or economy.
- Removing or rewriting DUEL/ROYALE.
- A custom client GUI in the first slice; commands are the deterministic test and
  control surface.
- Loading new chunks to find spawn points. The director fails closed or waits.
- Reusing `RaidControllerEntity`; it requires a living raid owner and adds objective,
  reward, finalize-command, and raid-bar semantics that do not fit this loop.
- Claiming 30-FPS approval before a matched live combat capture exists.

## What already exists

- `ArenaManager`: server-authoritative FSM, event hooks, bounds, restore journal,
  match tags, stale-entity sweeps, cleanup, and command handlers.
- `ArenaData`: persisted restore records and monotonic match IDs.
- `GarageFleet`: six complete PMVC01 player builds with frame, weapons, ammo, fuel,
  generator, and booster.
- Nine PMS enemy entities with autonomous AI; the first slice uses:
  - `pms01`: fast melee charger;
  - `pms03`: ground rifle pressure;
  - `pms02`: flying missile/bullet pressure.
- Six modern PMB bosses. `pmb01mk2` has current BaseBoss AI, phased attacks, a real
  damage-forwarding hit box, and a boss bar. It needs explicit initial hate so it
  engages immediately instead of waiting to be hit.
- A one-shot royale PvE hook. It is not a director: it uses shared world RNG,
  royale-only scatter points, immediate 6/12-unit bursts, no visibility checks,
  no recovery, and permanently persistent mobs.

## Architecture

```text
player command
    |
    v
ArenaManager.commandSoloStart
    |-- durable RestoreRecord + match id flush
    |-- GarageFleet.build(build)
    |-- position/tag/add/mount player mech
    `-- SpawnDirector.prepare(catalog, seed, player, arena)
            |
server tick |  lightweight phase/cap checks
            v
      WARMUP -> WAVE_COMBAT <-> INTERMISSION -> BOSS_COMBAT
                    |                              |
entity death -------+------------------------------+
                    |                              |
                    `----------> VICTORY <----------'

fighter/mech death -------------------------------> DEFEAT
VICTORY / DEFEAT -> ArenaManager.beginEnding -> shared cleanup -> IDLE
                                      |
                                      `-> /arena solo retry
```

Ownership stays deliberately split:

- `ArenaManager` owns player mutation, global match state, event wiring, restore,
  bounds, outcome, and cleanup.
- `SpawnDirector` owns catalog validation, seeded wave plans, placement, enemy UUIDs,
  pacing, leash/stuck recovery, telemetry, and boss completion.
- Enemy entities retain their upstream AI and attacks; no new animation or AI loop is
  added to render or client code.

## State model

The existing global FSM remains:

```text
IDLE -> ACTIVE(SOLO) -> ENDING -> IDLE
```

SOLO has a nested phase:

```text
WARMUP -> WAVE_COMBAT -> INTERMISSION -> ... -> BOSS_WARNING
       -> BOSS_COMBAT -> VICTORY
                         or
any live phase -> DEFEAT
cleanup is idempotent; retry starts only after cleanup
```

DUEL and ROYALE retain their current countdown and active paths. Only SOLO permits
one fighter, and SOLO never calls the multiplayer `alive <= 1` win condition.

## Data contract

`data/pomkotsmechs/arena/solo_encounters.json` defines:

- spawn distances, candidate attempts, cooldowns, leash/stuck thresholds;
- maximum active units and maximum active threat cost;
- ordered wave budgets;
- weighted archetypes with entity ID, threat cost, placement (`ground`/`air`), and
  vertical offset;
- boss entity, warning delay, threat cost, placement, and distance band.

The loader validates the full snapshot before a run starts. Unknown entity IDs,
non-positive weights/costs, impossible distance bands, empty waves, or a missing boss
fail the start with a clear message. The prior valid built-in catalog remains the
fallback if a datapack override is malformed.

Determinism contract:

```text
run seed + wave index + spawn ordinal -> roster pick + candidate sequence
```

World height/collision can reject a candidate, but the ordered attempt sequence and
the final accepted result are reproducible for the same world state.

## Smart placement rules

A candidate is accepted only when all applicable checks pass:

1. Its chunk is already loaded; spawning never forces generation.
2. Horizontal distance is inside the configured band and outside the no-spawn radius.
3. Ground units have a sturdy surface; air units use a heightmap plus offset.
4. The entity's full bounding box has no block/entity collision and remains inside
   build height.
5. A point in the player's forward camera cone is rejected when an unobstructed ray
   reaches it; points behind the player or occluded by terrain are allowed.
6. The point is inside the solo arena leash and not within the last spawn cluster.
7. Unit and threat caps still have room when the entity is committed.

Failed attempts wait and retry with the same deterministic sequence. They do not
silently skip content or spawn at the player's feet.

## Pacing and performance rules

Initial conservative caps, to be replaced only by measured values:

- maximum five normal enemies alive;
- maximum six active threat points;
- flyer cost two; ground units cost one; boss runs alone;
- at most one spawn commit per server tick;
- at most 32 geometry candidates per spawn request;
- heavy reconciliation every 20 ticks, stuck/leash audit every 100 ticks;
- no forced chunks, no new particles, no new animations, no client tick loop;
- low mech health reduces the next wave budget by one, never below one;
- active projectiles are observed in telemetry before a projectile cap is selected.

The 30-FPS gate is a matched Cold Ruin Balanced combat run on Clayton's laptop. Code
completion is not performance approval.

## Recovery and cleanup

- Every solo entity carries arena, match, PvE, and solo tags.
- The ADD guard admits only a UUID currently tracked by the director for the current
  match; an unloaded enemy abandoned by recovery is culled when it returns.
- Beyond the hard leash, an enemy is discarded and deterministically replaced.
- A loaded enemy that has moved less than the configured threshold for the stuck
  window is relocated to a new valid point; bounded relocation failure discards and
  replaces it.
- Duplicate death events are idempotent.
- Boss death completes the run; player/mech death wins any same-tick race and yields
  defeat.
- Stop, quit, server stop, abort, victory, and defeat all call the shared idempotent
  arena cleanup and restore path.
- A process crash does not resume a partial wave in the first slice. On restart,
  persisted restore records heal the player and boot cleanup reaps tagged entities.

## Test path diagram

```text
command validation
  |-- invalid build/catalog/geometry -> clear failure, no mutation
  `-- valid -> journal flush -> deploy/mount
                            |-- failure -> abort + restore
                            `-- success -> warmup
                                           |
                              deterministic wave plan
                                |-- no valid point -> bounded wait/fail
                                `-- spawn -> track -> death/stuck/leash
                                                        |
                                         next wave or boss
                                                        |
                         fighter loss -> defeat          boss loss -> victory
                                      \                 /
                                       shared ending/cleanup
                                                |
                                         clean retry/second run
```

Automated pure-logic coverage now passing (25 tests):

- identical seed/config yields identical roster and candidate order;
- weighted selection never exceeds wave/threat caps;
- low-health budget scaling is bounded;
- invalid catalogs fail validation;
- arena-edge priority candidates and the full 3D visibility cone are bounded;
- legal player bounds remain inside the enemy pursuit leash;
- Fabric projectile sweeps require a current tracked UUID;
- death-screen quit/rejoin retains the restore journal for respawn;
- a removed/dead boss cannot retain an orphan damage hitbox;
- each roster slot is bounded to one relocation, one same-slot replacement,
  then retirement instead of an infinite terrain-recovery loop;
- boss retirement/disappearance cannot become victory, and a cancellable
  living-death event must reach confirmed `KILLED` removal first.
- phase advancement fails closed at the exact boss boundary, and even stale
  victory state is rejected unless it belongs to that boss phase and follows a
  confirmed defeat;
- duplicate confirmed boss-death facts are idempotent, debug-next cannot skip the
  boss, and reset leaves a second run with pristine run-scoped state.

Still required before final RC promotion:

- world-backed stuck/unloaded threshold and retirement coverage;
- live defeat-over-director-victory arbitration coverage (the production order
  checks fighter/mech loss before ticking the director).

Build/integration verification:

- Java 17 `./gradlew build` for Forge and Fabric outputs;
- fresh SOLO start for each of six player builds;
- each of three archetypes targets and damages the fighter/mech;
- boss takes damage through its hit box, attacks, dies, and completes victory;
- mech destruction, player death, `/arena stop`, quit, and retry restore cleanly;
- fixed seed replays roster and spawn order;
- blocked, visible, distant, stuck, and unloaded enemy cases recover without growth;
- DUEL and ROYALE smoke tests remain unchanged.

Live gates after the slice:

- chunk-travel route;
- 30-minute soak with entity/projectile counts;
- matched PresentMon capture under Cold Ruin Balanced;
- 30-FPS decision before raising caps or adding animation-heavy content.

## Failure modes

| Failure | Test | Handling | Player-visible |
|---|---|---|---|
| No hidden collision-free point | deterministic retry test | bounded wait then clean abort | yes |
| Bad datapack entity ID | catalog validation test | reject snapshot/start | yes |
| Enemy unloads beyond player | integration travel test | tracked/leash replacement; stale ADD cull | telemetry/status |
| Enemy cannot path out of geometry | lineage + live recovery test | relocate once, replace once, then retire; boss aborts | telemetry/status |
| Boss never acquires target | boss integration test | seed explicit hate toward fighter | boss warning then error |
| Boss hit box orphaned | cleanup/server-stop test | parent self-kill plus tagged boss cleanup | silent but bounded |
| Same-tick boss and mech death | transition unit test | defeat precedence | yes |
| Spawn loop exceeds caps | soak + cap tests | hard unit/threat gates | status |
| Crash after player mutation | restart test | pre-mutation restore journal | recovery message |
| Retry inherits UUIDs/timers | reset/second-run test | idempotent reset | yes on failure |

No current critical gap is accepted silently without both handling and a planned test.

## Implementation tasks

- [x] **T1 (P1)** — Add SOLO runtime entry, commands, single-player deployment, and
  shared lifecycle integration without changing DUEL/ROYALE behavior.
- [x] **T2 (P1)** — Add validated encounter data and deterministic wave planning.
- [x] **T3 (P1)** — Implement camera/LOS/collision-aware placement, caps, tracking,
  leash, stuck recovery, and concise telemetry.
- [x] **T4 (P1)** — Integrate `pmb01mk2`, explicit hate, victory/defeat precedence,
  retry, and cleanup.
- [x] **T5 (P1)** — Add unit tests and run full Gradle build (25/25 tests; all 27
  common/Fabric/Forge tasks passed on the mp.24 fail-closed build).
- [x] **T6 (P1)** — Run fallback diff review and native systematic investigation;
  fix findings. Formal interactive gstack workflows remain blocked because this
  session exposes no `AskUserQuestion` tool.
- [ ] **T7 (P2)** — mp.22 live QA passed defeat, retry, wave, boss, stop, cleanup,
  and 30-FPS gates but exposed repeated stuck recovery; package mp.24 and rerun the
  bounded-recovery, boss-provenance, and lifecycle regression only at the final live
  gate.
- [ ] **T8 (P2)** — Promote/package only after fresh-install, soak, and 30-FPS gates.

Sequential implementation is preferred because lifecycle, tags, and director behavior
all converge in the same `arena` module. Read-only entity and map audits run in parallel.

## Fallback review summary

- Scope challenge: reduced to one complete data-driven vertical slice.
- Architecture: one global server FSM plus a package-local deterministic director.
- Code quality: extract spawning/pacing from the existing 2,204-line manager.
- Tests: no pre-existing test tree; add pure tests plus live integration gates.
- Performance: fixed conservative caps; visuals frozen; measurement required.
- Critical gaps found before implementation: random/visible spawning, persistence
  growth, two-player assumptions, instant solo victory, and boss target acquisition.
- Outside voice: independent arena/state audit agreed on `Mode.SOLO` plus a dedicated
  director and rejected `RaidControllerEntity` reuse.
- Review status: fallback plan ready to implement; formal interactive gstack review
  remains blocked by unavailable `AskUserQuestion`.
