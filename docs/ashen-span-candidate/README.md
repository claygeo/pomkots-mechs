# Operation Ashen Span mp.25 candidate tooling

> **BLOCKED — no releasable RC6 candidate exists. Do not run the candidate builder,
> launch RC5, initialize live qualification, or publish any Ashen Span artifact.**
> Genuine 6/6 player tickets create FULL chunks outside the locked 680-chunk safety
> envelope. An authoritative spec erratum and successful exact-pad one-build and
> six-build headless acceptance are required before candidate work resumes. See
> [the safety-envelope blocker](../ashen-span-hardening/SAFETY_ENVELOPE_BLOCKER.md).

`tools/build_ashen_span_mp25.py` and `tools/verify_ashen_span_mp25.py` form the
offline packaging track for Cold Ruin Sector 01. They do not download mods,
generate a world, launch Minecraft, modify the sealed mp.24 RC4 candidate, or
write a release alias.

The in-progress tool identity reserves mp.25/RC6, but its production build and verify
gates are deliberately unconditional and emit no candidate. An erratum label alone cannot
unlock them: a newly named candidate needs a reviewed two-phase build, actual-world
acceptance, and receipt-bound publication implementation. Immutable RC5 remains a
historical predecessor, not a qualified fallback.
The actual-world harness proved one RC5 startup defect: a fresh player occupies the
authored mech pad, but a broad collision predicate treated that same caller as an
obstruction. Commit `5a35ec3` carries the narrow block-versus-non-player collision fix
while retaining the exact authored world, mission, roster, tactics, six builds, and
balance. It is an intermediate fix only: it does not resolve normal player-ticket world
growth and must not be called or packaged as RC6.

The intermediate collision fix is pinned to runtime source commit
`5a35ec3d9a69fdd4d88ed7a0b21b28bc1b18ecfb`. Its clean-worktree mp.25 JAR is
10,704,165 bytes with SHA-256
`0263191D695C2CBB136B883CC62DAF1354C1B2013FFCCEDDDE54CE9DD63600F4`.
These values are diagnostic provenance, not candidate or release anchors. They must be
replaced after the authoritative erratum and reviewed runtime/world rebuild. The JAR
also predates the deterministic checkout policy below, so it is not a canonical cross-platform
runtime artifact.

The builder requires explicit paths and expected identities for every mutable
mp.25 input. The mp.24 RC4 baseline and Lost Cities 1.20.1 Forge 7.4.13 are
pinned in code. The source commit must resolve to the clean checked-out `HEAD`,
so the receipt cannot self-attest an unrelated object ID. Builder provenance
hashes the committed Git blob instead of checkout-dependent bytes, and
`.gitattributes` pins normalized hardening/asset/world inputs and the authored mission
JSON to LF, preserves other production resources as exact Git bytes, and gives the
directly copied root license and Forge `pack.mcmeta` fixed CRLF forms. Gradle therefore
cannot receive platform-dependent payloads. Publication is
exclusive: missing parent directories are created and checked one component at
a time, the output directory must not exist, and a sibling staging directory is
atomically renamed only after every file is written and reread.

## Required future inputs after the blocker is resolved

- sealed mp.24 RC4 MRPack
- `pomkotsmechs-forge-0.0.1-alpha.7-mp.25.jar` and its expected SHA-256
- exact `lostcities-1.20-7.4.13.jar`
- `mecharena_sector01-1.0.0-mp25.jar`, its expected SHA-256, and build receipt
- curated bounded world directory, canonical world-builder archive, expected
  archive SHA-256, and world build receipt
- the exact Architectury, Cloth Config, and GeckoLib JARs named by the sealed
  MRPack index; exactly one Cloth Config input is accepted
- the source Git commit represented by the candidate

## Build (disabled while blocked)

The command below documents the intended interface only. Do not run it against the
current 680-chunk world or the intermediate collision JAR. A successful command before
the erratum would be a tooling defect, not a valid candidate.

```powershell
python .\tools\build_ashen_span_mp25.py `
  --base-mrpack <sealed-mp24.mrpack> `
  --mp25-jar <pomkotsmechs-mp25.jar> --mp25-sha256 <sha256> `
  --lost-cities-jar <lostcities-1.20-7.4.13.jar> `
  --asset-jar <mecharena-sector01.jar> --asset-sha256 <sha256> `
  --asset-receipt <asset-receipt.json> `
  --world-dir <curated-world-directory> `
  --world-archive <world-builder.zip> --world-sha256 <sha256> `
  --world-receipt <world-receipt.json> `
  --architectury-jar <architectury.jar> `
  --cloth-config-jar <cloth-config.jar> `
  --geckolib-jar <geckolib.jar> `
  --source-commit <40-hex-git-object> `
  --output-dir <new-candidate-directory>
