# Mech Arena Code and Combat-Asset Audit

Audit date: 2026-07-14  
Source baseline: `fec9e9dfba10ecb17b2734a842339d0b8908b2c5`  
Scope: registered vehicles, Arena builds, enemies, turrets, bosses, weapons,
projectiles, spawn/control hooks, persistence, and client/server ownership.

This is a static source audit. “Implemented” means the registry, AI/action, and damage
paths exist. “Arena-qualified” is stricter: the asset is accepted by the Spawn Director,
present in the encounter catalog, and has appropriate test or live evidence. No asset
is called live-qualified merely because its source looks complete.

## Executive inventory

| Pool | Registered/implemented | Current SOLO catalog |
|---|---:|---:|
| Player vehicle entity types | 6 | PMVC01 only |
| Complete PMVC01 player builds | 6 | 6 |
| Mobile enemy types | 9 | 3 |
| Armed turret types | 4 | 0 |
| Boss types | 7 | 1 |
| Modular weapon items | 18 | 14 used across the six builds |

Authoritative registrations are in
`common/src/main/java/grcmcs/minecraft/mods/pomkotsmechs/PomkotsMechs.java`:
vehicles at lines 107–113, enemies at 116–124, bosses at 127–134, turrets at
139–142, projectiles/helpers at 145–205, weapons at 368–390, and ammo at 393–399.

## Player vehicle entity types

| ID | Class | Source-backed capability | Arena status |
|---|---|---|---|
| `pomkotsmechs:pmv01` | `Pmv01Entity` | Fixed combat frame: hammer, gatling, pile-bunker, grenade, and missiles | Not used by SOLO |
| `pomkotsmechs:pmv01b` | `Pmv01bEntity` | Combat-capable, but mechanically a PMV01 subclass with no overrides | Not used by SOLO |
| `pomkotsmechs:pmv02` | `Pmv02Entity` | Construction/utility frame: drill, roller, vacuum, lift, and block placement; no genuine entity-damage kit | Not a combat candidate |
| `pomkotsmechs:pmv03p` | `Pmv03pEntity` | Multi-seat combat/mobility frame with dual gatling and missile actions | No corestone/localized entity name; not SOLO-qualified |
| `pomkotsmechs:pmv03` | `Pmv03Entity` | Large fixed combat frame with dual rifles and missile | Not used by SOLO |
| `pomkotsmechs:pmvc01` | `Pmvc01Entity` | Configurable chassis with four dynamic weapon hardpoints | Current player chassis |

All six types have renderer and attribute registration. The useful static combat pool is
therefore five combat-oriented vehicle registrations plus one utility vehicle. The six
Arena choices below are loadouts on PMVC01, not six different entity types.

## Six complete Garage Fleet builds

`GarageFleet.java:53-110` defines the stable player-facing order. Build creation uses
PMVC01, installs all frame/power/weapon/extension items, loads matching magazines and
48 fuel pellets, recalculates stats, and sets full health (`GarageFleet.java:129-138`,
`211-234`).

| Index | Build | Frame / power | Weapons | Extensions |
|---:|---|---|---|---|
| 0 | Vanguard | Altair / Shiga / Narita | Shakuji rifle, Tsurugi blade, Kawasemi missiles | Soft lock |
| 1 | Siege | Aldebaran / Chiba / Kansai | Kasumi gatling, Biwa grenade, Dodo gigantic missile | Hard lock |
| 2 | Skirmisher | Deneb / Saga / Haneda | Senzoku shotgun, Kagenobu pile-bunker | Soft lock, hover |
| 3 | Artillery | Vega / Chiba / Narita | Shakuji rifle, Mukudori and Nosuri missile shoulders | Hard lock |
| 4 | Duelist | Sirius / Saga / Haneda | Mashu beam rifle, Tsurugi blade | Soft lock, hover |
| 5 | Trooper | Muknvali / Chiba / Kansai | Shinobazu SMG, Gassan lance, Suwa shoulder gatling | Hard lock |

The partial mp.24 live run proved that all six can be created, mounted, stopped, and
cleaned up. It did not prove every weapon under combat load.

## Mobile enemies

All nine registrations have attributes, renderer paths, target/goal code, and real
damage or projectile implementations. Their balance data is in
`common/src/main/resources/data/pomkotsmechs/enemies.json`.

| ID | Archetype and attack implementation | Current SOLO |
|---|---|---:|
| `pms01` | Charging melee; AABB damage (`Pms01Entity.java:54-70,110-114`) | Yes |
| `pms02` | Flying hybrid; enemy missile and bullet (`Pms02Entity.java:50-55,67-102`) | Yes |
| `pms03` | Ground gunner; `BulletMiddleEntity` (`Pms03Entity.java:45-77`) | Yes |
| `pms04` | Ground missile gunner (`Pms04Entity.java:47-74`) | No |
| `pms05` | Grenade lobber (`Pms05Entity.java:45-80`) | No |
| `pms06` | Roller/dash hybrid with missile and gun (`Pms06Entity.java:41-99`) | No |
| `pms07` | Roller/dash melee unit (`Pms07Entity.java:39-81`) | No |
| `pms08` | Suicide/explosion roller (`Pms08Entity.java:39-79`) | No |
| `pms09` | Flying fin-funnel bullet unit (`Pms09Entity.java:48-85`) | No |

