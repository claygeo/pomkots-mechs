# Operation Ashen Span offline validation

Status: finished-source evidence for the mp.25/RC5 offline candidate. This file
separates measurements made without an interactive Minecraft client from the
live-only gates in `ASHEN_SPAN_LIVE_VALIDATION.md`. Final post-commit artifact
hashes and the source commit are recorded by the generated candidate manifest,
offline receipt, and `SHA256SUMS.txt`; duplicating them in the committed source
would create a circular source-commit/hash claim.

## Measured source and runtime gates

- Authoritative base: `fec9e9dfba10ecb17b2734a842339d0b8908b2c5` on
  `feat/solo-combat`; implementation branch: `feat/ashen-span`.
- `gradle build --rerun-tasks --offline --no-daemon`: PASS, 25/25 tasks.
- `gradle offlineCheck --rerun-tasks --offline --no-daemon`: PASS, 15/15
  tasks, including the noninteractive Forge GameTest server.
- Common JUnit: PASS, 87 tests in 13 suites, zero failures, errors, or skips.
- Forge GameTest: PASS, all 6 required registered integration tests.
- Python packaging/world suite: PASS, 36 tests.
- Sector 01 source-asset suite: PASS, 8 tests.
- Python bytecode compilation: PASS for the packager, independent verifier,
  asset/world entry points, and all `tools/ashen_span_world` modules.
- `git diff --check`: PASS for the implementation and authored documentation.
  The two preserved Markdown hard-break spaces in the intentional pre-existing
  `ARENA_ASSET_AUDIT.md` work and trailing spaces emitted verbatim by Forge in
  the two raw generation logs are excluded from this mechanical check.

The tests cover the locked map geometry and roster, phase order and exact-zero
gates, all six Garage Fleet builds, staged-enemy behavior, ownership and ADD
lineage, service-once semantics, the self-controlled PMVC01 rival, PMB04
hitbox/projectile/effect ancestry and outcomes, retry/restore, and cleanup.

## Asset and bounded-world facts

- Source asset JAR: 297,084 bytes, SHA-256
  `E1AC026BC07966803C5F3AFCF455A0B6B7F924F4131C52600355944A5CBBEFA9`.
- Embedded canonical Lost Cities profile: 1,861 bytes, SHA-256
  `836B07B6493F4381587960E592058E9C78A9C71118FAB3597796E040172F9018`.
  It is shipped byte-identically at the client and server runtime profile paths.
- Frozen world-builder archive: 434,013 bytes, SHA-256
  `3EE5D52888DABA0F15CE8E9BC7A142292D46A0F3F966DCD2C1533771C89F4FA4`.
- Physical world verification: 680 full terrain chunks in `x=-17..16`,
  `z=-10..9`; 308 authored content chunks; 6 gates; 10 reveal shutters;
  17 sockets; 7 recovery anchors; 344 route supports; 7,744 clear route
  cells; 47 runtime physical markers; zero forbidden block entities or ticks.
- Canonical world-tree SHA-256:
  `537AF7F1AB05B5070CED69697CEA04330E89177C9E7B810DA60BAE24A88A1223`.
- One-chunk generation evidence SHA-256:
  `1475FCE7BE7E8EF635E2970DB94A6960467B905FBAD5E4F5BCF836630C14079D`.
- Full-generation evidence SHA-256:
  `D7E3426FA82F33CF90E60E56783E17759345FCE7CFDECE6A0F44C3115294E671`.

The client and server package contracts pin Lost Cities 1.20.1 Forge 7.4.13,
view/simulation distance 6/6, natural spawning off, and both Pomkots block
destruction switches off. The package builder performs two byte-equal builds;
the verifier is a separate implementation that reconstructs all eleven output
files and physically revalidates the Anvil payload.

## Dedicated-server smoke

A fresh isolated Forge 47.3.3 server used the exact six-mod runtime, frozen
world, embedded Lost Cities profile, Java 17.0.19, package properties, and
Pomkots safety config. It reached `Done`, executed `arena solo validate`,
reported all 680 full safety chunks and 47 physical markers, accepted `stop`,
and exited 0. A before/after Anvil inventory remained exactly 680 full chunks
with the same bounds; no startup proto-chunk halo was persisted.

The smoke exposed and closed three release blockers before candidate creation:

1. The embedded custom Lost Cities profile was not materialized into runtime
   config. The builder/verifier now require and ship the exact pinned bytes.
2. Minecraft's mutable chunk-type cache produced a false negative for a stored
   full edge chunk. Runtime validation now reads persisted NBT directly without
   a ticket or generation.
3. Vanilla's START ticket persisted a dependency halo outside the locked
   envelope. It is skipped only when the exact Sector 01 metadata and seed are
   present; every other world retains vanilla behavior.

The retained smoke summary is
`evidence/dedicated-server-smoke.txt`. The full ignored `latest.log` measured
71,948 bytes with SHA-256
`6F7AC3DA7EAD53DF8E11ABC2B586DE549E6291AFAAEC714BB3D0EDE2497CB9C9`.

## Safety and publication boundary

- The intentional `ArenaManager.java`, `ArenaManagerTest.java`, and
  `ARENA_ASSET_AUDIT.md` work is preserved and extended, not reset.
- DUEL/ROYALE, restore ownership, pre-ADD admission, descendant cleanup, and
  no-overwrite publication behavior retain regression coverage.
- The sealed mp.24 MRPack, JAR, manifest, aliases, and hashes are inputs only.
- Candidate publication is exclusive to a new mp.25 directory and fails if the
  destination already exists or any parent component is unsafe.
- No release, alias update, upstream PR, interactive client, Modrinth launch,
  or live gameplay occurred.

## Explicitly awaiting live validation

The 10–13 minute balance target, all-six-build interactive completions, FPS and
frame pacing, visual/audio readability, input accessibility, compatibility,
screenshots, disconnect behavior observed by a player, and extended gameplay
soak remain unmeasured. They are not represented as offline facts.
