# Operation Ashen Span publication readiness

> **BLOCKED — DO NOT UPLOAD, RELEASE, LAUNCH, OR QUALIFY.** No releasable RC6
> exists. The locked 6/6 player-ticket footprint exceeds the locked 680-chunk
> safety envelope. Publication work may resume only after an authoritative spec
> erratum, a newly regenerated candidate, and successful exact-pad one-build and
> six-build headless acceptance. See
> [the safety-envelope blocker](SAFETY_ENVELOPE_BLOCKER.md).

Checked against primary platform guidance on **2026-08-08**. This is an evidence
checklist, not legal advice or permission to upload. Every publication claim remains
unchecked.

## Current decision

**DO NOT UPLOAD OR RELEASE.** Immutable RC5 is a historical artifact whose no-player
smoke did not test normal player tickets. The intermediate `5a35ec3` collision fix is not
a releasable RC6. The project is not currently offline-qualified; the spec contradiction,
live human gates, and platform permission declarations are all incomplete.

## Modrinth requirements to resolve

Modrinth's June 23, 2026 process requires creators to resolve every external file in a
modpack's Permissions page before review. External content can be identified as
licensed, the uploader's own project, specially permitted, or not permitted; unresolved
files can leave a version withheld. See Modrinth's primary announcement:

- [Improving Modpack review delays](https://modrinth.com/news/article/modpack-permissions)
- [Obtaining modpack permissions](https://support.modrinth.com/en/articles/8797527-obtaining-modpack-permissions)
- [Content Rules](https://modrinth.com/legal/rules)
- [Modpacks on Modrinth](https://support.modrinth.com/en/articles/8802250-modpacks-on-modrinth)
- [Sharing modpacks](https://support.modrinth.com/en/articles/8797522-sharing-modpacks)

The help center says an exact file hosted on Modrinth may be used in a Modrinth
modpack. That covers the nine exact indexed downloads only after their version IDs are
rechecked. It does not automatically classify binaries and resource packs embedded in
`overrides/`; those require explicit origin and permission evidence.

## Fail-closed checklist

- [ ] An authorized erratum resolves the locked 680-chunk versus genuine 6/6 conflict.
- [ ] A newly regenerated candidate passes exact-pad one-build and six-build headless
      acceptance with no forbidden chunk generation.
- [ ] A finalized live-validation receipt has status `pass` for the exact candidate,
      and its independently recorded SHA-256 still passes `status --receipt-sha256`.
- [ ] The candidate-tree SHA-256 comes from a successful independent reconstruction
      and still authenticates all eleven candidate files during qualification.
- [ ] All six Garage Fleet builds completed in 10–13 minutes with human balance review.
- [ ] Controls, visuals, audio, FPS/frame pacing, recovery, compatibility, and soak pass.
- [ ] Every MRPack indexed project/version still resolves to the recorded exact file.
- [ ] Every embedded external file is entered on Modrinth's Permissions page with a
      source link and the correct `License`, `Your project`, or `Special permission`
      basis.
- [ ] Pomkots Mechs fork/modification provenance and MIT license are supplied.
- [ ] Lost Cities 7.4.13 origin and MIT license are supplied.
- [ ] The original Sector 01 asset and original Cold Ruin presentation files are
      declared by the person authorized to make the `Your project` representation.
- [ ] Modified Pomkots texture packs include upstream attribution and MIT modification
      notices.
- [ ] Architectury/Cloth/GeckoLib server-overlay redistribution notices and license
      texts are complete.
- [ ] Xaero's Minimap and World Map remain links to exact Modrinth-hosted files; their
      binaries are not moved into overrides or the server overlay.
- [ ] Project title, summary, description, dependencies, client/server flags, license,
      and external links are accurate and mutually consistent.
- [ ] The page plainly says one level, requires Minecraft Java, and is not standalone or
      endorsed by Mojang/Microsoft/Modrinth.
- [ ] A clean import and download test passes immediately before upload.
- [ ] Release artifacts are rebuilt under a new immutable candidate ID; RC5 is not
      edited in place.
- [ ] Operator explicitly authorizes the upload after reviewing the Permissions page.

## Minecraft terms and branding

Before publication, recheck the current [Minecraft EULA](https://www.minecraft.net/eula)
and [Minecraft Usage Guidelines](https://www.minecraft.net/usage-guidelines). Do not
ship Minecraft or Forge runtime binaries as if this were a standalone game, imply
official endorsement, or hide the requirement for a legitimate Java Edition account.

## Evidence that does not exist yet

There is currently no approved screenshot set, trailer, public project page, moderation
submission, ownership declaration, special-permission attachment, hardware
recommendation, compatibility list, live balance result, or release date. Blank means
unknown; it must not be rewritten as pass.