The current schema-1 catalog uses PMS01, PMS03, and PMS02 only. The other six are
real expansion candidates, but their threat costs, geometry, effects load, recovery,
and PMVC01 damage behavior remain unqualified.

## Armed turrets

All four are combat implementations but have no spawn eggs and are absent from SOLO:

| ID | Capability |
|---|---|
| `pmt01` | Rapid cannon using `BulletGrenadeEntity` |
| `pmt02` | Homing missile turret |
| `pmt03` | Grenade/cannon turret |
| `pmt04` | Gigantic-missile turret |

Turrets are potential fixed encounter variants, not safe drop-in wave entries: the
current Spawn Director validates only `BaseSmallMonsterEntity` or `BaseBossEntity`.

## Bosses

All seven registered bosses have real target/action/damage code. Only PMB01MK2 is
cataloged and Arena-qualified. The source-backed verdict must not be confused with a
runtime combat/performance pass.

| ID | Architecture and combat kit | Qualification |
|---|---|---|
| `pmb01` | Legacy boss; punch, upper, jump, gatling, grenade, normal/large missiles | Implemented, but rejected by current modern-boss type check |
| `pmb01mk2` | Modern phased boss; melee, jump, gatling, grenade, normal/large missiles, extra hitbox | Current catalog boss |
| `pmb02` | Modern flying/orbital boss; saber, cannon, missile pod and multi-direction salvos | Static source only |
| `pmb03` | Modern ground heavy; gatling, grenade, stomp, missiles and missile pod | Static source only |
| `pmb04` | Modern melee/dash boss; saber patterns plus gatling and missiles | Static source only |
| `pmb05` | Modern laser heavy; short/long lasers, stomp, gatling and missiles | Static source only |
| `pmb06` | Modern carrier/summoner; cannons, missile, hadoho and PMS01–09 summon pools | Static source only; highest entity/effects risk |

PMB01MK2’s weighted walk/dive/attack goals are in `Pmb01mk2Entity.java:41-114`,
with projectile and area attacks at lines 127–326. PMB02–06 each extend
`BaseBossEntity`; PMB01 does not. The director’s accepted modern boss boundary is in
`SpawnDirector.java:1054-1085`.

## Modular weapon items

All 18 registered weapons have functional attack implementations. Four are presently
unused by the six Garage builds: Uguisu, Kagami, Jinba, and Takao.

| ID | Slot/type | Attack | Ammo |
|---|---|---|---|
| `shakuji` | Hand rifle | `BulletRifleEntity` | Rifle |
| `shinobazu` | Hand SMG | `BulletMachineEntity` | Machine gun |
| `senzoku` | Hand shotgun | Spread `BulletMachineEntity` pellets | Shotgun |
| `kasumi` | Arm gatling | `BulletMachineEntity` large type | Gatling |
| `biwa` | Shoulder grenade | `BulletGrenadeEntity` | Grenade |
| `kawasemi` | Shoulder missile | Straight multi-lock homing missiles | Missile |
| `nosuri` | Shoulder missile | Vertical multi-lock salvo | Missile |
| `mukudori` | Shoulder missile | Curved multi-way salvo | Missile |
| `uguisu` | Hand missile | Four-target salvo | Missile |
| `dodo` | Shoulder heavy missile | `MissileGenericLargeEntity` | Large missile |
| `mashu` | Hand beam rifle | Explosive-impact `BulletBeamEntity` | None |
| `kagami` | Hand grenade | `BulletGrenadeEntity` | Grenade |
| `jinba` | Arm melee | Drill damage | None |
| `takao` | Hand melee | Hammer with distance multiplier | None |
| `kagenobu` | Arm melee | Pile-bunker | None |
| `tsurugi` | Arm melee | Blade sweep | None |
| `suwa` | Shoulder gatling | `BulletMachineEntity` large type | Gatling |
| `gassan` | Hand melee | Lance | None |

Damage ranges and level curves live in
`common/src/main/resources/data/pomkotsmechs/parts.json`; the code implementations are
under `items/parts/weapons/`. Weapon existence alone does not make a balanced variant:
future schema entries still need a PMVC01 loadout, threat/effects cost, ammo contract,
and deterministic eligibility rules.

## Combat projectile and damage entities

Registered damaging paths:

- Direct bullets: `bullet`, `bulletmiddle`, `bulletrifle`, `bulletmachine`,
  `bulletmachinelarge`, and `bulletbeam`.
- Explosive ballistic: `grenade`, `grenadelarge`, and `bulletgrenade`.
- Missiles: `missilegeneric`, `missilegenericlarge`, `missilevertical`,
  `missilehorizontal`, `missileenemy`, `missileenemylarge`, and `missilepod`.
- Area/melee helpers: `explosion`, `earthbreak`, `earthbreak2`, `earthraise`, and
  `exploadslash`.

