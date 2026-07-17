# Cold Ruin Sector 01 bounded world

`tools/build_ashen_span_world.py` creates this release world's bounded archive
and build receipt. It uses a private copy of the Forge 47.3.3 server runtime,
exact Lost Cities 7.4.13, and the pinned `mecharena_sector01` asset JAR. It never
starts or mutates `../server`, `../server-spike`, Mosslorn, or an mp.24
candidate.

The builder first creates a disposable one-chunk world and proves the four
physical Garage sentinels. Only then does a new server process generate the
three bounded batches (240 + 240 + 200 chunks). It crops and repacks terrain to
exactly `x=-17..16,z=-10..9`, injects the runtime-compatible
`mecharena_sector01_contract` SavedData, strips entity/POI/other-dimension
indices, and writes `cold_ruin_sector_01_mp25_world.zip` with the single locked
root `cold_ruin_sector_01/`.

The retained `ashen_span_generator` datapack provides one empty, non-spawning
Overworld biome and tags it for Lost Cities' raw-generation feature. This keeps
the Lost Cities feature pipeline enabled without allowing Plains vegetation or
the Void start platform into the bounded template. The pack is pinned, shipped
inside the world, and verified by hash.

Both build and `--verify-only` validate Minecraft 1.20.1 DataVersion 3465, the
Overworld generator and fixed seed, Garage spawn, sunset time, clear weather,
fixed gamerules, all 680 full chunks and their baked coordinates, the exact
curated file set, physical sentinels, gates, reveal shutters, socket/recovery
support, freight cover, the no-jump route, and the clear/open-sky Power Deck.
Verification opens and safely reconstructs the ZIP, independently validates
that reconstructed tree, compares it with the frozen output tree, checks the
canonical ZIP encoding, and measures the 32 MiB compressed budget. An edited
receipt is not accepted as evidence for archive contents.

Run from the repository root:

```powershell
$forge = 'C:\path\to\private-forge-47.3.3-runtime'
$forgeSha = '<sha256-of-every-libraries-path-and-byte>'
$java = 'C:\path\to\pinned-java-17.exe'
$javaSha = '<sha256-of-java-executable>'
$lostCities = 'C:\path\to\lostcities-1.20-7.4.13.jar'

python .\tools\build_ashen_span_world.py --proof-only `
  --forge-runtime $forge --forge-runtime-sha256 $forgeSha `
  --java $java --java-sha256 $javaSha --lost-cities-jar $lostCities
python .\tools\build_ashen_span_world.py `
  --forge-runtime $forge --forge-runtime-sha256 $forgeSha `
  --java $java --java-sha256 $javaSha --lost-cities-jar $lostCities
python .\tools\build_ashen_span_world.py --verify-only `
  --forge-runtime $forge --forge-runtime-sha256 $forgeSha `
  --java $java --java-sha256 $javaSha --lost-cities-jar $lostCities
python -m unittest discover -s .\tools\tests -p test_ashen_span_world.py
```

Scratch data is confined to ignored `build/ashen-span-world`.  Generated
release outputs are written under `sector01-world/dist`. The large reconstructed
tree and ZIP are intentionally ignored by Git; the measured
`*.build-receipt.json` remains trackable. `--finalize-only` may re-create the
canonical ZIP and receipt from an already verified frozen tree plus complete
generation logs after a post-generation audit interruption; it never runs
Forge. `--forge-runtime-sha256` binds every Forge-library path and byte;
`--java-sha256` binds the exact Java executable before either is copied or run.
The canonical one-chunk and full-generation logs are tracked under
`sector01-world/evidence`, re-parsed for every required completion token, and
re-hashed on verification. The receipt records both log hashes and both runtime
pins.

The deterministic claim is deliberately narrow: the ZIP bytes are canonical
for one verified frozen curated world tree. The receipt does not claim that two
independent Forge/Lost Cities generation runs must be byte-identical.