```

The builder performs two complete in-memory builds and requires byte equality
before publication. `--check` performs the same two builds and compares them to
an existing output without writing.

## Independent verification (disabled while blocked)

After an authoritative erratum and new candidate exist, run
`tools/verify_ashen_span_mp25.py` with the same input arguments and replace
`--output-dir` with `--candidate-dir`. The verifier imports neither the builder
nor a shared packaging module. It reconstructs all eleven output files,
independently opens and inspects the MRPack/server/world archives, invokes the
physical Anvil/world-contract verifier against the regenerated bounded payload
defined by the future erratum, and rejects a candidate that only has a plausible
receipt. On success it also
prints a domain-separated candidate-tree SHA-256 over all eleven filenames, sizes, and
file hashes. For a future post-erratum candidate, retain that value outside the candidate;
live qualification requires it as the external candidate-identity anchor.

## Generated output contract

The isolated directory contains exactly:

- deterministic client MRPack with one `saves/cold_ruin_sector_01`
- deterministic matched server overlay
- deterministic bounded world archive
- byte-identical mp.25 sidecar JAR
- canonical manifest, SHA-256 list, and offline build receipt
- third-party notices, runbook, rollback, and live-validation status

The MRPack and server overlay contain the same seven-file license bundle under
`overrides/licenses/` and `licenses/`, respectively. The bundle is reconstructed
from the exact input JARs, and missing or altered source license text fails the
build and independent verification. Qualification source/classes are forbidden
from both production JAR members and packaged paths.

Both client and server pin view/simulation distance to 6/6. The server overlay
contains one exact Cloth Config JAR and no Mosslorn, downloaded city, extra
save, player state, extra dimension, or unsafe archive path. Live play, balance,
FPS, compatibility, and soak remain explicitly unmeasured.

The 6/6 values are also the current offline blocker: a normal player ticket has an
eight-chunk FULL footprint, while the locked route has only six or seven chunks of safety
margin. Packaging the static 680-chunk input does not prove that it remains bounded during
play.

Both payloads also contain the same canonical `pomkotsmechs.json`, with entity
and player-vehicle block destruction set to `false`. This is a startup contract,
not a balance setting: the authored mission fails closed if either value is on.

Both payloads materialize the exact canonical profile embedded in the asset JAR
at `config/lostcities/profiles/mecharena_sector01.json` (under `overrides/` in
the MRPack). The builder and independent verifier pin its 1,861 bytes and
SHA-256, reject any additional profile member, and require client/server bytes
to match the asset member exactly.

For the authenticated Sector 01 world only, Pomkots suppresses vanilla's
initial START ticket and its 441-chunk wait target. Vanilla otherwise persists a
proto-chunk dependency halo outside the locked 34x20 envelope before a player
joins. Non-Sector worlds retain vanilla startup behavior. The dedicated smoke
in `OFFLINE_VALIDATION.md` proves boot, contract validation, shutdown, and an
unchanged 680-full-chunk terrain inventory.

That historical smoke had no registered player and therefore did not test player-distance
tickets. Later same-chunk headless reproduction generated 17 forbidden FULL chunks at
`x=-18,z=-8..8`; it supersedes any interpretation of that smoke as bounded-play evidence.

## Tests

```powershell
python -m unittest tools.tests.test_ashen_span_mp25_packaging -v
```

The synthetic end-to-end suite covers deterministic two-build equality,
independent reconstruction, archive mutation, duplicate Cloth Config, forbidden
city payloads, world-tree mismatch, unsafe archive paths, case collisions,
bounded archive expansion, fake/non-Anvil worlds, source-commit identity,
first-run parent creation, identity mismatch, and exclusive no-overwrite
publication. It also covers exact client/server license parity, missing/mutated
license rejection, and qualification-payload exclusion.

Run `./gradlew offlineCheck --offline` before packaging. That aggregate gate
includes all loader checks and the required Forge GameTest server; a failed
GameTest produces a failed Gradle invocation.