Registered non-attack/support paths include `blockprojectile`, `blockmass`, `present`,
`playerdummy`, the four boss hitbox IDs, `kujira`, `alert`, `alertred`, `bossbox`,
`raid_controller`, and `raid_objective`.

The current SOLO projectile guard identifies projectiles by the exact owning player
mech or director-owned enemy, tags them to the match, and caps tracked live shots at
48 (`ArenaManager.java:328-456`). Area/effect entities need separate accounting before
the goal’s particle/effects budget is satisfied.

## Spawn and ownership APIs

- `GarageFleet.build(ServerLevel,index)` creates and configures PMVC01 without adding
  it to the world. `ArenaManager` owns safe placement, tags, `addFreshEntity`, and mount.
- The Spawn Director loads `data/pomkotsmechs/arena/solo_encounters.json`, validates it,
  and falls back to its bundled catalog if an override is invalid
  (`SpawnDirector.java:225-329`).
- Arena spawning uses `EntityType.create`, assigns ownership/tags/persistence/target
  before `addFreshEntity`, and records the UUID before the ADD guard can observe it
  (`SpawnDirector.java:580-662`). It does not call `finalizeSpawn`; that must be a
  deliberate compatibility decision, not changed casually.
- The director never forces chunk loads. It checks the full footprint, build height,
  support, collision, loaded corners, cluster separation, player distance, camera cone,
  and line of sight (`SpawnDirector.java:664-721,1006-1052,1208-1272`).
- Recovery is bounded per slot: relocate once, replace once, then retire. Boss recovery
  exhaustion fails the run (`SpawnDirector.java:724-963`).
- Natural SpawnPlacement registration exists for PMS01–05, but Arena encounters do not
  use natural spawning.

## AI and player-control hooks

- The director assigns the owner as target at spawn and reconciles that target every
  20 ticks. PMB01MK2 also receives hate directly.
- Client input is sent C2S, queued on the server, and applied only while the sender is
  actually riding a `PomkotsControllable` vehicle (`PomkotsMechs.java:827-855`).
- Lock-on is server-validated for driver identity, live target, 150-block range, and
  block line of sight (`PomkotsMechs.java:930-1093`).
- Vehicle movement and weapon actions execute in server entity ticks; renderers,
  animations, particles, and key capture are client concerns.
- Arena’s FSM has no custom client state or HUD. Player telemetry is chat/command based.
- Adjacent hardening gap: the texture-change C2S path can address an arbitrary PMVC01
  entity ID without equivalent ownership/range validation (`PomkotsMechs.java:857-870`).

## Persistence and recovery boundary

- `ArenaData` SavedData persists lobby/pads, next match ID, royale configuration/cage
  ledger, and pending player-restoration records. It intentionally does not persist the
  active match, queue, or Spawn Director (`ArenaData.java:17-156`).
- `RestoreRecord` serializes gamemode, dimension, position, and rotation
  (`RestoreRecord.java:10-54`).
- SOLO writes and force-saves the restoration journal before changing the player or
  spawning/mounting the mech (`ArenaManager.java:2279-2284`).
- Join/quit/respawn handlers and idempotent cleanup restore players and sweep tagged
  entities (`ArenaManager.java:224-320,1872-2015`).
- Active runs and retry recipes are memory-only. Boot clears them and sweeps leftovers;
  disconnect is defeat/restore, not continuation. Durable save/rejoin resume is absent.

## Server/client boundary

| Concern | Authoritative side |
|---|---|
| SOLO/DUEL/ROYALE state, outcomes, spawning, caps, cleanup | Server |
| Entity ownership/tags, restore journal, match IDs | Server |
| Mech movement and weapon mutation | Server after validated C2S input |
| Lock-on target selection | Server validated |
| Renderers, animations, particles, key capture | Client |
| Arena status/debug presentation | Server chat/commands |

Common initialization registers server tick, death, entity-add, quit/join/respawn,
server lifecycle, and command hooks in `ArenaManager.java:181-192`. The global state
machine is IDLE → COUNTDOWN → ACTIVE → ENDING; SOLO branches inside ACTIVE and calls the
Spawn Director (`ArenaManager.java:504-515,1370-1384,1454-1568`).

## Verification boundary and expansion verdict

The current static pool is much larger than the shipped slice, but only the following
should be treated as current SOLO content:

- six PMVC01 Garage Fleet player builds;
- PMS01 melee, PMS03 gunner, and PMS02 flyer;
- PMB01MK2 as the sole boss.

PMS04–09, PMT01–04, PMB02–06, fixed player frames, and unused modular weapons are
source-backed expansion candidates. They are not data-only additions yet because the
schema lacks loadout variants, tiers, elites, multi-boss selection, and effects costs,
and because each candidate still requires geometry, damage, recovery, and performance
qualification. PMB06 in particular can multiply the entity/effect load through summons.

Offline unit coverage now contains 26 tests across director planning/caps/recovery,
ArenaManager seams, and boss-hitbox lifecycle. There is still no world-backed GameTest
for spawning, actual AI damage, boss completion, persistence round trips, or cleanup.
