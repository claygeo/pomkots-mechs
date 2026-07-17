# Operation Ashen Span mp.25 candidate tooling

`tools/build_ashen_span_mp25.py` and `tools/verify_ashen_span_mp25.py` form the
offline packaging track for Cold Ruin Sector 01. They do not download mods,
generate a world, launch Minecraft, modify the sealed mp.24 RC4 candidate, or
write a release alias.

The builder requires explicit paths and expected identities for every mutable
mp.25 input. The mp.24 RC4 baseline and Lost Cities 1.20.1 Forge 7.4.13 are
pinned in code. The source commit must resolve to the clean checked-out `HEAD`,
so the receipt cannot self-attest an unrelated object ID. Publication is
exclusive: missing parent directories are created and checked one component at
a time, the output directory must not exist, and a sibling staging directory is
atomically renamed only after every file is written and reread.

## Required final inputs

- sealed mp.24 RC4 MRPack
- `pomkotsmechs-forge-0.0.1-alpha.7-mp.25.jar` and its expected SHA-256
- exact `lostcities-1.20-7.4.13.jar`
- `mecharena_sector01-1.0.0-mp25.jar`, its expected SHA-256, and build receipt
- curated bounded world directory, canonical world-builder archive, expected
  archive SHA-256, and world build receipt
- the exact Architectury, Cloth Config, and GeckoLib JARs named by the sealed
  MRPack index; exactly one Cloth Config input is accepted
- the source Git commit represented by the candidate

## Build

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

## Independent verification

Run `tools/verify_ashen_span_mp25.py` with the same input arguments and replace
`--output-dir` with `--candidate-dir`. The verifier imports neither the builder
nor a shared packaging module. It reconstructs all eleven output files,
independently opens and inspects the MRPack/server/world archives, invokes the
physical Anvil/world-contract verifier against the reconstructed 680-chunk
payload, and rejects a candidate that only has a plausible receipt.

## Generated output contract

The isolated directory contains exactly:

- deterministic client MRPack with one `saves/cold_ruin_sector_01`
- deterministic matched server overlay
- deterministic bounded world archive
- byte-identical mp.25 sidecar JAR
- canonical manifest, SHA-256 list, and offline build receipt
- third-party notices, runbook, rollback, and live-validation status

Both client and server pin view/simulation distance to 6/6. The server overlay
contains one exact Cloth Config JAR and no Mosslorn, downloaded city, extra
save, player state, extra dimension, or unsafe archive path. Live play, balance,
FPS, compatibility, and soak remain explicitly unmeasured.

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

## Tests

```powershell
python -m unittest tools.tests.test_ashen_span_mp25_packaging -v
```

The synthetic end-to-end suite covers deterministic two-build equality,
independent reconstruction, archive mutation, duplicate Cloth Config, forbidden
city payloads, world-tree mismatch, unsafe archive paths, case collisions,
bounded archive expansion, fake/non-Anvil worlds, source-commit identity,
first-run parent creation, identity mismatch, and exclusive no-overwrite
publication.

Run `./gradlew offlineCheck --offline` before packaging. That aggregate gate
includes all loader checks and the required Forge GameTest server; a failed
GameTest produces a failed Gradle invocation.
