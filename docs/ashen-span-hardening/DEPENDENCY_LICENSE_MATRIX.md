# Operation Ashen Span dependency and license matrix

> **BLOCKED distribution status.** This inventory does not authorize launch,
> qualification, packaging, or publication. No releasable RC6 exists because the
> locked 6/6 player-ticket footprint exceeds the locked 680-chunk safety envelope.
> Resume distribution work only after an authoritative spec erratum, regenerated
> candidate, and successful exact-pad one-build and six-build headless acceptance.
> See [the safety-envelope blocker](SAFETY_ENVELOPE_BLOCKER.md).

Inventory date: **2026-08-08**. This matrix records local package metadata and exact
Modrinth project/version identities; it is not a legal opinion. Platform permissions
must still be completed before upload.

## Client dependencies referenced by `modrinth.index.json`

These nine binaries are downloaded from their exact Modrinth version URLs and are not
embedded in the MRPack. The permission basis is “exact file hosted on Modrinth,” as
described in Modrinth's [modpack permissions guide](https://support.modrinth.com/en/articles/8797527-obtaining-modpack-permissions).

| Project | Project / version | Exact file | License reported by Modrinth API on 2026-08-08 |
|---|---|---|---|
| Architectury API | `lhGA9TYQ` / `1MKTLiiG` | `architectury-9.2.14-forge.jar` | LGPL-3.0-only |
| Cloth Config | `9s6osm5g` / `t8TXrZvZ` | `cloth-config-11.1.136-forge.jar` | LGPL-3.0-only |
| Embeddium | `sk9rgfiA` / `UTbfe5d1` | `embeddium-0.3.31+mc1.20.1.jar` | LGPL-3.0-only |
| FerriteCore | `uXXizFIs` / `DG5Fn9Sz` | `ferritecore-6.0.1-forge.jar` | MIT |
| GeckoLib | `8BmcQJ2H` / `RBA7lJaW` | `geckolib-forge-1.20.1-4.4.9.jar` | MIT |
| Leawind's Third Person | `S3D3QF0M` / `oYVH29Z4` | `leawind_third_person-v2.1.0-mc1.20.1-forge.jar` | MIT |
| RenderScale | `Va8PJBFX` / `PJWr63qK` | `renderscale-1.3.8-forge+1.20.1.jar` | MIT |
| Xaero's Minimap | `1bokaNcj` / `nqVwHuEZ` | `xaerominimap-forge-1.20.1-26.2.0.jar` | All Rights Reserved |
| Xaero's World Map | `NcUtCpym` / `1yUPuPrU` | `xaeroworldmap-forge-1.20.1-1.42.0.jar` | All Rights Reserved |

The two Xaero binaries must remain exact hosted references. “Hosted file may be used in
a Modrinth modpack” is the recorded platform permission route; All Rights Reserved is
not permission to embed, mirror, modify, or redistribute those binaries independently.

## Embedded runtime content

| Component | Where embedded | Exact identity | Local license evidence / required closure |
|---|---|---|---|
| Pomkots Mechs mp.25 intermediate collision-fix JAR (not RC6) | Diagnostic only; not approved for client/server packaging | SHA-256 `0263191D695C2CBB136B883CC62DAF1354C1B2013FFCCEDDDE54CE9DD63600F4`; runtime source `5a35ec3d9a69fdd4d88ed7a0b21b28bc1b18ecfb` | JAR declares MIT and includes upstream `LICENSE`; retain fork/modification provenance. It does not resolve the safety-envelope blocker and is not releasable. Immutable RC5 predecessor SHA-256 is `29D3295D47CB3CD6744BAE98AFB404E5CFC87F94E26B0556A1120A5B42744213`. |
| Lost Cities 7.4.13 | Client and server | SHA-256 `DA5AE1B0C0D0C8066F2971C9ADB57D18657C4844AAD6CBA4F9F07F7946E30A95` | MIT; JAR has no license member, so the package notice reproduces the MIT text and links upstream. |
| `mecharena_sector01` asset | Client and server | SHA-256 `E1AC026BC07966803C5F3AFCF455A0B6B7F924F4131C52600355944A5CBBEFA9` | Original project content plus Lost Cities dependency notice; authorized uploader must make the ownership declaration. |
| Architectury 9.2.14 | Server overlay | SHA-256 `218B471D0B8A1F6CDA14CFC1BEB9EEB0DF54304500ACC6C5613D9B88EC65D9AF` | Metadata declares LGPL-3.0-only; JAR has no license member. RC6 must add pinned LGPL-3.0 text, attribution, and source link. |
| Cloth Config 11.1.136 | Server overlay | SHA-256 `1E895E85CF5B1E1905EF3178EC155C8BADFE22A1577B92C09143A5AA1F4CE0F2` | Metadata declares LGPL-3.0-only and JAR contains `LICENSE.md`; RC6 also carries the canonical license bundle. |
| GeckoLib 4.4.9 | Server overlay | SHA-256 `5D5FA42A52F11A7389B3FB1739EFCFC6957FF8236872A71382FB67CFF58B8FA2` | Metadata declares MIT and JAR contains `LICENSE`; retain attribution/source link. |
| Cold Ruin shader/resource pack | Client overrides | Exact member of the immutable MRPack | Project notice says original MIT content and includes its source/license notice. Authorized uploader must confirm ownership. |
| Forged Steel Materials | Client overrides | Exact member of the immutable MRPack | Modified Pomkots textures under MIT; retain original-author credit, modification notice, source path, and pinned source hash. |
| Forged Chassis | Client overrides | Exact member of the immutable MRPack | Modified Pomkots textures under MIT; retain license, source tag/commit/blob, credit, and modification notice. |

## Planned post-erratum RC6 license bundle contract

A future RC6 must carry deterministic, verifier-required files for:

- LGPL-3.0-only text and source/attribution for Architectury and Cloth Config;
- MIT text/source/attribution for GeckoLib, Lost Cities, and Pomkots Mechs;
- the Sector 01 and Cold Ruin provenance statements already present in source;
- modification notices for the two Pomkots-derived resource packs.

The bundle does not change the terms of any component. It must not claim ownership of
third-party work or treat an open-source license as a trademark endorsement.

## World hash identities

- Raw curated tree: `537AF7F1AB05B5070CED69697CEA04330E89177C9E7B810DA60BAE24A88A1223`
  under builder root `cold_ruin_sector_01/`.
- Package-rooted tree: `976E4F5B3BC4C4E2F9B552D00AFA6277BDD1D0436BF56A8A476C6F53D8E190EB`
  under runtime root `saves/cold_ruin_sector_01/`.

The different canonical path prefixes produce different tree hashes for the same ten
world files. Neither value is a binary license identifier.
