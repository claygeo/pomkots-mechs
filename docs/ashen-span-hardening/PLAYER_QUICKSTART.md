# Operation Ashen Span player quickstart

> **BLOCKED — do not import, launch, or play RC5 or any proposed RC6 package.**
> A normal player at the required 6/6 distance generates FULL chunks outside the
> locked 680-chunk safety envelope. This guide is retained only as a future
> procedure. It becomes usable after an authoritative spec erratum, a regenerated
> candidate, and successful exact-pad one-build and six-build headless acceptance.
> See [the safety-envelope blocker](SAFETY_ENVELOPE_BLOCKER.md).

Operation Ashen Span is one 10–13 minute single-player industrial bridge assault for
Minecraft Java Edition 1.20.1 Forge. The package is an `.mrpack`, not a standalone
executable. It contains one authored level, six selectable mechs, Gatekeeper R-01, a
service checkpoint, and the Span Warden finale. It has no campaign, crafting,
progression, or endless mode.

Status: suspended future installation/control guide. No current package is approved for
launch, and live play remains unqualified.

## Install into a fresh profile

Do not perform these steps against RC5 or the intermediate collision-fix artifact. They
document the intended post-blocker flow only.

1. Use a launcher that imports Modrinth packs, such as the Modrinth App or Prism
   Launcher, and a legitimate Minecraft Java account.
2. Choose **Import from file** and select the exact Operation Ashen Span `.mrpack`.
3. Create a new profile. Do not merge it into an existing modpack or copy an old
   `mods`, `config`, or `saves` directory over it.
4. Keep Minecraft at `1.20.1`, Forge at `47.3.3`, and Java at 17. Do not let the
   launcher substitute a newer Minecraft/Forge line.
5. Confirm the launcher finishes every hosted dependency download before starting.

If you are qualifying a candidate, hash it first and use the evidence protocol. Do not
rename or edit the candidate to make an import succeed.

## First 60 seconds

This section is disabled until a newly regenerated candidate passes the required
headless acceptance gates.

1. Start the fresh profile and wait for the main menu; do not add mods after launch.
2. Select **Singleplayer** and open `cold_ruin_sector_01`.
3. At the Garage, choose one of the six clickable Garage Fleet builds. If the chat card
   is not visible, run `/arena solo start 1` for Vanguard. Builds 1–6 are Vanguard,
   Siege, Skirmisher, Artillery, Duelist, and Trooper.
4. Wait for the mech to deploy and mount. Follow the objective text and bridge route;
   enemies reveal by authored phase rather than endless waves.
5. Run `/arena solo status` if the current objective is unclear.

The mission validates its exact map and safety settings before changing player or gate
state. A startup error is a package/profile problem; do not generate a replacement
world.

## Controls

```text
Move                         W A S D
Jump / boost                 Space
Dash                         Left Ctrl
Fire right-hand weapon       Left Mouse
Fire left-hand weapon        Right Mouse
Right shoulder weapon        P
Left shoulder weapon         O
Switch weapon mode           Y
Lock-on / track sight target Hold U
```

Run `/mechhelp` at any time to show the in-game control card again. The mech is your
health bar; losing it during this solo mission is defeat.

## Retry, stop, and preserve evidence

- `/arena solo retry` restarts the selected build using its recorded seed.
- `/arena solo stop` ends the run and restores owned mission state.
- After a defect, stop playing on that copy and preserve `logs/latest.log`, the newest
  `crash-reports/` file if present, a screenshot, build number, phase, and exact repro
  steps. Do not attach account names, server addresses, or chat history.

Use a fresh imported profile for a clean retest. Never repair the packaged world by
deleting regions or allowing new terrain generation.

## Known boundaries

- This is one vertical-slice level, not a full standalone game or campaign.
- The current bundled save starts with 680 FULL chunks, but it is not bounded under
  genuine 6/6 player tickets and therefore is blocked.
- Balance, visual/audio quality, controls, FPS, compatibility, and soak remain pending
  until recorded by a human against the exact candidate.
- The matched server overlay is optional and is not a complete Forge installation; see
  `SERVER_OPERATOR_GUIDE.md` before using it.
