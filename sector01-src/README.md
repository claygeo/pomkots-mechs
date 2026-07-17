# mecharena_sector01 asset source

This directory is the canonical low-code source for Cold Ruin Sector 01. The
declarative `sector01-contract.json` fixes the map, seed, profile, volumes,
gates, sockets, recovery anchors, scenery, and physical contract sentinels.
`lostcities-profile.json` is copied into a generation runtime at
`config/lostcities/profiles/mecharena_sector01.json` before the one-time
headless generation pass.

Build the deterministic Forge asset JAR and receipt from the repository's
parent directory:

```powershell
python .\tools\build_mecharena_sector01.py
python .\tools\test_mecharena_sector01.py
```

The builder emits 308 explicit one-chunk Lost Cities buildings and parts—one
for every chunk in the 22×14 authored rectangle—plus a custom palette, palette
style, city style, world style, and one predefined city at chunk `(0,0)`. The
predefined city has a one-block radius intentionally: all authored chunks are
forced by their explicit predefined-building entries, while the tiny radius
and `cityChance=0` prevent a probabilistic stock-city halo outside the authored
rectangle.

The part source is generated as 73 vertical slices (`y=48..120`). Structure
void is used as Lost Cities hard-air, so generation clears inherited terrain
inside the authored part volume before placing the fixed voxel contract. No
loot, spawners, foliage, fluids, highways, railways, explosions, or ticking
decoration is present.
