# Operation Ashen Span dedicated-server operator guide

> **BLOCKED — do not install, start, join, expose, or qualify RC5 or any proposed
> RC6 server.** Registering the required survival player at genuine 6/6 creates
> FULL chunks outside the locked 680-chunk safety envelope. This guide is a future
> procedure only. An authoritative spec erratum, regenerated candidate, and
> successful exact-pad one-build and six-build headless acceptance are required
> first. See [the safety-envelope blocker](SAFETY_ENVELOPE_BLOCKER.md).

The server artifact is a matched overlay for one Operation Ashen Span level. It is not
a complete Forge server, installer, hosted service, or general multiplayer pack. The
packaged contract is Minecraft `1.20.1`, Forge `47.3.3`, Java 17, one bounded save,
`max-players=1`, `view-distance=6`, and `simulation-distance=6`.

Status: suspended future operator procedure. No current Ashen Span candidate is approved
for server launch or player connection.

## Install safely

Do not perform these steps with RC5 or the intermediate collision-fix artifact. They are
retained to document the intended post-blocker installation flow.

1. Create a new empty server directory on a local NTFS volume. Never install over an
   existing world, RC5, RC6, or mp.24 server.
2. Install the official Forge `1.20.1-47.3.3` server into that directory with Java 17.
   A typical installer invocation is:

   ```powershell
   java -jar .\forge-1.20.1-47.3.3-installer.jar --installServer
   ```

3. Verify the server-overlay SHA-256 against its candidate `SHA256SUMS.txt`, then
   extract the overlay into the new Forge root. Preserve its `mods`, `config`,
   `saves/cold_ruin_sector_01`, and `server.properties` paths exactly.
4. Read the [Minecraft EULA](https://www.minecraft.net/eula). Set `eula=true` in
   `eula.txt` only if the operator accepts it. The package never accepts it for you.
5. Keep `online-mode=true` and `white-list=true`. Start once with `run.bat nogui`, wait
   for `Done`, then add the one authorized player from the server console:

   ```text
   whitelist add ExactMinecraftName
   whitelist list
   ```

6. From the server console run `arena solo validate`. It must report the authenticated
   Sector 01 contract, 680 full chunks, and 47 physical runtime markers without
   generating terrain.

Do not enable RCON, query, command blocks, natural spawning, or block destruction for
this candidate. Do not raise view/simulation distance above six.

## Start and stop

Disabled while blocked. A joining player activates the vanilla ticket path that proves
the current world contract cannot remain bounded.

Use the Forge-generated script with `nogui`; the overlay deliberately supplies no
launcher or account credentials.

```powershell
.\run.bat nogui
```

Join only after the console prints `Done`. The player starts the mission with the
Garage card or `/arena solo start <1-6>`. To shut down, use these console commands and
wait for the Java process to exit:

```text
save-all flush
stop
```

Never close the terminal or copy active region files as a normal shutdown procedure.

## Backup and rollback

Before first live qualification and before every destructive recovery exercise:

1. Stop cleanly and confirm no Java server process remains.
2. Copy the whole server directory to a new, dated backup directory.
3. Record hashes of the candidate overlay and the four region files.
4. Keep the backup read-only during the run.

Rollback means stopping the current server and starting a new directory restored from
one complete matched backup. Do not overlay RC5 and RC6, mix mp.24/mp.25 JARs, or copy
only selected region files. If live validation finds a defect, preserve the failed
copy and its logs; fixes belong in a new candidate.

## Troubleshooting

| Symptom | Check and action |
|---|---|
| Java class-version or early JVM failure | `java -version` must report a 64-bit Java 17 runtime used by `run.bat`. |
| Server stops for EULA | Read the linked EULA; set `eula=true` only after acceptance. |
| Missing-mod/dependency error | Re-extract the matched overlay into a clean Forge 47.3.3 root; do not download approximate versions. |
| `Failed to verify username` or join rejected | Confirm network access, `online-mode=true`, the exact account name, and `whitelist list`. Do not disable authentication on a public network. |
| Player is not whitelisted | Run `whitelist add ExactMinecraftName`, then `whitelist reload`. |
| Wrong level or new terrain appears | Stop immediately. `level-name` must be `saves/cold_ruin_sector_01`; restore a clean backup rather than generate a substitute. |
| Mission rejects map/profile/settings | Verify candidate hashes, the one Lost Cities profile, view/simulation 6/6, and both block-destruction flags off. |
| Server hangs before `Done` | Preserve `logs/latest.log` and any crash report. Do not repeatedly kill and restart the same writable world. |
| Mission state survives a failed run | Run `/arena solo stop` if the player is connected, stop cleanly, preserve evidence, and restore the known backup for the next test. |

The operator should not expose this single-player slice as a public server. Publication,
security hardening for internet hosting, capacity testing, and live compatibility are
separate gates.
