package grcmcs.minecraft.mods.pomkotsmechs.arena;

import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.EntityEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.event.events.common.TickEvent;
import grcmcs.minecraft.mods.pomkotsmechs.PomkotsMechs;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import grcmcs.minecraft.mods.pomkotsmechs.entity.vehicle.PomkotsVehicle;
import grcmcs.minecraft.mods.pomkotsmechs.entity.vehicle.custom.Pmvc01Entity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server-side arena match system. All state lives here and is driven purely by
 * Architectury common events (no client classes, no packets, no mixins).
 *
 * <p>There is one logical server per process, so the match state is held in
 * static fields and reset whenever a server (re)starts.
 */
public final class ArenaManager {
    private ArenaManager() {
    }

    // Roster the arena is allowed to spawn. pmv03/pmv03p/pmvc01 are excluded.
    public static final List<String> ROSTER = List.of("pmv01", "pmv01b", "pmv02");
    public static final String DEFAULT_MECH = "pmv01";

    private static final int MIN_FIGHTERS = 2;
    private static final int MIN_PADS = 2;
    private static final int AUTO_START_DELAY = 200;   // DUEL: ticks queue must stay ready before auto-start
    private static final int ROYALE_AUTO_START_DELAY = 600; // ROYALE: sustained-min-players auto-start delay (30s)
    private static final int COUNTDOWN_LENGTH = 200;   // 10s
    private static final int ENDING_LENGTH = 100;      // 5s wind-down
    private static final int MATCH_TIMEOUT = 8 * 60 * 20; // 8 minutes of multiplayer ACTIVE
    private static final int SOLO_MATCH_TIMEOUT = 20 * 60 * 20; // hard safety cap; normal runs finish much sooner
    private static final double BOUNDS_RADIUS = 150.0; // horizontal blocks from center
    private static final double SOLO_BOUNDS_RADIUS = 160.0;
    private static final int SOLO_PROJECTILE_CAP = 48;
    private static final int BOUNDS_GRACE = 200;       // ticks allowed outside before elimination
    private static final int SPECTATE_HEIGHT = 30;

    // Royale tuning.
    private static final int CAGE_LENGTH = 200;        // 10s the glass cages hold fighters before dropping
    private static final double SKY_CAGE_MIN_RING_RADIUS = 24.0; // floor for the per-match cage ring radius
    private static final double SKY_CAGE_SPACING = 8.0; // min arc distance between adjacent cage slots
    private static final int SKY_CAGE_Y_OFFSET = 60;   // cages float at least this many blocks above center Y
    private static final int SKY_CAGE_HALF = 2;        // 5x5 footprint (center +/- 2)
    private static final int SKY_CAGE_WALL_HEIGHT = 4; // wall blocks; glass roof sits one above the top wall
    private static final int SKY_CAGE_HEADROOM = 8;    // build-height clamp margin above the roof
    private static final int PVE_MOBS_PER_WAVE = 6;    // hostiles per PvE wave at GRACE end (light=1, heavy=2)
    private static final int SCATTER_MIN_DIST = 30;    // nearest a scattered mech may spawn
    private static final double ROYALE_BOUNDS_FACTOR = 1.25; // bounds = royale radius * this
    private static final int PREP_CHUNKS_PER_TICK = 2; // scatter chunk-gen budget during countdown
    private static final double ZONE_SHRINK_PER_TICK = 0.05; // minimum shrink rate; real rate computed per match
    private static final double ZONE_MIN_RADIUS = 15.0;      // the zone never shrinks below this
    private static final int ROYALE_HARD_CAP = MATCH_TIMEOUT + 6000; // absolute draw backstop (+5 min)

    static final String TAG_ARENA = "mecharena";
    static final String TAG_OWNER_PREFIX = "mecharena_owner_";
    static final String TAG_MATCH_PREFIX = "mecharena_match_";
    // PvE hostiles carry TAG_ARENA (so all existing cleanup/sweep paths reap them)
    // plus the current match tag (so the guards treat them as legitimate) plus this
    // marker, which keeps their deaths OUT of the loot-mech accounting.
    static final String TAG_PVE = "mecharena_pve";
    static final String TAG_SOLO = "mecharena_solo";
    static final String TAG_SOLO_PROJECTILE = "mecharena_solo_projectile";
    static final int ASHEN_SPAN_BUILD_COUNT = 6;
    // Hostile roster for the PvE hook: charging, gun, and spider mobs. All are
    // Monsters that autonomously target the nearest player (hostile to everyone).
    private static final List<String> PVE_ROSTER = List.of("pms01", "pms03", "pms05");
    private static final String PREFIX = "[Arena] ";

    private static ArenaState state = ArenaState.IDLE;
    private static final LinkedHashMap<UUID, String> queue = new LinkedHashMap<>();
    private static final List<Fighter> fighters = new ArrayList<>();
    private static final SpawnDirector spawnDirector = new SpawnDirector();
    private static final AshenSpanDirector ashenSpanDirector = new AshenSpanDirector();
    private static final Set<UUID> soloProjectileIds = new HashSet<>();
    private static int soloProjectileDrops = 0;

    // Retry remembers the last successful solo recipe but never persists it. A server
    // restart intentionally starts from a clean command rather than resurrecting a
    // partial encounter against a possibly changed datapack or world.
    private static UUID soloOwner;
    private static int lastSoloBuild = 0;
    private static long lastSoloSeed = 0L;
    private static boolean hasLastSoloRun = false;

    private static int idleTimer = 0;
    private static int countdownTicks = 0;
    private static int matchTicks = 0;
    private static int endingTicks = 0;
    private static int sweepTimer = 0;
    private static int cageTicks = 0;   // royale only: ticks the cages still hold fighters before dropping
    private static int graceTicks = 0;  // royale only: ticks left in the no-combat GRACE window after the drop

    // First-mount control-card tracker. In-memory only (per server session):
    // cleared on every (re)start so returning players see the card once per boot.
    private static final Set<UUID> mountHinted = new HashSet<>();

    // Geometry of the currently running match.
    private static double centerX;
    private static double centerY;
    private static double centerZ;
    private static net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> arenaDimension;

    // Mode + bounds of the currently running match, snapshotted so a mid-match
    // config change can never perturb a live match (mode changes are IDLE-only
    // anyway). matchMode is set in beginCountdown so it is already correct for
    // the whole COUNTDOWN + ACTIVE window that the seam checks read it in.
    private static Mode matchMode = Mode.DUEL;
    private static double matchBoundsRadius = BOUNDS_RADIUS;
    // Per-royale-match id, claimed from the PERSISTED sequence in ArenaData (and
    // force-flushed before any scatter spawns), so a restart can never hand out
    // an id that crash-leftover mechs in unloaded chunks still carry. Scattered
    // mechs are tagged with it; the stray sweeps treat only the CURRENT id as
    // legitimate.
    private static int matchId = 0;
    // Per-match snapshots taken at startMatch (like matchMode/matchBoundsRadius) so
    // a mid-match config change can never perturb a live match. matchRingCount is
    // the sky-cage ring divisor; the grace/pve snapshots freeze the GRACE window
    // length and the PvE tier for the whole match.
    private static int matchRingCount = 0;
    private static int matchGraceDuration = 1200;
    private static RoyalePve matchPve = RoyalePve.OFF;
    // Per-match sky-cage geometry, computed once at startMatch so the deploy teleport
    // and the cage build read ONE shared value. The ring radius scales with fighter
    // count so adjacent slots stay >= SKY_CAGE_SPACING apart, and the cage base Y is
    // lifted above the tallest terrain under any slot so no cage clips a tower.
    private static double matchRingRadius = SKY_CAGE_MIN_RING_RADIUS;
    private static int matchCageBaseY = 0;
    // Snapshot of the min-player floor for the running COUNTDOWN (workstream E): the
    // countdown cancels below THIS, not below the bare MIN_FIGHTERS constant, so a
    // royale that auto-armed at its configured min still needs that many to start.
    private static int matchMinPlayers = MIN_FIGHTERS;
    // Scatter plan computed at countdown start: spawn points plus the deduped
    // set of chunks they need. Chunks are generated a few per tick DURING the
    // countdown so startMatch never forces dozens of Lost Cities chunks in one
    // tick (watchdog risk). Cleared by resetToIdle.
    private static final List<int[]> scatterPoints = new ArrayList<>();
    private static final List<long[]> scatterChunksToPrep = new ArrayList<>();
    // Live current-match loot mech count, tracked at spawn/death instead of
    // scanning loaded entities (unloaded outer chunks would undercount).
    private static int royaleMechsAlive = 0;
    // Endgame zone shrink rate, computed per match from the starting radius so
    // every legal radius closes to ZONE_MIN_RADIUS before the hard cap.
    private static double zoneShrinkPerTick = ZONE_SHRINK_PER_TICK;

    /** Wired from the mod's common init. Registers all server-side event hooks. */
    public static void init() {
        TickEvent.SERVER_POST.register(ArenaManager::onServerTick);
        EntityEvent.LIVING_DEATH.register(ArenaManager::onLivingDeath);
        EntityEvent.ADD.register(ArenaManager::onEntityAdd);
        PlayerEvent.PLAYER_QUIT.register(ArenaManager::onPlayerQuit);
        PlayerEvent.PLAYER_JOIN.register(ArenaManager::onPlayerJoin);
        PlayerEvent.PLAYER_RESPAWN.register(ArenaManager::onPlayerRespawn);
        LifecycleEvent.SERVER_STARTED.register(ArenaManager::onServerStarted);
        LifecycleEvent.SERVER_STOPPING.register(ArenaManager::onServerStopping);
        ArenaCommands.register();
    }

    // ---------------------------------------------------------------------
    // Lifecycle events
    // ---------------------------------------------------------------------

    private static void onServerStarted(MinecraftServer server) {
        // Fresh boot: drop any in-memory state and sweep leftover arena mechs
        // that a crash may have left behind.
        resetToIdle();
        hasLastSoloRun = false;
        lastSoloBuild = 0;
        lastSoloSeed = 0L;
        queue.clear();
        fighters.clear();
        mountHinted.clear();
        killTaggedEntities(server);
        // A crash mid-royale can leave a glass cage in the city; the recorded
        // positions outlive the process in ArenaData, so remove them now.
        removeCageBlocks(server);
        MissionGateLedger.restoreAll(server);
    }

    private static void onServerStopping(MinecraftServer server) {
        // Restore everyone BEFORE shutdown saves playerdata; records of players
        // we cannot heal right now (dead/offline) stay persisted for the
        // join/respawn healers on the next boot.
        if (state != ArenaState.IDLE) {
            broadcast(server, "Server stopping — match cancelled.");
        }
        cleanup(server);
    }

    private static void onPlayerQuit(ServerPlayer player) {
        UUID uuid = player.getUUID();
        queue.remove(uuid);
        // Drop this player's rolling lock-on rate window so the server-authoritative
        // lock limiter's map never leaks UUIDs across reconnects (workstream F).
        PomkotsMechs.pruneLockRate(uuid);

        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        // A pending record means this player still owes a restore. Whatever the
        // state (ACTIVE/ENDING/even IDLE after a partial cleanup), heal a living
        // player on the way out. A death-screen player keeps the journal for the
        // respawn hook, because teleporting the obsolete dead entity can lose it.
        RestoreRecord rec = ArenaData.get(server).getPendingRestore(uuid);
        if (rec == null) {
            return;
        }
        if (state == ArenaState.ACTIVE) {
            Fighter f = findFighterByUuid(uuid);
            if (f != null && !f.eliminated) {
                // Disconnect counts as elimination. Don't move them to spectator
                // (they are leaving). The win check runs on the next tick.
                eliminate(server, f, false);
            }
        }
        if (canRestorePlayerImmediately(player.isAlive())) {
            restorePlayer(server, player, rec);
        }
    }

    private static void onPlayerJoin(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        // Heals crash-mid-match relogs (even days later): the record outlived the
        // match in ArenaData, so apply it once the rejoined player is alive. A
        // death-screen reconnect remains journaled until PLAYER_RESPAWN.
        RestoreRecord rec = ArenaData.get(server).getPendingRestore(player.getUUID());
        if (rec != null && canRestorePlayerImmediately(player.isAlive())) {
            restorePlayer(server, player, rec);
        }
        sendGarageFleetCardIfSectorReady(player);
    }

    private static void onPlayerRespawn(ServerPlayer player, boolean conqueredEnd) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        UUID uuid = player.getUUID();
        if (state == ArenaState.ACTIVE || state == ArenaState.ENDING) {
            Fighter f = findFighterByUuid(uuid);
            if (f != null && f.eliminated) {
                if (ArenaData.get(server).getPendingRestore(uuid) == null) {
                    // Already healed (e.g. on quit) — the match no longer owns
                    // this player. Never re-capture them into spectator: there is
                    // no record left, so nothing would ever release them.
                    return;
                }
                // Died in-match and clicked respawn: keep them spectating the
                // rest of the match. Their pending record stays until match end.
                player.setGameMode(GameType.SPECTATOR);
                ServerLevel level = arenaDimension != null ? server.getLevel(arenaDimension) : null;
                if (level == null) {
                    level = server.overworld();
                }
                player.teleportTo(level, centerX, centerY + SPECTATE_HEIGHT, centerZ,
                        player.getYRot(), player.getXRot());
                return;
            }
            if (f != null) {
                // A CURRENT, non-eliminated fighter respawning mid-match: their
                // record is owned by the match — never consume it here during
                // ACTIVE, or a later eliminate() would strand them. Treat the
                // respawn as their elimination and let the normal path handle it.
                if (state == ArenaState.ACTIVE) {
                    eliminate(server, f, true);
                    return;
                }
                // ENDING: the outcome can no longer change, so the record is
                // safe to consume now instead of leaving them at their bed.
                RestoreRecord rec = ArenaData.get(server).getPendingRestore(uuid);
                if (rec != null) {
                    restorePlayer(server, player, rec);
                }
                return;
            }
        }
        // No match running (or not an eliminated fighter): if a restore is still
        // owed — e.g. death-screen respawn that landed after cleanup — apply it.
        RestoreRecord rec = ArenaData.get(server).getPendingRestore(uuid);
        if (rec != null) {
            restorePlayer(server, player, rec);
        }
    }

    /**
     * Catches arena mechs the boot sweep missed (entities in unloaded/forceloaded
     * chunks that only load after {@code SERVER_STARTED}). Cancels the addition of
     * any tagged mech that does not belong to a live match. Non-arena entities and
     * legitimate in-match mechs pass through untouched.
     */
    private static EventResult onEntityAdd(Entity entity, Level level) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return EventResult.pass();
        }
        ArenaHooks.AddDecision authored = ArenaHooks.admitAdded(entity, serverLevel);
        if (authored == ArenaHooks.AddDecision.ACCEPT) {
            return EventResult.pass();
        }
        if (authored == ArenaHooks.AddDecision.REJECT) {
            return EventResult.interruptFalse();
        }
        // A tagged projectile is being re-added from saved/chunk state, never
        // freshly fired. Reject it before owner classification so an old shot
        // from the same pilot cannot be relabelled into a later run.
        if (entity.getTags().contains(TAG_SOLO_PROJECTILE)) {
            soloProjectileIds.remove(entity.getUUID());
            return EventResult.interruptFalse();
        }
        // Projectiles are short-lived, but some missiles outlive the entire ENDING
        // window. Attach them to the solo match at creation so outcome/stop/retry
        // teardown cannot leave a hostile shot behind. Ownership is exact: either
        // the solo pilot (player-mech weapons) or a current tagged enemy. Unrelated
        // arrows/projectiles in the same dimension remain untouched.
        if (entity instanceof Projectile projectile
                && matchMode == Mode.SOLO
                && !ArenaHooks.isActive()
                && isSoloCombatProjectileOwner(projectile.getOwner())) {
            // Once an outcome is decided, the still-mounted pilot gets a visual
            // wind-down but cannot create shots that outlive the match. Wrong-
            // dimension combat shots are rejected for the same reason.
            if (state != ArenaState.ACTIVE || arenaDimension == null
                    || !serverLevel.dimension().equals(arenaDimension)) {
                return EventResult.interruptFalse();
            }
            if (soloProjectileIds.size() >= SOLO_PROJECTILE_CAP) {
                pruneSoloProjectiles(serverLevel);
                if (soloProjectileIds.size() >= SOLO_PROJECTILE_CAP) {
                    soloProjectileDrops++;
                    return EventResult.interruptFalse();
                }
            }
            entity.addTag(TAG_ARENA);
            entity.addTag(TAG_MATCH_PREFIX + matchId);
            entity.addTag(TAG_SOLO);
            entity.addTag(TAG_SOLO_PROJECTILE);
            soloProjectileIds.add(entity.getUUID());
            return EventResult.pass();
        }
        if (!entity.getTags().contains(TAG_ARENA)) {
            return EventResult.pass();
        }
        // ROYALE scattered mechs are unowned gear, so the DUEL identity check
        // can't recognise them. During a live royale match legitimacy is by the
        // current match tag instead; anything tagged for an older match (or with
        // no match tag at all) is culled exactly as a DUEL stray would be.
        if (matchMode == Mode.ROYALE
                && (state == ArenaState.ACTIVE || state == ArenaState.COUNTDOWN)) {
            if (entity.getTags().contains(TAG_MATCH_PREFIX + matchId)) {
                return EventResult.pass();
            }
            return EventResult.interruptFalse();
        }
        // SOLO enemies are stricter than royale loot: a current match tag alone is
        // insufficient. The UUID must still be owned by the live director so an
        // unloaded unit that was replaced by leash/stuck recovery cannot return later
        // and grow the encounter without bound.
        if (matchMode == Mode.SOLO
                && (state == ArenaState.ACTIVE || state == ArenaState.COUNTDOWN)
                && entity.getTags().contains(TAG_PVE)) {
            boolean current = entity.getTags().contains(TAG_MATCH_PREFIX + matchId);
            return current
                    && arenaDimension != null
                    && serverLevel.dimension().equals(arenaDimension)
                    && spawnDirector.owns(entity.getUUID())
                    ? EventResult.pass()
                    : EventResult.interruptFalse();
        }
        // A tagged arena mech is being added. In ANY state, only a live fighter's
        // current mech may enter the world. Ownership requires IDENTITY, not just
        // the owner tag: deployFighter sets f.mech before addFreshEntity, and
        // findFighterByMech heals f.mech for a legitimately chunk-reloaded mech,
        // so f.mech == entity holds in both valid cases. A crash-leftover
        // duplicate carrying the same owner tag while the real mech lives fails
        // the identity test and is culled.
        Fighter f = findFighterByMech(entity);
        if (f != null && !f.eliminated && f.mech == entity
                && (state == ArenaState.ACTIVE || state == ArenaState.COUNTDOWN)) {
            return EventResult.pass();
        }
        return EventResult.interruptFalse();
    }

    private static boolean isSoloCombatProjectileOwner(Entity owner) {
        if (owner == null) {
            return false;
        }
        if (soloOwner != null && soloOwner.equals(owner.getUUID())) {
            return true;
        }
        boolean current = owner.getTags().contains(TAG_SOLO)
                && owner.getTags().contains(TAG_MATCH_PREFIX + matchId);
        if (!current) {
            return false;
        }
        if (owner.getTags().contains(TAG_PVE)) {
            return spawnDirector.owns(owner.getUUID());
        }
        Fighter fighter = findFighterByMech(owner);
        return fighter != null && !fighter.eliminated && fighter.mech == owner;
    }

    static boolean isTrackedSoloProjectile(boolean inArenaDimension,
                                           boolean currentMatch,
                                           boolean trackedUuid) {
        return inArenaDimension && currentMatch && trackedUuid;
    }

    static boolean canRestorePlayerImmediately(boolean alive) {
        return alive;
    }

    private static void pruneSoloProjectiles(ServerLevel level) {
        String matchTag = TAG_MATCH_PREFIX + matchId;
        Iterator<UUID> iterator = soloProjectileIds.iterator();
        while (iterator.hasNext()) {
            Entity entity = level.getEntity(iterator.next());
            if (entity == null || entity.isRemoved()
                    || !entity.getTags().contains(TAG_SOLO_PROJECTILE)
                    || !entity.getTags().contains(matchTag)) {
                iterator.remove();
            }
        }
    }

    private static String soloProjectileStatus() {
        return "shots " + soloProjectileIds.size() + "/" + SOLO_PROJECTILE_CAP
                + (soloProjectileDrops > 0 ? ", capped " + soloProjectileDrops : "");
    }

    private static EventResult onLivingDeath(LivingEntity entity, DamageSource source) {
        if (state == ArenaState.ACTIVE) {
            MinecraftServer server = entity.getServer();
            if (server != null) {
                boolean currentMatchPve = entity.getTags().contains(TAG_PVE)
                        && entity.getTags().contains(TAG_MATCH_PREFIX + matchId);
                if (matchMode == Mode.SOLO && currentMatchPve) {
                    ashenSpanDirector.onDeath(entity);
                    return EventResult.pass();
                }

                // Terminalize a SOLO fighter death in the death event itself. If
                // we only mark the fighter eliminated, tickSolo() sees that flag
                // later and can no longer distinguish a destroyed pilot/frame
                // from a disconnect. Moving directly to ENDING also preserves the
                // required loss-before-victory ordering for same-tick double KOs.
                if (matchMode == Mode.SOLO) {
                    Fighter soloFighter = null;
                    boolean fighterPlayer = false;
                    boolean fighterMech = false;
                    if (entity instanceof ServerPlayer) {
                        soloFighter = findFighterByUuid(entity.getUUID());
                        fighterPlayer = soloFighter != null;
                    } else if (entity.getTags().contains(TAG_ARENA)
                            && entity.getTags().contains(TAG_MATCH_PREFIX + matchId)) {
                        soloFighter = findFighterByMech(entity);
                        fighterMech = soloFighter != null;
                    }
                    AshenSpanMissionModel.DefeatReason reason = classifySoloFighterDeath(
                            matchMode, currentMatchPve, fighterMech, fighterPlayer);
                    if (reason != null) {
                        eliminate(server, soloFighter,
                                reason == AshenSpanMissionModel.DefeatReason.MECH_DESTROYED);
                        String message = reason == AshenSpanMissionModel.DefeatReason.MECH_DESTROYED
                                ? "SOLO DEFEAT — your combat frame was destroyed."
                                : "SOLO DEFEAT — pilot destroyed.";
                        recordSoloDefeat(server, soloFighter, reason, message);
                        beginEnding(server);
                        return EventResult.pass();
                    }
                }
                if (entity.getTags().contains(TAG_ARENA)) {
                    if (matchMode == Mode.ROYALE) {
                        // Mechs are GEAR, not lives: a downed mech eliminates no
                        // one. The count is tracked at spawn/death rather than
                        // scanned, so mechs idling in unloaded chunks still count.
                        // PvE hostiles share TAG_ARENA + the match tag (so cleanup
                        // reaps them) but are NOT loot mechs, so their deaths must
                        // not touch the mech count.
                        if (entity.getTags().contains(TAG_MATCH_PREFIX + matchId)
                                && !entity.getTags().contains(TAG_PVE)) {
                            royaleMechsAlive = Math.max(0, royaleMechsAlive - 1);
                            broadcast(server, "A mech went down — " + royaleMechsAlive + " mechs remain.");
                        }
                    } else if (matchMode == Mode.DUEL) {
                        // DUEL: an arena mech died -> eliminate its owner.
                        Fighter f = findFighterByMech(entity);
                        if (f != null) {
                            eliminate(server, f, true);
                        }
                    }
                } else if (entity instanceof ServerPlayer) {
                    Fighter f = findFighterByUuid(entity.getUUID());
                    if (f != null) {
                        eliminate(server, f, true);
                    }
                }
            }
        }
        return EventResult.pass();
    }

    static AshenSpanMissionModel.DefeatReason classifySoloFighterDeath(
            Mode mode, boolean currentMatchPve, boolean fighterMech, boolean fighterPlayer) {
        if (mode != Mode.SOLO || currentMatchPve) {
            return null;
        }
        if (fighterPlayer) {
            return AshenSpanMissionModel.DefeatReason.PLAYER_DESTROYED;
        }
        if (fighterMech) {
            return AshenSpanMissionModel.DefeatReason.MECH_DESTROYED;
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // State machine (ticked at the end of every server tick)
    // ---------------------------------------------------------------------

    private static void onServerTick(MinecraftServer server) {
        // Global QoL, orthogonal to the match FSM: the first time a player pilots a
        // pomkots mech this session, hand them the control card. Runs in every state
        // (and reads no match config/phase), so it leaves DUEL flow untouched.
        tickMountHints(server);

        switch (state) {
            case IDLE -> tickIdle(server);
            case COUNTDOWN -> tickCountdown(server);
            case ACTIVE -> tickActive(server);
            case ENDING -> tickEnding(server);
        }
    }

    /**
     * Detects the first mech mount per player per server session and sends the
     * one-time control card. Cheap: a single {@code getVehicle()} check per online
     * player, gated by an in-memory UUID set so it fires exactly once.
     */
    private static void tickMountHints(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getVehicle() instanceof PomkotsVehicle
                    && mountHinted.add(player.getUUID())) {
                player.sendSystemMessage(mountHelpCard());
            }
        }
    }

    private static void tickIdle(MinecraftServer server) {
        // Periodic stray-mech sweep. The EntityEvent.ADD guard covers Forge chunk
        // loads, but on Fabric that event only fires for fresh spawns — this
        // loader-agnostic sweep is what actually reaps crash leftovers there.
        // Use the same 5s bound as ACTIVE so a cleanup leftover cannot roam for
        // the old 30s window after its chunk returns.
        if (++sweepTimer >= 100) {
            sweepTimer = 0;
            sweepStrayMechs(server);
        }
        ArenaData data = ArenaData.get(server);
        // SOLO is started explicitly by its owner and never waits on the multiplayer
        // queue. If a future datapack/admin persists SOLO as the ambient mode, leave
        // the idle queue disarmed rather than applying DUEL's two-player assumptions.
        if (data.getMode() == Mode.SOLO) {
            idleTimer = 0;
            return;
        }
        // ROYALE needs a center, not pads; DUEL needs pads. Anything else keeps the
        // auto-start timer disarmed.
        Mode mode = data.getMode();
        boolean venueReady = mode == Mode.ROYALE
                ? data.getRoyaleCenter() != null
                : data.getPads().size() >= MIN_PADS;
        if (!venueReady) {
            idleTimer = 0;
            return;
        }
        int viable = countViableQueued(server);
        if (mode == Mode.ROYALE) {
            // Two start gates: a full lobby (>= startAt) fires immediately; a viable
            // lobby (>= min) fires after a sustained idle delay. Below min disarms.
            int startAt = Math.max(MIN_FIGHTERS, data.getRoyaleStartAtPlayers());
            int min = Math.max(MIN_FIGHTERS, data.getRoyaleMinPlayers());
            // Either gate arms the SAME configured-min countdown floor: a full lobby
            // fires it immediately, a sustained viable lobby after the idle delay.
            if (viable >= startAt) {
                beginCountdown(server, min);
                return;
            }
            if (viable >= min) {
                idleTimer++;
                if (idleTimer >= ROYALE_AUTO_START_DELAY) {
                    beginCountdown(server, min);
                }
            } else {
                idleTimer = 0;
            }
        } else {
            // DUEL: unchanged — >= MIN_FIGHTERS sustained for AUTO_START_DELAY.
            if (viable >= MIN_FIGHTERS) {
                idleTimer++;
                if (idleTimer >= AUTO_START_DELAY) {
                    beginCountdown(server, MIN_FIGHTERS);
                }
            } else {
                idleTimer = 0;
            }
        }
    }

    /**
     * Queue members who could actually be deployed right now (online, alive, not
     * mid-disconnect). Dead/AFK queue entries must not arm a countdown that
     * startMatch would immediately abort.
     */
    private static int countViableQueued(MinecraftServer server) {
        int n = 0;
        for (UUID uuid : queue.keySet()) {
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            if (p != null && p.isAlive() && !p.hasDisconnected()) {
                n++;
            }
        }
        return n;
    }

    /** Discards every tagged mech that is not legitimate for the current match. */
    private static void sweepStrayMechs(MinecraftServer server) {
        boolean royaleLive = matchMode == Mode.ROYALE
                && (state == ArenaState.ACTIVE || state == ArenaState.COUNTDOWN);
        boolean soloLive = matchMode == Mode.SOLO
                && (state == ArenaState.ACTIVE || state == ArenaState.COUNTDOWN);
        for (ServerLevel level : server.getAllLevels()) {
            List<? extends Entity> tagged = level.getEntities(
                    EntityTypeTest.forClass(Entity.class),
                    e -> e.getTags().contains(TAG_ARENA));
            for (Entity e : tagged) {
                boolean legit;
                if (soloLive && ArenaHooks.isActive()
                        && e.getTags().contains(ArenaHooks.TAG_OWNED)) {
                    // Authored roots and descendants use the Ashen ownership graph,
                    // not SpawnDirector/soloProjectileIds. Check this first so the
                    // 100-tick stray sweep cannot erase a valid encounter or make a
                    // phase appear to reach exact zero five seconds after staging.
                    legit = ArenaHooks.isCurrentOwnedEntity(e, level);
                } else if (royaleLive) {
                    // ROYALE: scattered mechs are unowned; the current match tag
                    // is what keeps them from being reaped by our own sweep.
                    legit = e.getTags().contains(TAG_MATCH_PREFIX + matchId);
                } else if (soloLive && e.getTags().contains(TAG_SOLO_PROJECTILE)) {
                    legit = isTrackedSoloProjectile(
                            arenaDimension != null && level.dimension().equals(arenaDimension),
                            e.getTags().contains(TAG_MATCH_PREFIX + matchId),
                            soloProjectileIds.contains(e.getUUID()));
                } else if (soloLive && e.getTags().contains(TAG_PVE)) {
                    // A same-match SOLO tag is not enough: only director-owned UUIDs
                    // may survive. This catches replaced enemies on loaders where the
                    // ADD event does not fire for a chunk reload.
                    legit = arenaDimension != null
                            && level.dimension().equals(arenaDimension)
                            && e.getTags().contains(TAG_MATCH_PREFIX + matchId)
                            && spawnDirector.owns(e.getUUID());
                } else {
                    Fighter f = findFighterByMech(e);
                    legit = f != null && !f.eliminated && f.mech == e
                            && (state == ArenaState.ACTIVE || state == ArenaState.COUNTDOWN);
                }
                if (!legit) {
                    e.ejectPassengers();
                    e.discard();
                }
            }
        }
    }

    /**
     * @param minPlayers the viable-queue floor this countdown must keep to survive.
     *        Auto-start passes the mode's configured min (ROYALE: max(2, royaleMin);
     *        DUEL: 2); a force-start via /arena start passes the bare MIN_FIGHTERS so
     *        an admin can run a match at 2 regardless of the royale min-players tuning.
     */
    private static void beginCountdown(MinecraftServer server, int minPlayers) {
        state = ArenaState.COUNTDOWN;
        countdownTicks = COUNTDOWN_LENGTH;
        idleTimer = 0;
        matchMinPlayers = Math.max(MIN_FIGHTERS, minPlayers);
        // Lock in the mode for the whole match now (mode changes are IDLE-only),
        // so every seam that reads matchMode is correct across COUNTDOWN + ACTIVE.
        ArenaData data = ArenaData.get(server);
        matchMode = data.getMode();
        if (matchMode == Mode.ROYALE) {
            // Claim a fresh id from the persisted sequence and flush it to disk
            // BEFORE anything is tagged with it: after any crash, leftover mechs
            // always carry an id strictly below the next one handed out.
            matchId = data.claimMatchId();
            server.overworld().getDataStorage().save();
            planScatter(server, data);
        }
        broadcast(server, "Match starting in 10 seconds! (" + queue.size() + " queued)");
    }

    /**
     * Precomputes the royale scatter: fixed spawn points for this match plus the
     * DEDUPED set of chunks they touch, which tickCountdown then generates a few
     * per tick across the 10s countdown (200 ticks x budget >> worst-case 64
     * chunks, so the set is always exhausted before startMatch).
     */
    private static void planScatter(MinecraftServer server, ArenaData data) {
        scatterPoints.clear();
        scatterChunksToPrep.clear();
        ArenaPoint rc = data.getRoyaleCenter();
        if (rc == null) {
            return; // startMatch re-validates and cancels cleanly
        }
        ServerLevel level = server.getLevel(rc.dimensionKey());
        if (level == null) {
            return;
        }
        RandomSource rand = level.getRandom();
        double cx = Mth.floor(rc.x) + 0.5;
        double cz = Mth.floor(rc.z) + 0.5;
        double span = Math.max(0.0, data.getRoyaleRadius() - SCATTER_MIN_DIST);
        java.util.LinkedHashSet<Long> chunks = new java.util.LinkedHashSet<>();
        // Over-plan 3x the needed points: scatterMechs later PREFERS points whose
        // surface sits near the center's street level, so mechs land where a
        // player on foot can actually reach them instead of on tower roofs.
        for (int i = 0; i < data.getMechCount() * 3; i++) {
            double angle = rand.nextDouble() * 2.0 * Math.PI;
            double dist = SCATTER_MIN_DIST + rand.nextDouble() * span;
            int x = Mth.floor(cx + dist * Math.cos(angle));
            int z = Mth.floor(cz + dist * Math.sin(angle));
            scatterPoints.add(new int[]{x, z});
            chunks.add(((long) (x >> 4) << 32) | ((z >> 4) & 0xFFFFFFFFL));
        }
        for (long c : chunks) {
            scatterChunksToPrep.add(new long[]{c >> 32, (int) c});
        }
    }

    private static void tickCountdown(MinecraftServer server) {
        // Royale scatter prep: generate a few of the planned scatter chunks per
        // tick so startMatch never pays the whole chunk-gen bill in one tick.
        if (matchMode == Mode.ROYALE && !scatterChunksToPrep.isEmpty() && arenaLevelForPrep(server) != null) {
            ServerLevel level = arenaLevelForPrep(server);
            for (int i = 0; i < PREP_CHUNKS_PER_TICK && !scatterChunksToPrep.isEmpty(); i++) {
                long[] c = scatterChunksToPrep.remove(scatterChunksToPrep.size() - 1);
                level.getChunk((int) c[0], (int) c[1]);
            }
        }
        if (countViableQueued(server) < matchMinPlayers) {
            // Dropped below the floor this countdown was armed at (workstream E).
            // Cancel WITHOUT wiping the queue; auto-start re-arms once enough
            // players are queued again.
            broadcast(server, "Not enough players — countdown cancelled.");
            resetToIdle();
            return;
        }
        countdownTicks--;
        if (countdownTicks > 0 && countdownTicks % 20 == 0) {
            int secs = countdownTicks / 20;
            if (secs <= 5) {
                broadcast(server, "Match starts in " + secs + "...");
            }
        }
        if (countdownTicks <= 0) {
            startMatch(server);
        }
    }

    private static void startMatch(MinecraftServer server) {
        ArenaData data = ArenaData.get(server);
        Mode mode = matchMode; // locked in at beginCountdown

        // --- Venue setup seam. DUEL validates spawn pads; ROYALE needs a
        // center. Both end with arenaDimension/arenaLevel/center/matchBoundsRadius
        // set so the shared fighter loop and the match ticker are mode-agnostic.
        ServerLevel arenaLevel;
        double[] center;
        List<ArenaPoint> pads = null;
        if (mode == Mode.ROYALE) {
            ArenaPoint rc = data.getRoyaleCenter();
            if (rc == null) {
                broadcast(server, "Royale center is not set. Match cancelled.");
                abortMatch(server);
                return;
            }
            arenaDimension = rc.dimensionKey();
            arenaLevel = server.getLevel(arenaDimension);
            if (arenaLevel == null) {
                broadcast(server, "Royale dimension is not loaded. Match cancelled.");
                abortMatch(server);
                return;
            }
            // Snap X/Z to the block center so the spawn ring and the cage grid
            // share one canonical origin — an off-center admin position could
            // otherwise place a cage wall through a fighter's bounding box.
            center = new double[]{Mth.floor(rc.x) + 0.5, rc.y, Mth.floor(rc.z) + 0.5};
            matchBoundsRadius = data.getRoyaleRadius() * ROYALE_BOUNDS_FACTOR;
            // Freeze the GRACE length and PvE tier for the whole match, so an admin
            // config change (idle-only anyway) can never perturb a running match.
            matchGraceDuration = data.getRoyaleGraceTicks();
            matchPve = data.getRoyalePve();
        } else {
            pads = data.getPads();
            if (pads.size() < MIN_PADS) {
                broadcast(server, "Not enough spawn pads. Match cancelled.");
                abortMatch(server);
                return;
            }

            // All pads must share one dimension. Legacy data could be mixed; refuse
            // to run rather than teleport fighters into split arenas.
            ResourceLocation dim0 = pads.get(0).dimension;
            for (ArenaPoint pad : pads) {
                if (!pad.dimension.equals(dim0)) {
                    broadcast(server, "Spawn pads span multiple dimensions. Match cancelled — reset the pads.");
                    abortMatch(server);
                    return;
                }
            }

            arenaDimension = pads.get(0).dimensionKey();
            arenaLevel = server.getLevel(arenaDimension);
            if (arenaLevel == null) {
                broadcast(server, "Arena dimension is not loaded. Match cancelled.");
                abortMatch(server);
                return;
            }
            center = computeCenter(pads);
            // Misconfigured pads outside the bounds circle would self-eliminate
            // everyone who spawns on them; refuse to start instead.
            for (ArenaPoint pad : pads) {
                double pdx = pad.x - center[0];
                double pdz = pad.z - center[2];
                if (pdx * pdx + pdz * pdz > (BOUNDS_RADIUS - 10) * (BOUNDS_RADIUS - 10)) {
                    broadcast(server, "A spawn pad lies outside the arena bounds circle. Match cancelled — fix the pads.");
                    abortMatch(server);
                    return;
                }
            }
            matchBoundsRadius = BOUNDS_RADIUS;
        }
        centerX = center[0];
        centerY = center[1];
        centerZ = center[2];

        fighters.clear();
        // DUEL is capped by pad count; ROYALE has no pads, so every viable queued
        // player deploys into their own sky cage on the ring.
        int cap = mode == Mode.ROYALE ? countViableQueued(server) : pads.size();
        // Freeze the sky-cage ring divisor so deploy and the cage build agree on geometry.
        matchRingCount = cap;
        List<UUID> consumed = new ArrayList<>();

        // ---- PASS 1: select viable fighters and PERSIST every RestoreRecord to disk
        // BEFORE any player is mutated (workstream A). A crash between this durability
        // barrier and the deploy pass below always finds the records on disk, so the
        // join/respawn healers can restore everyone. No teleport or gamemode change
        // happens in this pass.
        int index = 0;
        Iterator<Map.Entry<UUID, String>> it = queue.entrySet().iterator();
        while (it.hasNext() && index < cap) {
            Map.Entry<UUID, String> entry = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null || !player.isAlive() || player.hasDisconnected()) {
                // Offline, on the death screen, or mid-disconnect: not viable to
                // deploy right now. Leave them queued and skip.
                continue;
            }
            Fighter f = new Fighter(
                    player.getUUID(),
                    player.getName().getString(),
                    entry.getValue(),
                    player.gameMode.getGameModeForPlayer(),
                    player.level().dimension(),
                    player.getX(), player.getY(), player.getZ(), player.getYRot());
            f.ringIndex = index; // ROYALE cage slot on the ring; unused in DUEL
            fighters.add(f);
            // Never overwrite an unapplied record: it holds the player's TRUE original
            // state from an earlier match that hasn't been healed yet.
            if (data.getPendingRestore(f.uuid) == null) {
                data.putPendingRestore(f.uuid, new RestoreRecord(
                        f.originalGameMode, f.originalDimension,
                        f.originalX, f.originalY, f.originalZ, f.originalYaw));
            }
            consumed.add(entry.getKey());
            index++;
        }
        // Durability barrier: flush ALL records to disk before the first mutation.
        server.overworld().getDataStorage().save();

        for (UUID uuid : consumed) {
            queue.remove(uuid);
        }

        if (fighters.size() < MIN_FIGHTERS) {
            broadcast(server, "Not enough available fighters. Match cancelled.");
            abortMatch(server);
            return;
        }

        // ---- ROYALE cage setup: compute the shared ring geometry, PREFLIGHT every
        // cage volume, and PLACE all cages BEFORE any fighter is teleported. If any
        // cage cannot be placed clear of obstruction the match aborts cleanly with
        // nothing placed (workstream C). Runs while state is still COUNTDOWN so the
        // subsequent scatter is admitted by the ADD guard under the current match tag.
        if (mode == Mode.ROYALE) {
            if (!planRoyaleCages(server, arenaLevel, center)) {
                broadcast(server, "No clear sky above the arena to cage fighters. Match cancelled.");
                abortMatch(server);
                return;
            }
        }

        // ---- PASS 2: mutate — deploy each selected fighter. DUEL mounts a mech on a
        // pad; ROYALE teleports the fighter into their already-built sky cage.
        for (int i = 0; i < fighters.size(); i++) {
            Fighter f = fighters.get(i);
            boolean deployed = mode == Mode.ROYALE
                    ? deployFighterRoyale(server, arenaLevel, f, center)
                    : deployFighter(server, arenaLevel, f, pads.get(i));
            if (!deployed) {
                broadcast(server, "Failed to deploy " + f.name + ". Match aborted.");
                abortMatch(server);
                return;
            }
        }

        if (mode == Mode.ROYALE) {
            // Cages are up and fighters are inside; scatter the loot mechs.
            scatterMechs(arenaLevel);
            cageTicks = CAGE_LENGTH;
            graceTicks = 0; // grace begins only once the cages drop
        } else {
            cageTicks = 0;
            graceTicks = 0;
        }

        state = ArenaState.ACTIVE;
        matchTicks = 0;
        if (mode == Mode.ROYALE) {
            broadcast(server, "Royale started! " + fighters.size()
                    + " fighters caged in the sky — you drop when the cages break.");
        } else {
            broadcast(server, "Match started! " + fighters.size() + " fighters enter the arena.");
        }
    }

    /**
     * Records nothing (already recorded) but performs the physical setup: mode,
     * teleport, mech spawn and mount. Returns false if the mech cannot be
     * spawned or mounted, in which case the caller aborts the whole match.
     */
    private static boolean deployFighter(MinecraftServer server, ServerLevel arenaLevel, Fighter f, ArenaPoint pad) {
        ServerPlayer player = server.getPlayerList().getPlayer(f.uuid);
        if (player == null) {
            return false;
        }

        player.setGameMode(GameType.ADVENTURE);
        player.teleportTo(arenaLevel, pad.x, pad.y, pad.z, pad.yaw, 0f);
        if (player.serverLevel() != arenaLevel) {
            // Another mod cancelled the cross-dimension teleport; never mount a
            // player onto a mech in a level they are not in.
            return false;
        }

        ResourceLocation id = new ResourceLocation(PomkotsMechs.MODID, f.mechId);
        if (!BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
            return false;
        }
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);
        Entity spawned = type.create(arenaLevel);
        if (!(spawned instanceof LivingEntity mech)) {
            if (spawned != null) {
                spawned.discard();
            }
            return false;
        }

        mech.setPos(pad.x, pad.y, pad.z);
        mech.setYRot(pad.yaw);
        mech.addTag(TAG_ARENA);
        mech.addTag(TAG_OWNER_PREFIX + f.uuid);
        // Set the reference before adding so the EntityEvent.ADD guard recognises
        // this as a live fighter's mech and lets it through.
        f.mech = mech;
        if (!arenaLevel.addFreshEntity(mech)) {
            mech.discard();
            f.mech = null;
            return false;
        }

        return player.startRiding(mech, true);
    }

    /**
     * ROYALE deploy: the pre-match snapshot is already recorded and the sky cages are
     * already built (both in {@link #startMatch} before this runs), so this only performs
     * the physical setup — ADVENTURE + a teleport into this fighter's own floating sky
     * cage at their pre-assigned {@link Fighter#ringIndex}. NO mech is spawned or mounted:
     * royale mechs are scattered loot, not a fighter's life. Returns false if the teleport
     * was cancelled/blocked, in which case the caller aborts and everyone is restored.
     */
    private static boolean deployFighterRoyale(MinecraftServer server, ServerLevel arenaLevel, Fighter f,
                                               double[] center) {
        ServerPlayer player = server.getPlayerList().getPlayer(f.uuid);
        if (player == null) {
            return false;
        }
        double[] base = skyCageBase(center, matchRingCount, f.ringIndex);
        // Face the ring center so caged fighters look inward.
        float yaw = (float) (Math.toDegrees(-Math.atan2(center[0] - base[0], center[2] - base[2])));

        player.setGameMode(GameType.ADVENTURE);
        player.teleportTo(arenaLevel, base[0], base[1], base[2], yaw, 0f);
        // A same-dimension teleport leaves serverLevel() unchanged even if another mod
        // cancels the move, so verify the player actually ARRIVED — right dimension AND
        // within 2 blocks of the cage slot — before committing them (workstream B).
        return player.serverLevel() == arenaLevel
                && player.position().distanceToSqr(base[0], base[1], base[2]) < 4.0;
    }

    /**
     * The block-centered feet position of a fighter's sky cage: an evenly-spaced slot
     * on a ring of {@code matchRingRadius} around the center, at the shared
     * {@code matchCageBaseY}. Both the per-match ring radius (scaled so adjacent slots
     * stay >= {@link #SKY_CAGE_SPACING} apart) and the base Y (lifted above the tallest
     * terrain under any slot) are frozen in {@link #planRoyaleCages}. Snapped to the
     * block grid so the cage walls never clip the fighter's bounding box. Both the
     * deploy teleport and the cage build call this, so their geometry can never diverge.
     */
    private static double[] skyCageBase(double[] center, int ringCount, int index) {
        double[] xz = skyCageBaseXZ(center, ringCount, index);
        xz[1] = matchCageBaseY;
        return xz;
    }

    /**
     * The block-centered X/Z of a fighter's cage slot on the ring, WITHOUT the base Y.
     * Used by the geometry pass in {@link #planRoyaleCages} to sample terrain height
     * before the shared base Y exists; {@link #skyCageBase} fills in the Y afterwards.
     */
    private static double[] skyCageBaseXZ(double[] center, int ringCount, int index) {
        double angle = ringCount > 0 ? (2.0 * Math.PI * index / ringCount) : 0.0;
        double x = center[0] + matchRingRadius * Math.cos(angle);
        double z = center[2] + matchRingRadius * Math.sin(angle);
        return new double[]{Mth.floor(x) + 0.5, 0.0, Mth.floor(z) + 0.5};
    }

    /**
     * Computes the shared royale cage geometry, preflights every cage volume, and —
     * only if all are clear — places one floating glass sky cage per fighter around
     * the spawn ring. Each cage is a hollow 5x5 box, four walls high, with a glass
     * roof AND a glass floor (the fighters are in mid-air, so the floor holds them up
     * until it drops). Returns false — nothing placed, no ledger written — if the
     * geometry cannot clear the terrain or any cage volume is obstructed, so the
     * caller aborts the match cleanly (workstream C).
     *
     * <p>Sequence:
     * <ol>
     *   <li>Ring radius scales with fighter count so adjacent slots stay
     *       >= {@link #SKY_CAGE_SPACING} blocks apart at any count.</li>
     *   <li>ONE shared base Y for the whole match: the max motion-blocking surface over
     *       every slot's 5x5 footprint, lifted; clamped under the build height. If the
     *       clamp cannot clear the terrain, abort.</li>
     *   <li>PREFLIGHT every cage's full 5x5 volume at that Y — every interior and shell
     *       position must be air/replaceable. If any slot fails, abort (nothing placed).</li>
     *   <li>Record the plan (MERGED with any retained ledger leftovers — workstream H),
     *       flush to disk BEFORE any glass exists, then place the glass.</li>
     * </ol>
     */
    private static boolean planRoyaleCages(MinecraftServer server, ServerLevel level, double[] center) {
        // (1) Ring radius: circumference / N >= SKY_CAGE_SPACING => radius >= N*spacing/2pi.
        matchRingRadius = Math.max(SKY_CAGE_MIN_RING_RADIUS,
                Math.ceil(matchRingCount * SKY_CAGE_SPACING / (2.0 * Math.PI)));

        // (2) One shared base Y. For each fighter's slot, take the max motion-blocking
        // surface over its 5x5 footprint; the match base Y clears the tallest of those,
        // so a slot over a tower lifts EVERY cage above it (nobody suffocates).
        int globalMaxSurface = level.getMinBuildHeight();
        for (Fighter f : fighters) {
            double[] base = skyCageBaseXZ(center, matchRingCount, f.ringIndex);
            int bx = Mth.floor(base[0]);
            int bz = Mth.floor(base[2]);
            for (int dx = -SKY_CAGE_HALF; dx <= SKY_CAGE_HALF; dx++) {
                for (int dz = -SKY_CAGE_HALF; dz <= SKY_CAGE_HALF; dz++) {
                    int x = bx + dx;
                    int z = bz + dz;
                    level.getChunk(x >> 4, z >> 4); // ensure heightmap is meaningful
                    int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                    if (h > globalMaxSurface) {
                        globalMaxSurface = h;
                    }
                }
            }
        }
        int desiredY = Math.max((int) Math.floor(center[1]) + SKY_CAGE_Y_OFFSET,
                globalMaxSurface + SKY_CAGE_HEADROOM);
        int cageY = Math.min(desiredY, level.getMaxBuildHeight() - SKY_CAGE_HEADROOM);
        // The cage floor sits at cageY-1: it must stay ABOVE the tallest terrain, or
        // the clamp has pushed the box into a tower -> clearance impossible, abort.
        if (cageY - 1 <= globalMaxSurface) {
            return false;
        }
        matchCageBaseY = cageY;

        // (3) Preflight: every position of every cage's full 5x5 volume (floor..roof)
        // must be air/replaceable. With the heightmap lift this virtually always passes;
        // a floating obstruction at this Y aborts the match rather than suffocating.
        ResourceLocation dim = level.dimension().location();
        for (Fighter f : fighters) {
            double[] base = skyCageBase(center, matchRingCount, f.ringIndex);
            if (!cageVolumeClear(level, Mth.floor(base[0]), Mth.floor(base[1]), Mth.floor(base[2]))) {
                return false; // nothing placed, no ledger written
            }
        }

        // (4) Plan every glass position, then MERGE with any retained ledger leftovers
        // (unavailable-dimension entries a prior removal could not clear) so a new match
        // never erases them (workstream H). Flush the ledger to disk BEFORE any glass
        // exists (crash-safe: the ledger can only over-list placed glass, never
        // under-list — removal only ever clears our own glass), then place.
        List<CageBlock> plan = new ArrayList<>();
        for (Fighter f : fighters) {
            double[] base = skyCageBase(center, matchRingCount, f.ringIndex);
            planSkyCage(level, dim, plan, Mth.floor(base[0]), Mth.floor(base[1]), Mth.floor(base[2]));
        }
        ArenaData data = ArenaData.get(server);
        List<CageBlock> merged = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (CageBlock cb : data.getCageBlocks()) {
            if (seen.add(cageKey(cb))) {
                merged.add(cb);
            }
        }
        for (CageBlock cb : plan) {
            if (seen.add(cageKey(cb))) {
                merged.add(cb);
            }
        }
        data.setCageBlocks(merged);
        server.overworld().getDataStorage().save();
        BlockState glass = Blocks.GLASS.defaultBlockState();
        for (CageBlock cb : plan) {
            level.setBlockAndUpdate(cb.pos, glass);
        }
        return true;
    }

    /**
     * True iff a cage's full 5x5 volume — floor (by-1) through roof
     * (by+{@link #SKY_CAGE_WALL_HEIGHT}) — is entirely air/replaceable at the chosen
     * base Y, so the walls/floor/roof can all be placed and the interior can never
     * suffocate a fighter.
     */
    private static boolean cageVolumeClear(ServerLevel level, int bx, int by, int bz) {
        for (int dx = -SKY_CAGE_HALF; dx <= SKY_CAGE_HALF; dx++) {
            for (int dz = -SKY_CAGE_HALF; dz <= SKY_CAGE_HALF; dz++) {
                for (int y = by - 1; y <= by + SKY_CAGE_WALL_HEIGHT; y++) {
                    BlockState s = level.getBlockState(new BlockPos(bx + dx, y, bz + dz));
                    if (!s.isAir() && !s.canBeReplaced()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** Dedup key for the cage ledger merge: dimension + packed block position. */
    private static String cageKey(CageBlock cb) {
        return cb.dimension.toString() + '|' + cb.pos.asLong();
    }

    /** Plans one 5x5 sky cage (glass floor + roof + four walls) centered on the
     *  fighter's feet block, recording only currently AIR/replaceable positions. */
    private static void planSkyCage(ServerLevel level, ResourceLocation dim,
                                    List<CageBlock> plan, int bx, int by, int bz) {
        for (int dx = -SKY_CAGE_HALF; dx <= SKY_CAGE_HALF; dx++) {
            for (int dz = -SKY_CAGE_HALF; dz <= SKY_CAGE_HALF; dz++) {
                // Glass floor one below the fighters' feet, glass roof above the walls.
                planCageBlock(level, dim, plan, bx + dx, by - 1, bz + dz);
                planCageBlock(level, dim, plan, bx + dx, by + SKY_CAGE_WALL_HEIGHT, bz + dz);
                // Walls: perimeter of the footprint only.
                boolean perimeter = dx == -SKY_CAGE_HALF || dx == SKY_CAGE_HALF
                        || dz == -SKY_CAGE_HALF || dz == SKY_CAGE_HALF;
                if (perimeter) {
                    for (int dy = 0; dy < SKY_CAGE_WALL_HEIGHT; dy++) {
                        planCageBlock(level, dim, plan, bx + dx, by + dy, bz + dz);
                    }
                }
            }
        }
    }

    /** Adds the position to the cage plan iff it is currently AIR. */
    private static void planCageBlock(ServerLevel level, ResourceLocation dim,
                                      List<CageBlock> plan, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        BlockState s = level.getBlockState(pos);
        // Vegetation, snow layers and other replaceables must not leave passable
        // gaps in the cage walls — they are throwaway blocks, so overwriting them
        // is as safe as overwriting air (removal still only clears GLASS).
        if (s.isAir() || s.canBeReplaced()) {
            plan.add(new CageBlock(dim, pos));
        }
    }

    /**
     * Scatters {@code mechCount} unowned loot mechs across the city: uniform angle,
     * distance {@code SCATTER_MIN_DIST}..radius from center, surface Y from the
     * motion-blocking heightmap. The chunk is force-generated first so getHeight is
     * meaningful on ungenerated terrain. Each mech is tagged with the current match
     * id (and no owner tag) so the stray sweeps treat it as legitimate loot.
     *
     * <p>The deployed mechs come from the {@link GarageFleet}: fully-assembled preset
     * custom-mech builds (frame + generator + booster + fuel + weapons with loaded ammo),
     * cycled by index so varied archetypes spread across the city rather than the three
     * identical stock frames the scatter used to place.
     */
    private static void scatterMechs(ServerLevel level) {
        RandomSource rand = level.getRandom();
        royaleMechsAlive = 0;
        int want = ArenaData.get(level.getServer()).getMechCount();
        // Reachability pass: prefer planned points whose surface sits within a
        // band of the center's street level (a player on foot can get there);
        // top up from the leftovers only if the streets can't fill the quota.
        List<int[]> ordered = new ArrayList<>();
        List<int[]> rooftops = new ArrayList<>();
        for (int[] pt : scatterPoints) {
            // Chunks were prepped across the countdown (planScatter/tickCountdown);
            // getChunk here is a cheap cache hit that also covers the rare case of
            // a countdown too short to exhaust the prep list.
            level.getChunk(pt[0] >> 4, pt[1] >> 4);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, pt[0], pt[1]);
            if (y <= centerY + 8) {
                ordered.add(new int[]{pt[0], pt[1], y});
            } else {
                rooftops.add(new int[]{pt[0], pt[1], y});
            }
            if (ordered.size() >= want) {
                break;
            }
        }
        for (int[] r : rooftops) {
            if (ordered.size() >= want) {
                break;
            }
            ordered.add(r);
        }
        for (int i = 0; i < ordered.size(); i++) {
            int[] pt = ordered.get(i);
            int x = pt[0];
            int z = pt[1];
            int y = pt[2];

            // Deploy a preset custom-mech loadout from the garage fleet, cycling the fleet by
            // index (GarageFleet.build wraps modulo its size) so distinct builds spread out.
            LivingEntity mech = GarageFleet.build(level, i);
            if (mech == null) {
                continue;
            }
            mech.setPos(x + 0.5, y, z + 0.5);
            mech.setYRot(rand.nextFloat() * 360.0f);
            mech.addTag(TAG_ARENA);
            mech.addTag(TAG_MATCH_PREFIX + matchId);
            if (!level.addFreshEntity(mech)) {
                mech.discard();
            } else {
                royaleMechsAlive++;
            }
        }
        broadcast(level.getServer(), royaleMechsAlive + " custom mechs deployed across the city.");
    }

    /**
     * PvE pressure fired at GRACE end. light -> one wave, heavy -> two waves of
     * {@link #PVE_MOBS_PER_WAVE} hostiles.
     *
     * <p>Implementation choice: direct tagged-mob spawns, NOT the raid controller.
     * {@link grcmcs.minecraft.mods.pomkotsmechs.entity.event.RaidControllerEntity}
     * hard-requires a living owner (it ends the raid the moment {@code raidOwner}
     * resolves null) and layers on objective entities, advancements, finalize
     * commands and boss bars — none of which fit an ownerless free-for-all where
     * the "owner" fighter can be eliminated at any moment. The spec sanctions this
     * simplification, so we spawn the mod's own Monsters directly. They already
     * target the nearest player autonomously (hostile to everyone equally) and are
     * tagged so every existing arena cleanup path reaps them.
     */
    private static void triggerPve(MinecraftServer server) {
        if (matchPve == RoyalePve.OFF) {
            return;
        }
        ServerLevel level = arenaDimension != null ? server.getLevel(arenaDimension) : null;
        if (level == null) {
            return;
        }
        int waves = matchPve == RoyalePve.HEAVY ? 2 : 1;
        int spawned = spawnPveMobs(level, waves * PVE_MOBS_PER_WAVE);
        if (spawned > 0) {
            broadcast(server, "Hostiles inbound — " + spawned + " enemy units deployed across the city!");
        }
    }

    /**
     * Spawns {@code count} hostile mobs at random scatter points, at the surface
     * heightmap Y. Each carries TAG_ARENA + the current match tag (so the ADD guard
     * and stray sweeps treat it as legitimate and every cleanup path reaps it) plus
     * TAG_PVE (so its death is never mistaken for a loot mech). Returns the number
     * actually spawned.
     */
    private static int spawnPveMobs(ServerLevel level, int count) {
        // Accepted latency (workstream I): a PvE mob that wanders into a chunk which
        // then unloads is not reaped the instant the match ends — it is culled when its
        // chunk next loads, by the SERVER_STARTED boot sweep, the EntityEvent.ADD guard,
        // or the periodic IDLE stray sweep (all match-tag aware). We deliberately do NOT
        // add chunk-load hooks for this rare, self-healing edge.
        if (scatterPoints.isEmpty()) {
            return 0;
        }
        RandomSource rand = level.getRandom();
        int spawned = 0;
        for (int i = 0; i < count; i++) {
            int[] pt = scatterPoints.get(rand.nextInt(scatterPoints.size()));
            int x = pt[0];
            int z = pt[1];
            level.getChunk(x >> 4, z >> 4);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
            String mobId = PVE_ROSTER.get(i % PVE_ROSTER.size());
            ResourceLocation id = new ResourceLocation(PomkotsMechs.MODID, mobId);
            if (!BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
                continue;
            }
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);
            Entity spawnedEntity = type.create(level);
            if (!(spawnedEntity instanceof LivingEntity mob)) {
                if (spawnedEntity != null) {
                    spawnedEntity.discard();
                }
                continue;
            }
            mob.setPos(x + 0.5, y, z + 0.5);
            mob.setYRot(rand.nextFloat() * 360.0f);
            mob.addTag(TAG_ARENA);
            mob.addTag(TAG_MATCH_PREFIX + matchId);
            mob.addTag(TAG_PVE);
            if (mob instanceof Mob m) {
                m.setPersistenceRequired(); // never despawn mid-match
            }
            if (!level.addFreshEntity(mob)) {
                mob.discard();
            } else {
                spawned++;
            }
        }
        return spawned;
    }

    /**
     * Removes every recorded cage block (setting it back to AIR only if it is
     * still our glass, so a player-replaced block is left alone) and clears the
     * ledger. Blocks in an unloadable dimension are kept recorded for a later
     * retry rather than silently forgotten.
     */
    private static void removeCageBlocks(MinecraftServer server) {
        ArenaData data = ArenaData.get(server);
        List<CageBlock> blocks = data.getCageBlocks();
        if (blocks.isEmpty()) {
            return;
        }
        List<CageBlock> retained = new ArrayList<>();
        for (CageBlock cb : new ArrayList<>(blocks)) {
            ServerLevel level = server.getLevel(cb.dimensionKey());
            if (level == null) {
                retained.add(cb);
                continue;
            }
            if (level.getBlockState(cb.pos).is(Blocks.GLASS)) {
                level.setBlockAndUpdate(cb.pos, Blocks.AIR.defaultBlockState());
            }
        }
        data.setCageBlocks(retained);
        // Flush the cleared ledger so a crash right after removal cannot replay
        // stale entries onto player-built glass later.
        server.overworld().getDataStorage().save();
    }

    /** Resolves the royale prep level during COUNTDOWN (null when unset). */
    private static ServerLevel arenaLevelForPrep(MinecraftServer server) {
        ArenaPoint rc = ArenaData.get(server).getRoyaleCenter();
        return rc != null ? server.getLevel(rc.dimensionKey()) : null;
    }

    private static void tickActive(MinecraftServer server) {
        matchTicks++;

        // Mid-match stray sweep (every 5s): catches crash leftovers whose chunks
        // load during a match on loaders where EntityEvent.ADD misses them. The
        // sweep is match-tag aware, so it never reaps the current scatter.
        if (++sweepTimer >= 100) {
            sweepTimer = 0;
            sweepStrayMechs(server);
        }

        if (matchMode == Mode.SOLO) {
            tickSolo(server);
            return;
        }

        // ROYALE cage phase: fighters are held in the floating glass boxes. No
        // mount policy, no out-of-bounds, no win check while the cages still stand
        // — just count them down and drop them. (DUEL keeps cageTicks == 0.)
        if (matchMode == Mode.ROYALE && cageTicks > 0) {
            tickCage(server);
            return;
        }

        // ROYALE landing safety: once the cages have dropped, keep every not-yet-landed
        // fighter's fall distance at zero each tick until they touch down — INDEPENDENT
        // of the grace window (workstream D), so a laggy faller still in the air when
        // grace lapses never dies to the sky-cage drop.
        if (matchMode == Mode.ROYALE) {
            tickLandingSafety(server);
        }

        // ROYALE GRACE phase: the cages have dropped and fighters are falling in /
        // scrambling for a mech. Damage is blocked (see isGraceProtected), out-of-bounds
        // only WARNS, and the win check is paused — nobody can be eliminated until the
        // fight actually starts. (Fall damage is neutralised by tickLandingSafety above.)
        if (matchMode == Mode.ROYALE && graceTicks > 0) {
            tickGrace(server);
            return;
        }

        // Mount policy seam: DUEL evicts mech thieves. In ROYALE any player may
        // mount any mech — that scramble IS the game — so this never runs.
        if (matchMode == Mode.DUEL) {
            ejectMechThieves();
        }

        ServerLevel arenaLevel = arenaDimension != null ? server.getLevel(arenaDimension) : null;
        if (arenaLevel != null) {
            checkOutOfBounds(server, true);
        }

        if (evaluateWinCondition(server)) {
            return;
        }

        if (matchTicks >= MATCH_TIMEOUT) {
            if (matchMode == Mode.ROYALE) {
                // Royale endgame: no draw — the zone closes on the center and the
                // EXISTING bounds check eliminates whoever stays outside, forcing
                // a winner. The shrink rate is computed PER MATCH so any legal
                // radius (50..1000) reaches the minimum ring with time to spare
                // before the hard-cap backstop.
                if (matchTicks == MATCH_TIMEOUT) {
                    broadcast(server, "THE ZONE IS CLOSING — get to the center!");
                    int shrinkWindow = (ROYALE_HARD_CAP - MATCH_TIMEOUT) - 2 * BOUNDS_GRACE;
                    zoneShrinkPerTick = Math.max(ZONE_SHRINK_PER_TICK,
                            (matchBoundsRadius - ZONE_MIN_RADIUS) / shrinkWindow);
                }
                matchBoundsRadius = Math.max(ZONE_MIN_RADIUS, matchBoundsRadius - zoneShrinkPerTick);
                if ((matchTicks - MATCH_TIMEOUT) % 600 == 0 && matchTicks > MATCH_TIMEOUT) {
                    broadcast(server, "Zone radius: " + (int) matchBoundsRadius + " blocks.");
                }
                if (matchTicks >= ROYALE_HARD_CAP) {
                    broadcast(server, "Time limit reached — the match is a draw.");
                    beginEnding(server);
                }
            } else {
                broadcast(server, "Time limit reached — the match is a draw.");
                beginEnding(server);
            }
        }
    }

    /**
     * SOLO's ACTIVE path. The arena still owns fighter loss, bounds, outcome and
     * teardown; the director owns only encounter pacing and enemy lifecycle.
     * Fighter loss is checked before the director so a same-tick boss/mech double KO
     * resolves as defeat instead of granting a victory to a destroyed player mech.
     */
    private static void tickSolo(MinecraftServer server) {
        Fighter fighter = fighters.size() == 1 ? fighters.get(0) : null;
        if (fighter == null || fighter.eliminated) {
            recordSoloDefeat(server, fighter, AshenSpanMissionModel.DefeatReason.DISCONNECTED,
                    "SOLO DEFEAT — your combat frame was lost.");
            beginEnding(server);
            return;
        }

        ServerPlayer player = server.getPlayerList().getPlayer(fighter.uuid);
        if (player == null || player.hasDisconnected() || !player.isAlive()) {
            AshenSpanMissionModel.DefeatReason reason = player == null || player.hasDisconnected()
                    ? AshenSpanMissionModel.DefeatReason.DISCONNECTED
                    : AshenSpanMissionModel.DefeatReason.PLAYER_DESTROYED;
            eliminate(server, fighter, false);
            recordSoloDefeat(server, fighter, reason, "SOLO DEFEAT — pilot unavailable.");
            beginEnding(server);
            return;
        }
        if (fighter.mech == null || !fighter.mech.isAlive()) {
            eliminate(server, fighter, true);
            recordSoloDefeat(server, fighter, AshenSpanMissionModel.DefeatReason.MECH_DESTROYED,
                    "SOLO DEFEAT — your combat frame was destroyed.");
            beginEnding(server);
            return;
        }
        if (matchTicks >= SOLO_MATCH_TIMEOUT) {
            recordSoloDefeat(server, fighter, AshenSpanMissionModel.DefeatReason.TIME_LIMIT,
                    "SOLO DEFEAT — 20-minute safety limit reached.");
            beginEnding(server);
            return;
        }
        if (arenaDimension == null) {
            recordSoloDefeat(server, fighter, AshenSpanMissionModel.DefeatReason.MISSION_ABORTED,
                    "SOLO RUN ABORTED — arena dimension became unavailable.");
            beginEnding(server);
            return;
        }
        if (!fighter.mech.level().dimension().equals(arenaDimension)) {
            eliminate(server, fighter, true);
            recordSoloDefeat(server, fighter, AshenSpanMissionModel.DefeatReason.MISSION_ABORTED,
                    "SOLO DEFEAT — your combat frame left the arena dimension.");
            beginEnding(server);
            return;
        }

        // Observer policy is enforced even while the owner is receiving bounds
        // or portal grace. Otherwise a helper could enter, clear live enemies,
        // and leave while the director itself is intentionally paused.
        ServerPlayer intruder = findSoloIntruder(server, fighter.uuid, arenaDimension,
                centerX, centerZ, matchBoundsRadius + 24.0);
        if (intruder != null) {
            recordSoloDefeat(server, fighter, AshenSpanMissionModel.DefeatReason.MISSION_ABORTED,
                    "SOLO RUN ABORTED — " + intruder.getName().getString()
                            + " entered the combat zone. Observers must use spectator mode.");
            beginEnding(server);
            return;
        }

        if (!player.level().dimension().equals(arenaDimension)) {
            return;
        }

        // SOLO is mech combat. Eject a stolen passenger, then repair an accidental
        // nearby dismount immediately. A pilot who abandons the frame cannot fight
        // waves on foot or wedge the director around a distant target.
        for (Entity passenger : new ArrayList<>(fighter.mech.getPassengers())) {
            if (passenger != player) {
                passenger.stopRiding();
            }
        }
        if (player.getVehicle() != fighter.mech
                && (player.distanceToSqr(fighter.mech) > 64.0
                || !player.startRiding(fighter.mech, true))) {
            eliminate(server, fighter, true);
            recordSoloDefeat(server, fighter, AshenSpanMissionModel.DefeatReason.PILOT_SEPARATED,
                    "SOLO DEFEAT — pilot separated from the combat frame.");
            beginEnding(server);
            return;
        }

        ServerLevel arenaLevel = server.getLevel(arenaDimension);
        if (arenaLevel == null) {
            recordSoloDefeat(server, fighter, AshenSpanMissionModel.DefeatReason.MISSION_ABORTED,
                    "SOLO RUN ABORTED — arena dimension became unavailable.");
            beginEnding(server);
            return;
        }
        AshenSpanDirector.TickResult result = ashenSpanDirector.tick(
                server, arenaLevel, player, fighter.mech);
        for (String message : ashenSpanDirector.drainMessages()) {
            broadcast(server, message);
        }
        if (result == AshenSpanDirector.TickResult.VICTORY) {
            broadcast(server, "SOLO VICTORY — SPAN WARDEN DESTROYED.");
            broadcast(server, ashenSpanDirector.summary(fighter.mech.getHealth()));
            beginEnding(server);
            return;
        }
        if (result == AshenSpanDirector.TickResult.FAILED) {
            broadcast(server, "SOLO DEFEAT — " + ashenSpanDirector.failureReason());
            broadcast(server, ashenSpanDirector.summary(fighter.mech.getHealth()));
            beginEnding(server);
            return;
        }

    }

    private static void recordSoloDefeat(MinecraftServer server, Fighter fighter,
                                         AshenSpanMissionModel.DefeatReason reason,
                                         String message) {
        ashenSpanDirector.recordDefeat(reason, message);
        broadcast(server, message);
        float remainingHealth = fighter != null && fighter.mech != null
                ? fighter.mech.getHealth() : 0.0F;
        broadcast(server, ashenSpanDirector.summary(remainingHealth));
    }

    private static ServerPlayer findSoloIntruder(MinecraftServer server, UUID owner,
                                                  net.minecraft.resources.ResourceKey<Level> dimension,
                                                  double x, double z, double radius) {
        double radiusSqr = radius * radius;
        for (ServerPlayer candidate : server.getPlayerList().getPlayers()) {
            if (candidate.getUUID().equals(owner) || candidate.hasDisconnected()
                    || !candidate.isAlive() || candidate.isSpectator()
                    || !candidate.level().dimension().equals(dimension)) {
                continue;
            }
            double dx = candidate.getX() - x;
            double dz = candidate.getZ() - z;
            if (dx * dx + dz * dz <= radiusSqr) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * ROYALE cage countdown. Announces the drop at 10/5/4/3/2/1 seconds and, when
     * it lapses, removes ALL recorded cage blocks (glass floors go with the walls
     * and roofs, so fighters fall straight through) and opens the GRACE window. No
     * elimination can happen here — the ACTIVE tick skips bounds and win checks
     * entirely while {@code cageTicks > 0}.
     */
    private static void tickCage(MinecraftServer server) {
        // Keep every held fighter's fall distance (and their mount's) at zero while the
        // cages still stand — exactly like tickGrace/tickLandingSafety — so a
        // teleport-into-cage never banks fall damage that would land the instant the
        // floor drops (workstream C). Fighters are also isGraceProtected during the HOLD.
        for (Fighter f : fighters) {
            if (f.eliminated) {
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(f.uuid);
            if (player != null) {
                player.fallDistance = 0.0f;
                if (player.getVehicle() != null) {
                    player.getVehicle().fallDistance = 0.0f;
                }
            }
        }
        if (cageTicks % 20 == 0) {
            int secs = cageTicks / 20;
            if (secs == 10 || secs <= 5) {
                broadcast(server, "Cages drop in " + secs + "...");
            }
        }
        cageTicks--;
        if (cageTicks <= 0) {
            // Removing every recorded position at once takes the floors out with the
            // walls/roofs; the fighters drop toward the city below. Grace opens now,
            // covering the fall and the mech scramble.
            removeCageBlocks(server);
            for (Fighter f : fighters) {
                f.outsideTicks = 0; // fresh bounds grace once real combat begins
            }
            graceTicks = matchGraceDuration;
            broadcast(server, "The cages drop — fall in! No damage for "
                    + (matchGraceDuration / 20) + "s. Grab a mech!");
        }
    }

    /**
     * ROYALE GRACE countdown. Keeps fall damage off every fighter (descent from the
     * sky cages), warns anyone who strays out of bounds WITHOUT eliminating them,
     * announces the fight start at 30/10/5/4/3/2/1 seconds, and — when it lapses —
     * ends grace and fires the configured PvE pressure. Win evaluation stays paused
     * for the whole window (this returns before the ACTIVE tick reaches it).
     */
    private static void tickGrace(MinecraftServer server) {
        // Fall-damage neutralisation during the descent is handled by tickLandingSafety
        // (called from tickActive before this), which also carries it PAST grace end for
        // any fighter still in the air. Here we only run the grace warnings + countdown.
        // Warnings only — eliminations are paused during grace.
        checkOutOfBounds(server, false);

        if (graceTicks % 20 == 0) {
            int secs = graceTicks / 20;
            if (secs == 30 || secs == 10 || (secs >= 1 && secs <= 5)) {
                broadcast(server, "Grace: " + secs + "s until the fight begins.");
            }
        }
        graceTicks--;
        if (graceTicks <= 0) {
            for (Fighter f : fighters) {
                f.outsideTicks = 0; // clear any grace-time OOB debounce -> full bounds grace
            }
            broadcast(server, "GRACE OVER — fight!");
            triggerPve(server);
        }
    }

    /**
     * ROYALE landing safety (post cage-drop). For every not-yet-landed fighter, marks
     * them landed the first tick they (or their mech) touch the ground, and until then
     * keeps their fall distance — and their mount's — at zero every tick. Runs every
     * ACTIVE tick after the cages drop, INDEPENDENT of the grace window (workstream D),
     * so a fighter still falling when grace lapses cannot die to the sky-cage drop.
     * Cheap: one onGround check per un-landed fighter, and it stops touching a fighter
     * the moment they land.
     */
    private static void tickLandingSafety(MinecraftServer server) {
        for (Fighter f : fighters) {
            if (f.eliminated || f.landed) {
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(f.uuid);
            if (player == null) {
                continue; // offline; a rejoin re-enters this loop still un-landed
            }
            Entity vehicle = player.getVehicle();
            // Ground OR water counts as landed — water negates fall damage and would
            // otherwise never trip onGround, leaving the fighter with permanent fall
            // immunity (the drop can land in a city pool/river).
            boolean down = player.onGround() || player.isInWater()
                    || (vehicle != null && (vehicle.onGround() || vehicle.isInWater()));
            if (down) {
                f.landed = true;
                continue;
            }
            player.fallDistance = 0.0f;
            if (vehicle != null) {
                vehicle.fallDistance = 0.0f;
            }
        }
    }

    /**
     * The base mod lets ANY player right-click-mount an unoccupied mech (and
     * riders become invulnerable), so a bystander could steal a fighter's mech
     * mid-match. Throw off every rider who is not the mech's owner.
     */
    private static void ejectMechThieves() {
        for (Fighter f : fighters) {
            if (f.eliminated || f.mech == null || !f.mech.isAlive()) {
                continue;
            }
            for (Entity passenger : new ArrayList<>(f.mech.getPassengers())) {
                if (passenger instanceof ServerPlayer rider && !rider.getUUID().equals(f.uuid)) {
                    rider.stopRiding();
                    sendTo(rider, "That mech belongs to " + f.name + ".");
                }
            }
        }
    }

    /**
     * @param allowElimination when false (ROYALE grace), fighters who stray out of
     *        bounds are still WARNED but never eliminated — the countdown to
     *        elimination is frozen. Normal ACTIVE combat passes true.
     */
    private static void checkOutOfBounds(MinecraftServer server, boolean allowElimination) {
        for (Fighter f : fighters) {
            if (f.eliminated) {
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(f.uuid);
            if (player == null) {
                continue; // disconnect handled by the quit event
            }
            boolean outside;
            if (!player.level().dimension().equals(arenaDimension)) {
                // Left the arena dimension entirely (e.g. a nether portal). Treat
                // as out of bounds on the same grace timer.
                outside = true;
            } else {
                double dx = player.getX() - centerX;
                double dz = player.getZ() - centerZ;
                // Per-match radius: DUEL uses BOUNDS_RADIUS, ROYALE uses its
                // configured play radius scaled by ROYALE_BOUNDS_FACTOR.
                outside = (dx * dx + dz * dz) > matchBoundsRadius * matchBoundsRadius;
            }
            if (outside) {
                if (!allowElimination) {
                    // Grace: warn but do not charge the elimination timer, so a
                    // fighter still drifting in when the fight starts gets the full
                    // bounds grace fresh (tickCage already zeroed outsideTicks).
                    if (f.outsideTicks == 0) {
                        sendTo(player, "You're outside the arena — get back in before the fight starts.");
                        f.outsideTicks = 1; // debounce the warning; reset at grace end
                    }
                    continue;
                }
                f.outsideTicks++;
                if (f.outsideTicks == 1 || f.outsideTicks % 20 == 0) {
                    int secsLeft = Math.max(1, (BOUNDS_GRACE - f.outsideTicks) / 20 + 1);
                    sendTo(player, "Return to the arena! Eliminated in " + secsLeft + "s.");
                }
                if (f.outsideTicks > BOUNDS_GRACE) {
                    eliminate(server, f, true);
                }
            } else {
                f.outsideTicks = 0;
            }
        }
    }

    /** Returns true if the match transitioned to ENDING this call. */
    private static boolean evaluateWinCondition(MinecraftServer server) {
        int alive = 0;
        Fighter last = null;
        for (Fighter f : fighters) {
            if (!f.eliminated) {
                alive++;
                last = f;
            }
        }
        if (alive <= 1) {
            if (alive == 1) {
                broadcast(server, last.name + " wins the arena match!");
            } else {
                broadcast(server, "Double KO — the match is a draw.");
            }
            beginEnding(server);
            return true;
        }
        return false;
    }

    private static void beginEnding(MinecraftServer server) {
        state = ArenaState.ENDING;
        endingTicks = ENDING_LENGTH;
        // Clear PvE pressure the instant the outcome is decided so it cannot harass
        // the winner during the 5s wind-down (workstream D). Targeted discard: ONLY
        // current-match PvE bodies and their tracked projectiles, never royale loot
        // mechs — those stay until cleanup so a spectator still sees the battlefield.
        discardMatchPveMobs(server);
    }

    /** Discards current-match PvE bodies and solo combat projectiles. */
    private static void discardMatchPveMobs(MinecraftServer server) {
        String matchTag = TAG_MATCH_PREFIX + matchId;
        for (ServerLevel level : server.getAllLevels()) {
            List<? extends Entity> mobs = level.getEntities(
                    EntityTypeTest.forClass(Entity.class),
                    e -> e.getTags().contains(matchTag)
                            && (e.getTags().contains(TAG_PVE)
                            || e.getTags().contains(TAG_SOLO_PROJECTILE)));
            for (Entity e : mobs) {
                e.discard();
            }
        }
        if (matchMode == Mode.SOLO) {
            soloProjectileIds.clear();
        }
    }

    private static void tickEnding(MinecraftServer server) {
        endingTicks--;
        if (endingTicks <= 0) {
            cleanup(server);
        }
    }

    // ---------------------------------------------------------------------
    // Elimination & cleanup
    // ---------------------------------------------------------------------

    /**
     * Eliminate a fighter. Kills their mech and, if {@code spectate} and the
     * player is still alive and online, drops them into spectator above center.
     * A dead player (mid-death elimination) is left to respawn normally and is
     * restored during cleanup.
     */
    private static void eliminate(MinecraftServer server, Fighter f, boolean spectate) {
        if (f.eliminated) {
            return;
        }
        f.eliminated = true;

        if (f.mech != null && f.mech.isAlive()) {
            f.mech.ejectPassengers();
            f.mech.discard();
        }

        int remain = 0;
        for (Fighter o : fighters) {
            if (!o.eliminated) {
                remain++;
            }
        }
        if (server != null) {
            broadcast(server, f.name + " is eliminated — " + remain + " remain.");

            if (spectate) {
                ServerPlayer player = server.getPlayerList().getPlayer(f.uuid);
                if (player != null && player.isAlive()) {
                    player.setGameMode(GameType.SPECTATOR);
                    ServerLevel level = arenaDimension != null ? server.getLevel(arenaDimension) : null;
                    if (level == null) {
                        level = server.overworld();
                    }
                    player.teleportTo(level, centerX, centerY + SPECTATE_HEIGHT, centerZ, player.getYRot(), player.getXRot());
                }
            }
        }
    }

    /** Shared, idempotent teardown used by win/draw/stop/abort. */
    private static void cleanup(MinecraftServer server) {
        killTaggedEntities(server);
        // Royale leaves a glass cage and scattered mechs: killTaggedEntities
        // reaps the mechs; this restores the cage blocks. No-op for DUEL (the
        // ledger is empty), so DUEL teardown is unchanged.
        removeCageBlocks(server);
        MissionGateLedger.restoreAll(server);
        ArenaData data = ArenaData.get(server);
        boolean healed = false;
        for (Fighter f : new ArrayList<>(fighters)) {
            ServerPlayer player = server.getPlayerList().getPlayer(f.uuid);
            RestoreRecord rec = data.getPendingRestore(f.uuid);
            if (rec == null) {
                continue; // already restored (e.g. a quit mid-match)
            }
            // Only heal players who are online AND alive right now — a dead player
            // on the death screen can't be teleported yet, so leave their record
            // for the RESPAWN healer. Offline players are left for the JOIN healer.
            if (player != null && player.isAlive()) {
                restorePlayer(server, player, rec);
                if (data.getPendingRestore(f.uuid) == null) {
                    healed = true; // record was actually cleared this teardown
                }
            }
        }
        fighters.clear();
        resetToIdle();
        if (healed) {
            // Durably persist the restored playerdata AHEAD of the just-cleared journal
            // records. The record clears above are only ArenaData-dirty; any later
            // forced ArenaData-only save (e.g. the NEXT match's startMatch durability
            // barrier, or a royale ledger flush) could otherwise persist those deletions
            // while the restored gamemode/position still live only in memory — a JVM
            // crash between the two would strand a player in arena-state playerdata with
            // no recovery record. saveEverything runs SYNCHRONOUSLY on the server thread
            // (a one-time match-end hitch; flush=false only skips the blocking chunk-flush
            // wait) and writes all online players' data FIRST, then the SavedData, so the
            // restore is durable before its record deletion can be. Gated to fire only
            // when a teardown actually healed someone. NOTE: the single-player join/respawn
            // healers do not co-persist this way (too heavy per event) — a much narrower,
            // largely pre-existing residual documented on those paths.
            server.saveEverything(true, false, false);
        }
    }

    private static void abortMatch(MinecraftServer server) {
        cleanup(server);
        // Clear the queue too: a persistent failure (bad pads, blocked mounts)
        // would otherwise re-arm auto-start and loop abort broadcasts forever.
        if (!queue.isEmpty()) {
            queue.clear();
            broadcast(server, "Queue cleared — re-join with /arena join once the arena is fixed.");
        }
    }

    /**
     * Restore a player from a durable {@link RestoreRecord} (not a Fighter, so it
     * can heal players whose Fighter object no longer exists — crash relogs,
     * post-cleanup respawns). Applies gamemode + teleport (lobby if set, else the
     * record's own position), then clears the record so it is never re-applied.
     *
     * <p>Durability note: the BATCH caller {@link #cleanup} co-persists all its
     * restores with one {@code saveEverything} so the cleared records are durable.
     * The single-player healers ({@code onPlayerJoin}/{@code onPlayerRespawn}) do NOT
     * — a full save per rejoin/respawn is too heavy. That leaves a narrow, largely
     * pre-existing residual: a single-player heal clears the record in memory, an
     * ArenaData-only save then persists that deletion, and a JVM crash lands before
     * the player's own data is saved. Fully closing it needs an access-widened
     * single-player {@code PlayerList.save} (protected in 1.20.1) — a follow-up.
     */
    private static void restorePlayer(MinecraftServer server, ServerPlayer player, RestoreRecord record) {
        player.setGameMode(record.gameMode);

        ArenaData data = ArenaData.get(server);
        ArenaPoint lobby = data.getLobby();
        if (lobby != null) {
            ServerLevel level = server.getLevel(lobby.dimensionKey());
            if (level != null) {
                player.teleportTo(level, lobby.x, lobby.y, lobby.z, lobby.yaw, 0f);
                // Confirm the teleport actually ARRIVED — dimension AND within 2 blocks
                // of the target — before clearing the record. serverLevel() alone is a
                // trivially-true same-dimension check even if another mod cancelled the
                // move, which would strand the player and drop their record (workstream B).
                if (player.serverLevel() == level
                        && player.position().distanceToSqr(lobby.x, lobby.y, lobby.z) < 4.0) {
                    data.removePendingRestore(player.getUUID());
                    return;
                }
                // Teleport was cancelled by another mod; try the record position.
            }
        }
        // Fall back to the recorded pre-match position.
        ServerLevel level = server.getLevel(record.dimension);
        if (level == null) {
            level = server.overworld();
        }
        player.teleportTo(level, record.x, record.y, record.z, record.yaw, 0f);
        if (player.serverLevel() == level
                && player.position().distanceToSqr(record.x, record.y, record.z) < 4.0) {
            data.removePendingRestore(player.getUUID());
        }
        // else: keep the record so a later join/respawn heal retries.
    }

    private static void killTaggedEntities(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            List<? extends Entity> tagged = level.getEntities(
                    EntityTypeTest.forClass(Entity.class),
                    e -> e.getTags().contains(TAG_ARENA));
            for (Entity e : tagged) {
                e.ejectPassengers();
                e.discard();
            }
        }
    }

    private static void resetToIdle() {
        spawnDirector.reset();
        ashenSpanDirector.reset();
        soloProjectileIds.clear();
        soloProjectileDrops = 0;
        soloOwner = null;
        state = ArenaState.IDLE;
        idleTimer = 0;
        countdownTicks = 0;
        matchTicks = 0;
        endingTicks = 0;
        cageTicks = 0;
        graceTicks = 0;
        arenaDimension = null;
        scatterPoints.clear();
        scatterChunksToPrep.clear();
        royaleMechsAlive = 0;
        matchRingCount = 0;
        matchRingRadius = SKY_CAGE_MIN_RING_RADIUS;
        matchCageBaseY = 0;
        matchMinPlayers = MIN_FIGHTERS;
        zoneShrinkPerTick = ZONE_SHRINK_PER_TICK;
        // Clear per-match landing state (workstream D). Fighters are cleared by cleanup
        // before this runs, so this is a defensive no-op in the normal path, but it
        // guarantees no landed flag can ever survive into a fresh match.
        for (Fighter f : fighters) {
            f.landed = false;
        }
    }

    /**
     * Pure: returns {@code [cx, cy, cz]} for the given pads without touching any
     * static state. The live match's center statics are assigned ONLY in
     * {@link #startMatch}, so read-only callers (e.g. /arena info) can't perturb a
     * running match's bounds circle.
     */
    private static double[] computeCenter(List<ArenaPoint> pads) {
        double sx = 0, sy = 0, sz = 0;
        for (ArenaPoint pad : pads) {
            sx += pad.x;
            sy += pad.y;
            sz += pad.z;
        }
        int n = pads.size();
        return new double[]{sx / n, sy / n, sz / n};
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static Fighter findFighterByUuid(UUID uuid) {
        for (Fighter f : fighters) {
            if (f.uuid.equals(uuid)) {
                return f;
            }
        }
        return null;
    }

    private static Fighter findFighterByMech(Entity mech) {
        for (Fighter f : fighters) {
            if (f.mech == mech) {
                return f;
            }
        }
        // Fallback: match by owner tag in case the reference was lost.
        String ownerTag = null;
        for (String tag : mech.getTags()) {
            if (tag.startsWith(TAG_OWNER_PREFIX)) {
                ownerTag = tag.substring(TAG_OWNER_PREFIX.length());
                break;
            }
        }
        if (ownerTag != null) {
            try {
                Fighter f = findFighterByUuid(UUID.fromString(ownerTag));
                if (f != null && mech instanceof LivingEntity le
                        && (f.mech == null || !f.mech.isAlive())) {
                    // The mech was chunk-unloaded and reloaded as a new instance:
                    // heal the stale reference so eject/discard keep working.
                    f.mech = le;
                }
                return f;
            } catch (IllegalArgumentException ignored) {
                // malformed tag; ignore
            }
        }
        return null;
    }

    /**
     * True while a ROYALE protection window is open AND the given entity is protected
     * by it: an active fighter (on foot mid-scramble, or the winner) or a current-match
     * arena mech (a loot mech, possibly already piloted). The protection windows are the
     * cage HOLD and the GRACE window (both ACTIVE) plus the ENDING wind-down — the last
     * so the winner cannot die to an in-flight projectile during the 5s outro. The
     * {@link grcmcs.minecraft.mods.pomkotsmechs.mixin.LivingEntityMixin} hurt hook
     * consults this to cancel ALL non-bypass damage in these windows — player, AI/mob,
     * and fall alike — so the drop-in scramble is completely safe and no elimination can
     * happen until "GRACE OVER — fight!".
     *
     * <p>Reads only static state, so it is a cheap no-op outside a live royale window
     * (and on a client JVM, where these statics never leave their defaults). DUEL never
     * opens a protection window, so this is always false there.
     */
    public static boolean isGraceProtected(LivingEntity entity) {
        if (matchMode != Mode.ROYALE) {
            return false;
        }
        boolean active = state == ArenaState.ACTIVE && (cageTicks > 0 || graceTicks > 0);
        boolean ending = state == ArenaState.ENDING;
        if (!active && !ending) {
            return false;
        }
        if (entity instanceof Player) {
            Fighter f = findFighterByUuid(entity.getUUID());
            return f != null && !f.eliminated;
        }
        return entity.getTags().contains(TAG_ARENA)
                && entity.getTags().contains(TAG_MATCH_PREFIX + matchId);
    }

    /**
     * True when the ATTACKER behind a damage source is itself royale-protected — its
     * direct entity OR its owner (e.g. the mech that fired a projectile) is a currently-
     * protected fighter or current-match mech. The hurt mixin blocks the hit in that
     * case too, so a protected fighter/mech can neither take nor DEAL damage during a
     * protection window (workstream D). A cheap no-op outside a live royale window.
     */
    public static boolean isAttackerGraceProtected(DamageSource source) {
        if (source == null || matchMode != Mode.ROYALE) {
            return false;
        }
        return attackerEntityProtected(source.getDirectEntity())
                || attackerEntityProtected(source.getEntity());
    }

    private static boolean attackerEntityProtected(Entity attacker) {
        return attacker instanceof LivingEntity le && isGraceProtected(le);
    }

    /**
     * The one-time mech control card, sent on first mount and re-sendable via
     * {@code /mechhelp}. Self-contained en_US text describing the DEFAULT
     * keybinds (all rebindable in Controls). Kept as a single multi-line
     * component so it prints as one chat block.
     */
    static final String MOUNT_HELP_TEXT =
            "===== MECH CONTROLS =====\n"
            + "Move: W A S D    Jump / Boost: Space    Dash: Left Ctrl\n"
            + "Fire right weapon: Left Mouse    Fire left weapon: Right Mouse\n"
            + "Shoulder weapons: P (right) / O (left)    Switch mode: Y\n"
            + "Lock-on: U (hold to track targets in your sights)\n"
            + "Your mech is your health bar. In SOLO and DUEL, losing it means defeat.\n"
            + "Outside those modes, mech destruction ejects you. Type /mechhelp to see this again.";

    private static Component mountHelpCard() {
        return Component.literal(MOUNT_HELP_TEXT);
    }

    /** One accessible chat card; each authored build invokes the existing command path. */
    private static void sendGarageFleetCardIfSectorReady(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null || state != ArenaState.IDLE || player.serverLevel() != server.overworld()) {
            return;
        }
        // Joining should never scan all 680 persisted chunks or block on remote
        // marker reads. The clicked start command below remains the authoritative,
        // fail-closed map validation boundary.
        if (!AshenSpanMapContract.hasExpectedWorldIdentity(server, server.overworld())) {
            return;
        }
        MutableComponent card = Component.literal("===== GARAGE FLEET =====\n")
                .withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal("Select Operation Ashen Span deployment:\n")
                        .withStyle(ChatFormatting.GOLD));
        for (int i = 0; i < GarageFleet.size(); i++) {
            int publicBuild = i + 1;
            String buildName = GarageFleet.name(i);
            MutableComponent button = Component.literal("[" + publicBuild + " "
                            + buildName.toUpperCase(java.util.Locale.ROOT) + "]")
                    .withStyle(style -> style.withColor(ChatFormatting.AQUA)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                    "/arena solo start " + publicBuild))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    Component.literal("Deploy " + buildName))));
            card.append(button);
            card.append(Component.literal(i == GarageFleet.size() - 1 ? "" : "  "));
        }
        card.append(Component.literal("\nKeyboard fallback: /arena solo start <1-6>")
                .withStyle(ChatFormatting.GRAY));
        player.sendSystemMessage(card);
    }

    private static void broadcast(MinecraftServer server, String message) {
        server.getPlayerList().broadcastSystemMessage(Component.literal(PREFIX + message), false);
    }

    private static void sendTo(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal(PREFIX + message));
    }

    // ---------------------------------------------------------------------
    // Command handlers (wired from ArenaCommands). Return Brigadier result ints.
    // ---------------------------------------------------------------------

    /** Starts a one-player run immediately at a safe nearby surface. */
    public static int commandSoloStart(CommandSourceStack src, int build, Long requestedSeed) {
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendFailure(Component.literal(PREFIX + "Only a player can start solo combat."));
            return 0;
        }
        if (state != ArenaState.IDLE) {
            src.sendFailure(Component.literal(PREFIX + "Another arena run is already starting or active."));
            return 0;
        }
        int normalizedBuild = normalizeGarageBuild(build);
        if (normalizedBuild < 0) {
            src.sendFailure(Component.literal(PREFIX + "Build must be 1-" + GarageFleet.size() + "."));
            return 0;
        }
        if (player.isPassenger()) {
            src.sendFailure(Component.literal(PREFIX + "Dismount your current vehicle before starting a solo run."));
            return 0;
        }

        MinecraftServer server = player.getServer();
        if (server == null) {
            src.sendFailure(Component.literal(PREFIX + "Server unavailable."));
            return 0;
        }
        ServerLevel level = server.overworld();
        AshenSpanMapContract.Result contract = AshenSpanMapContract.validate(server, level);
        if (!contract.valid()) {
            src.sendFailure(Component.literal(PREFIX + contract.error()));
            return 0;
        }
        ArenaData data = ArenaData.get(server);
        RestoreRecord staleRestore = data.getPendingRestore(player.getUUID());
        if (staleRestore != null) {
            // Normally the JOIN healer already applied this. Heal once more here so
            // a recovered player cannot overwrite their true pre-crash location.
            restorePlayer(server, player, staleRestore);
            if (data.getPendingRestore(player.getUUID()) != null) {
                src.sendFailure(Component.literal(PREFIX
                        + "A prior arena restore is still pending; move to a safe loaded area and retry."));
                return 0;
            }
        }
        return startSoloRun(src, player, normalizedBuild, requestedSeed);
    }

    static int normalizeGarageBuild(int publicBuild) {
        return publicBuild >= 1 && publicBuild <= ASHEN_SPAN_BUILD_COUNT ? publicBuild - 1 : -1;
    }

    private static int startSoloRun(CommandSourceStack src, ServerPlayer player, int build, Long requestedSeed) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return 0;
        }
        ArenaData data = ArenaData.get(server);
        ServerLevel level = server.overworld();
        LivingEntity mech = GarageFleet.build(level, build);
        if (!prepareSoloGarageBuild(mech)) {
            if (mech != null) {
                mech.discard();
            }
            src.sendFailure(Component.literal(PREFIX + "Could not assemble garage build #" + (build + 1) + "."));
            return 0;
        }
        Vec3 spawn = new Vec3(AshenSpanDefinition.PLAYER_PAD.x(),
                AshenSpanDefinition.PLAYER_PAD.y(), AshenSpanDefinition.PLAYER_PAD.z());
        mech.setPos(spawn.x, spawn.y, spawn.z);
        if (!isSoloDeploymentPadClear(level, mech, player)) {
            mech.discard();
            src.sendFailure(Component.literal(PREFIX
                    + "The authored Garage deployment pad is blocked or not loaded."));
            return 0;
        }
        ServerPlayer intruder = findSoloIntruder(server, player.getUUID(), level.dimension(),
                0.0D, 0.0D, 190.0D);
        if (intruder != null) {
            mech.discard();
            src.sendFailure(Component.literal(PREFIX + "The solo venue is occupied by "
                    + intruder.getName().getString() + "; observers must use spectator mode."));
            return 0;
        }

        matchMode = Mode.SOLO;
        state = ArenaState.COUNTDOWN; // setup guard: admits only the fighter mech/director UUIDs
        matchId = data.claimMatchId();
        server.overworld().getDataStorage().save();
        long seed = requestedSeed != null ? requestedSeed : AshenSpanMapData.WORLD_SEED;

        centerX = AshenSpanDefinition.ORIGIN_X;
        centerY = AshenSpanDefinition.SPAN_DECK_Y;
        centerZ = AshenSpanDefinition.ORIGIN_Z;
        arenaDimension = level.dimension();
        matchBoundsRadius = 190.0D;
        matchMinPlayers = 1;
        matchTicks = 0;
        endingTicks = 0;
        cageTicks = 0;
        graceTicks = 0;

        if (!ashenSpanDirector.prepare(level, matchId, seed, build, player.getUUID(), mech)) {
            String reason = ashenSpanDirector.failureReason();
            mech.discard();
            resetToIdle();
            src.sendFailure(Component.literal(PREFIX + "Solo catalog rejected: " + reason));
            return 0;
        }

        fighters.clear();
        Fighter fighter = new Fighter(
                player.getUUID(), player.getName().getString(), "garage:" + build,
                player.gameMode.getGameModeForPlayer(), player.level().dimension(),
                player.getX(), player.getY(), player.getZ(), player.getYRot());
        fighters.add(fighter);
        if (data.getPendingRestore(fighter.uuid) != null) {
            mech.discard();
            resetToIdle();
            fighters.clear();
            src.sendFailure(Component.literal(PREFIX + "A prior restore record prevents a safe start."));
            return 0;
        }
        data.putPendingRestore(fighter.uuid, new RestoreRecord(
                fighter.originalGameMode, fighter.originalDimension,
                fighter.originalX, fighter.originalY, fighter.originalZ, fighter.originalYaw));
        // Durability barrier before gamemode, teleport, entity add, or mount.
        server.overworld().getDataStorage().save();

        player.setGameMode(GameType.ADVENTURE);
        mech.setPos(spawn.x, spawn.y, spawn.z);
        mech.setYRot(-90.0F);
        mech.addTag(TAG_OWNER_PREFIX + fighter.uuid);
        fighter.mech = mech; // identity must exist before EntityEvent.ADD fires
        if (!level.addFreshEntity(mech)) {
            fighter.mech = null;
            mech.discard();
            cleanup(server);
            src.sendFailure(Component.literal(PREFIX + "The player mech could not enter the world; start rolled back."));
            return 0;
        }
        player.teleportTo(level, spawn.x, spawn.y, spawn.z, -90.0F, 0.0F);
        boolean arrived = player.serverLevel() == level
                && player.position().distanceToSqr(spawn) < 4.0;
        if (!arrived || !player.startRiding(mech, true)) {
            cleanup(server);
            src.sendFailure(Component.literal(PREFIX + "Could not mount the selected mech; start rolled back."));
            return 0;
        }
        if (!ashenSpanDirector.startAfterMount(level, player, mech)) {
            String reason = ashenSpanDirector.failureReason();
            cleanup(server);
            src.sendFailure(Component.literal(PREFIX + "Mission staging failed: " + reason));
            return 0;
        }
        // Deliver the authored objective before the next server tick opens the
        // launch-bay shutters and activates the already-staged roots.
        for (String message : ashenSpanDirector.drainMessages()) {
            broadcast(server, message);
        }

        queue.remove(player.getUUID());
        soloOwner = player.getUUID();
        lastSoloBuild = build;
        lastSoloSeed = seed;
        hasLastSoloRun = true;
        state = ArenaState.ACTIVE;
        broadcast(server, "COLD RUIN SECTOR 01 — OPERATION ASHEN SPAN | "
                + GarageFleet.name(build) + " | seed " + seed + ".");
        src.sendSuccess(() -> Component.literal(PREFIX
                + "Use /arena solo status, /arena solo retry, or /arena solo stop."), false);
        return 1;
    }

    /**
     * SOLO-only launch preparation. GarageFleet.build intentionally retains its
     * legacy inventory-only semantics for ROYALE scatter and admin garage spawns;
     * Ashen Span additionally requires a full loaded magazine, no pending reload,
     * full energy, synced fuel, and full health before the entity enters the world.
     */
    static boolean prepareSoloGarageBuild(LivingEntity mech) {
        if (!(mech instanceof Pmvc01Entity customMech)) {
            return false;
        }
        customMech.resetForArenaService();
        return true;
    }

    /**
     * Validates the fixed Garage deployment pad without treating the command's
     * starting player as an obstruction.  Minecraft 1.20.1's
     * {@code noCollision(Entity, AABB)} includes entity collision shapes, so it
     * cannot be composed with the following caller-excluding entity query.
     */
    static boolean isSoloDeploymentPadClear(ServerLevel level, LivingEntity mech,
                                             ServerPlayer player) {
        AABB spawnBox = mech.getBoundingBox();
        BlockPos spawnFloor = BlockPos.containing(mech.position()).below();
        return level.hasChunkAt(spawnFloor)
                && level.getBlockState(spawnFloor).isFaceSturdy(level, spawnFloor, Direction.UP)
                && isAabbLoaded(level, spawnBox)
                && hasSturdyFootprint(level, spawnBox)
                && soloDeploymentCollisionFree(
                        level.getBlockCollisions(mech, spawnBox).iterator().hasNext(),
                        !level.getEntities(mech, spawnBox,
                                entity -> entity != player && entity.canBeCollidedWith()).isEmpty());
    }

    static boolean soloDeploymentCollisionFree(boolean hasBlockCollision,
                                                boolean hasNonPlayerObstruction) {
        return !hasBlockCollision && !hasNonPlayerObstruction;
    }

    /** Finds a nearby surface that fits the complete custom-mech bounding box. */
    private static Vec3 findSoloMechSpawn(ServerLevel level, ServerPlayer player,
                                          double originX, double originY,
                                          double originZ, LivingEntity mech) {
        int[][] offsets = {
                {0, 0}, {6, 0}, {-6, 0}, {0, 6}, {0, -6},
                {6, 6}, {6, -6}, {-6, 6}, {-6, -6},
                {12, 0}, {-12, 0}, {0, 12}, {0, -12}
        };
        for (int[] offset : offsets) {
            int xBlock = Mth.floor(originX) + offset[0];
            int zBlock = Mth.floor(originZ) + offset[1];
            if (!level.hasChunkAt(new BlockPos(xBlock, Mth.floor(originY), zBlock))) {
                continue;
            }
            int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, xBlock, zBlock);
            double[] heights = offset[0] == 0 && offset[1] == 0
                    ? new double[]{originY, surfaceY}
                    : new double[]{surfaceY};
            for (double y : heights) {
                double x = xBlock + 0.5;
                double z = zBlock + 0.5;
                BlockPos feet = BlockPos.containing(x, y, z);
                BlockPos floor = feet.below();
                if (floor.getY() < level.getMinBuildHeight()
                        || !level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)) {
                    continue;
                }
                mech.setPos(x, y, z);
                AABB box = mech.getBoundingBox();
                if (box.maxY >= level.getMaxBuildHeight()
                        || !isAabbLoaded(level, box)
                        || !hasSturdyFootprint(level, box)
                        || level.getBlockCollisions(mech, box).iterator().hasNext()
                        || !level.getEntities(mech, box,
                                entity -> entity != player && entity.canBeCollidedWith()).isEmpty()) {
                    continue;
                }
                return new Vec3(x, y, z);
            }
        }
        return null;
    }

    private static boolean isAabbLoaded(ServerLevel level, AABB box) {
        int y = Mth.floor(box.minY);
        return level.hasChunkAt(new BlockPos(Mth.floor(box.minX), y, Mth.floor(box.minZ)))
                && level.hasChunkAt(new BlockPos(Mth.floor(box.minX), y, Mth.floor(box.maxZ)))
                && level.hasChunkAt(new BlockPos(Mth.floor(box.maxX), y, Mth.floor(box.minZ)))
                && level.hasChunkAt(new BlockPos(Mth.floor(box.maxX), y, Mth.floor(box.maxZ)));
    }

    private static boolean hasSturdyFootprint(ServerLevel level, AABB box) {
        double edgeX = Math.max(0.0, (box.maxX - box.minX) * 0.5 - 0.05);
        double edgeZ = Math.max(0.0, (box.maxZ - box.minZ) * 0.5 - 0.05);
        double x = (box.minX + box.maxX) * 0.5;
        double z = (box.minZ + box.maxZ) * 0.5;
        double y = box.minY - 0.01;
        double[][] samples = {
                {x, z},
                {x - edgeX, z - edgeZ}, {x - edgeX, z + edgeZ},
                {x + edgeX, z - edgeZ}, {x + edgeX, z + edgeZ}
        };
        for (double[] sample : samples) {
            BlockPos support = BlockPos.containing(sample[0], y, sample[1]);
            if (!level.getBlockState(support).isFaceSturdy(level, support, Direction.UP)) {
                return false;
            }
        }
        return true;
    }

    private static long defaultSoloSeed(ServerLevel level, UUID fighterId, int id) {
        long seed = level.getSeed() ^ ((long) id * 0x9E3779B97F4A7C15L);
        seed ^= fighterId.getMostSignificantBits();
        seed ^= Long.rotateLeft(fighterId.getLeastSignificantBits(), 23);
        return seed;
    }

    public static int commandSoloRetry(CommandSourceStack src) {
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendFailure(Component.literal(PREFIX + "Only a player can retry solo combat."));
            return 0;
        }
        if (!hasLastSoloRun) {
            src.sendFailure(Component.literal(PREFIX + "No completed or active solo recipe exists yet."));
            return 0;
        }
        if (state != ArenaState.IDLE) {
            boolean ownsRun = matchMode == Mode.SOLO && player.getUUID().equals(soloOwner);
            if (!ownsRun && !src.hasPermission(2)) {
                src.sendFailure(Component.literal(PREFIX + "Only the active solo pilot or an operator can retry this run."));
                return 0;
            }
            if (matchMode != Mode.SOLO) {
                src.sendFailure(Component.literal(PREFIX + "A multiplayer arena match is active."));
                return 0;
            }
            cleanup(player.getServer());
        }
        return commandSoloStart(src, lastSoloBuild + 1, lastSoloSeed);
    }

    public static int commandSoloStatus(CommandSourceStack src) {
        if (matchMode == Mode.SOLO && state != ArenaState.IDLE) {
            src.sendSuccess(() -> Component.literal(PREFIX + "SOLO " + state + " | "
                    + ashenSpanDirector.status()), false);
            return 1;
        }
        String last = hasLastSoloRun
                ? "Last recipe: build " + (lastSoloBuild + 1) + " (" + GarageFleet.name(lastSoloBuild)
                        + "), seed " + lastSoloSeed + "."
                : "No solo run has started this server session.";
        src.sendSuccess(() -> Component.literal(PREFIX + "SOLO IDLE | " + last), false);
        return 1;
    }

    /** Read-only dedicated-server/offline health check for the authored map contract. */
    public static int commandSoloValidate(CommandSourceStack src) {
        MinecraftServer server = src.getServer();
        AshenSpanMapContract.Result result =
                AshenSpanMapContract.validate(server, server.overworld());
        if (!result.valid()) {
            src.sendFailure(Component.literal(PREFIX + "ASHEN SPAN CONTRACT FAIL — "
                    + result.error()));
            return 0;
        }
        src.sendSuccess(() -> Component.literal(PREFIX
                + "ASHEN SPAN CONTRACT PASS — 680 full safety chunks and "
                + AshenSpanMapContract.markers().size() + " physical markers verified."), true);
        return 1;
    }

    public static int commandSoloStop(CommandSourceStack src) {
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendFailure(Component.literal(PREFIX + "Only a player can stop solo combat."));
            return 0;
        }
        if (state == ArenaState.IDLE || matchMode != Mode.SOLO) {
            src.sendFailure(Component.literal(PREFIX + "No solo run is active."));
            return 0;
        }
        if (!player.getUUID().equals(soloOwner) && !src.hasPermission(2)) {
            src.sendFailure(Component.literal(PREFIX + "Only the active solo pilot or an operator can stop this run."));
            return 0;
        }
        if (!soloStopChangesOutcome(state)) {
            cleanup(player.getServer());
            src.sendSuccess(() -> Component.literal(PREFIX
                    + "Concluded solo run cleaned up without changing its outcome."), true);
            return 1;
        }
        Fighter activeFighter = fighters.size() == 1 ? fighters.get(0) : null;
        recordSoloDefeat(player.getServer(), activeFighter,
                AshenSpanMissionModel.DefeatReason.STOPPED, "SOLO DEFEAT — run stopped.");
        cleanup(player.getServer());
        src.sendSuccess(() -> Component.literal(PREFIX + "Solo run stopped and cleaned up."), true);
        return 1;
    }

    static boolean soloStopChangesOutcome(ArenaState currentState) {
        return currentState != ArenaState.IDLE && currentState != ArenaState.ENDING;
    }

    public static int commandSoloDebugNext(CommandSourceStack src) {
        if (state != ArenaState.ACTIVE || matchMode != Mode.SOLO) {
            src.sendFailure(Component.literal(PREFIX + "No active solo encounter to advance."));
            return 0;
        }
        src.sendFailure(Component.literal(PREFIX
                + "Authored mission phases cannot be bypassed; exact-zero gates are mandatory."));
        return 0;
    }

    public static int commandJoin(CommandSourceStack src, String mech) {
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendFailure(Component.literal(PREFIX + "Only players can join the queue."));
            return 0;
        }
        if (state == ArenaState.ACTIVE) {
            src.sendFailure(Component.literal(PREFIX + "A match is in progress. Try again once it ends."));
            return 0;
        }
        if (!ROSTER.contains(mech)) {
            src.sendFailure(Component.literal(PREFIX + "Unknown mech '" + mech + "'. Roster: " + String.join(", ", ROSTER)));
            return 0;
        }
        boolean already = queue.containsKey(player.getUUID());
        queue.put(player.getUUID(), mech);
        src.sendSuccess(() -> Component.literal(PREFIX
                + (already ? "Updated your pick to " : "Joined the queue with ") + mech
                + ". Queued: " + queue.size()), false);
        return 1;
    }

    public static int commandLeave(CommandSourceStack src) {
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendFailure(Component.literal(PREFIX + "Only players can leave."));
            return 0;
        }
        UUID uuid = player.getUUID();
        if (state == ArenaState.ACTIVE) {
            Fighter f = findFighterByUuid(uuid);
            if (f != null && !f.eliminated) {
                eliminate(player.getServer(), f, true);
                src.sendSuccess(() -> Component.literal(PREFIX + "You forfeited the match."), false);
                return 1;
            }
        }
        if (queue.remove(uuid) != null) {
            src.sendSuccess(() -> Component.literal(PREFIX + "You left the queue."), false);
            return 1;
        }
        src.sendFailure(Component.literal(PREFIX + "You are not queued or fighting."));
        return 0;
    }

    public static int commandStatus(CommandSourceStack src) {
        MinecraftServer server = src.getLevel().getServer();
        ArenaData data = ArenaData.get(server);
        Mode shownMode = state == ArenaState.IDLE ? data.getMode() : matchMode;
        StringBuilder sb = new StringBuilder(PREFIX + "State: " + state
                + " | Mode: " + shownMode);
        if (shownMode == Mode.ROYALE) {
            // Royale venue/tuning at a glance, mirroring what /arena info shows.
            sb.append("\n").append(PREFIX).append("Royale: center ")
                    .append(data.getRoyaleCenter() != null ? data.getRoyaleCenter().toString() : "not set")
                    .append(", radius ").append(data.getRoyaleRadius())
                    .append(", mechs ").append(data.getMechCount());
        }
        switch (state) {
            case IDLE, COUNTDOWN -> {
                sb.append("\n").append(PREFIX).append("Queued (").append(queue.size()).append("): ");
                sb.append(describeQueue(server));
                if (state == ArenaState.COUNTDOWN) {
                    sb.append("\n").append(PREFIX).append("Starting in ").append(countdownTicks / 20 + 1).append("s");
                }
            }
            case ACTIVE -> {
                List<String> alive = new ArrayList<>();
                for (Fighter f : fighters) {
                    if (!f.eliminated) {
                        alive.add(f.name);
                    }
                }
                sb.append("\n").append(PREFIX).append("Fighters alive (").append(alive.size()).append("): ")
                        .append(alive.isEmpty() ? "none" : String.join(", ", alive));
                int timeout = shownMode == Mode.SOLO ? SOLO_MATCH_TIMEOUT : MATCH_TIMEOUT;
                sb.append("\n").append(PREFIX).append("Time remaining: ")
                        .append(Math.max(0, (timeout - matchTicks) / 20)).append("s");
                if (shownMode == Mode.SOLO) {
                    sb.append("\n").append(PREFIX).append(ashenSpanDirector.status());
                }
            }
            case ENDING -> sb.append("\n").append(PREFIX).append("Match ending...");
        }
        String out = sb.toString();
        src.sendSuccess(() -> Component.literal(out), false);
        return 1;
    }

    private static String describeQueue(MinecraftServer server) {
        if (queue.isEmpty()) {
            return "empty";
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<UUID, String> entry : queue.entrySet()) {
            ServerPlayer p = server.getPlayerList().getPlayer(entry.getKey());
            String name = p != null ? p.getName().getString() : entry.getKey().toString().substring(0, 8);
            parts.add(name + " (" + entry.getValue() + ")");
        }
        return String.join(", ", parts);
    }

    public static int commandSetLobby(CommandSourceStack src) {
        ArenaData data = ArenaData.get(src.getLevel().getServer());
        Vec3 pos = src.getPosition();
        Vec2 rot = src.getRotation();
        ArenaPoint lobby = new ArenaPoint(src.getLevel().dimension().location(), pos.x, pos.y, pos.z, rot.y);
        data.setLobby(lobby);
        // Echo the exact point: a bare console/RCON invocation records the world
        // spawn, and the echo is what makes that mistake visible.
        src.sendSuccess(() -> Component.literal(PREFIX + "Lobby return point saved at " + lobby + "."), false);
        return 1;
    }

    public static int commandAddPad(CommandSourceStack src) {
        ArenaData data = ArenaData.get(src.getLevel().getServer());
        ResourceLocation newDim = src.getLevel().dimension().location();
        List<ArenaPoint> pads = data.getPads();
        if (!pads.isEmpty() && !pads.get(0).dimension.equals(newDim)) {
            src.sendFailure(Component.literal(PREFIX + "Pad dimension " + newDim
                    + " differs from existing pads in " + pads.get(0).dimension
                    + ". Clear pads first to build the arena elsewhere."));
            return 0;
        }
        Vec3 pos = src.getPosition();
        Vec2 rot = src.getRotation();
        ArenaPoint pad = new ArenaPoint(newDim, pos.x, pos.y, pos.z, rot.y);
        data.addPad(pad);
        int count = data.getPads().size();
        src.sendSuccess(() -> Component.literal(PREFIX + "Spawn pad #" + count + " added at " + pad + "."), false);
        return 1;
    }

    public static int commandClearPads(CommandSourceStack src) {
        ArenaData data = ArenaData.get(src.getLevel().getServer());
        data.clearPads();
        src.sendSuccess(() -> Component.literal(PREFIX + "All spawn pads cleared."), false);
        return 1;
    }

    public static int commandInfo(CommandSourceStack src) {
        ArenaData data = ArenaData.get(src.getLevel().getServer());
        StringBuilder sb = new StringBuilder(PREFIX + "Mode: " + data.getMode());
        sb.append("\n").append(PREFIX).append("Lobby: ")
                .append(data.getLobby() != null ? data.getLobby().toString() : "not set");
        sb.append("\n").append(PREFIX).append("Pads: ").append(data.getPads().size());
        for (int i = 0; i < data.getPads().size(); i++) {
            sb.append("\n").append(PREFIX).append("  #").append(i + 1).append(": ").append(data.getPads().get(i));
        }
        if (!data.getPads().isEmpty()) {
            double[] c = computeCenter(data.getPads());
            sb.append("\n").append(PREFIX).append(String.format("Center: [%.1f, %.1f, %.1f]", c[0], c[1], c[2]));
        }
        sb.append("\n").append(PREFIX).append("Royale center: ")
                .append(data.getRoyaleCenter() != null ? data.getRoyaleCenter().toString() : "not set");
        sb.append("\n").append(PREFIX).append("Royale radius: ").append(data.getRoyaleRadius());
        sb.append("\n").append(PREFIX).append("Royale mechs: ").append(data.getMechCount());
        String out = sb.toString();
        src.sendSuccess(() -> Component.literal(out), false);
        return 1;
    }

    public static int commandStart(CommandSourceStack src) {
        MinecraftServer server = src.getLevel().getServer();
        if (state != ArenaState.IDLE) {
            src.sendFailure(Component.literal(PREFIX + "A match is already starting or running."));
            return 0;
        }
        if (countViableQueued(server) < MIN_FIGHTERS) {
            src.sendFailure(Component.literal(PREFIX + "Need at least " + MIN_FIGHTERS + " queued players (online and alive)."));
            return 0;
        }
        // Venue requirement seam: ROYALE needs a center; DUEL needs pads.
        ArenaData data = ArenaData.get(server);
        if (data.getMode() == Mode.SOLO) {
            src.sendFailure(Component.literal(PREFIX
                    + "SOLO starts directly with /arena solo start <build> [seed]."));
            return 0;
        }
        if (data.getMode() == Mode.ROYALE) {
            if (data.getRoyaleCenter() == null) {
                src.sendFailure(Component.literal(PREFIX + "Royale center not set. Use /arena royale setcenter first."));
                return 0;
            }
        } else if (data.getPads().size() < MIN_PADS) {
            src.sendFailure(Component.literal(PREFIX + "Need at least " + MIN_PADS + " spawn pads."));
            return 0;
        }
        // Force-start: an admin may run a match at the bare 2-player floor regardless
        // of the royale min-players tuning (workstream E).
        beginCountdown(server, MIN_FIGHTERS);
        src.sendSuccess(() -> Component.literal(PREFIX + "Force-starting match."), true);
        return 1;
    }

    public static int commandStop(CommandSourceStack src) {
        MinecraftServer server = src.getLevel().getServer();
        cleanup(server);
        queue.clear();
        src.sendSuccess(() -> Component.literal(PREFIX + "Arena stopped, cleaned up, queue cleared."), true);
        return 1;
    }

    /**
     * Admin/test utility: spawn one garage-fleet preset at the source position,
     * UNTAGGED (it is a freebie, not match loot — the arena sweeps must leave it
     * alone). Doubles as the live verification surface for the fleet builds.
     */
    public static int commandGarage(CommandSourceStack src, int index) {
        ServerLevel level = src.getLevel();
        LivingEntity mech = GarageFleet.build(level, index);
        if (mech == null) {
            src.sendFailure(Component.literal(PREFIX + "Garage build failed (see log)."));
            return 0;
        }
        Vec3 pos = src.getPosition();
        mech.setPos(pos.x, pos.y, pos.z);
        if (!level.addFreshEntity(mech)) {
            src.sendFailure(Component.literal(PREFIX + "Could not place the mech here."));
            return 0;
        }
        src.sendSuccess(() -> Component.literal(PREFIX + "Deployed garage build #"
                + (index % GarageFleet.size()) + " (" + GarageFleet.name(index) + ")."), false);
        return 1;
    }

    // ---------------------------------------------------------------------
    // Royale command handlers
    // ---------------------------------------------------------------------

    public static int commandMode(CommandSourceStack src, Mode mode) {
        MinecraftServer server = src.getLevel().getServer();
        // Never flip mode under a live/starting match: the match snapshots its
        // mode at beginCountdown and the venue seams assume it can't change.
        if (state != ArenaState.IDLE) {
            src.sendFailure(Component.literal(PREFIX + "Cannot change mode while a match is starting or running."));
            return 0;
        }
        ArenaData.get(server).setMode(mode);
        src.sendSuccess(() -> Component.literal(PREFIX + "Arena mode set to " + mode + "."), true);
        return 1;
    }

    /**
     * Royale venue config is IDLE-only: beginCountdown freezes the scatter plan
     * around the center/radius it read, so a mid-countdown change would desync
     * the plan from the venue startMatch re-reads (and could force cold chunk
     * generation in a brand-new dimension in a single tick).
     */
    private static boolean denyUnlessIdle(CommandSourceStack src) {
        if (state != ArenaState.IDLE) {
            src.sendFailure(Component.literal(PREFIX + "Royale settings can only change while the arena is idle."));
            return true;
        }
        return false;
    }

    public static int commandRoyaleSetCenter(CommandSourceStack src) {
        if (denyUnlessIdle(src)) {
            return 0;
        }
        ArenaData data = ArenaData.get(src.getLevel().getServer());
        // Read the source position + dimension exactly like setlobby, so admins
        // can drive it via `execute in <dim> positioned X Y Z run arena royale setcenter`.
        Vec3 pos = src.getPosition();
        Vec2 rot = src.getRotation();
        ArenaPoint center = new ArenaPoint(src.getLevel().dimension().location(), pos.x, pos.y, pos.z, rot.y);
        data.setRoyaleCenter(center);
        src.sendSuccess(() -> Component.literal(PREFIX + "Royale center saved at " + center + "."), false);
        return 1;
    }

    public static int commandRoyaleRadius(CommandSourceStack src, int radius) {
        if (denyUnlessIdle(src)) {
            return 0;
        }
        if (radius < 50 || radius > 1000) {
            src.sendFailure(Component.literal(PREFIX + "Radius must be between 50 and 1000."));
            return 0;
        }
        ArenaData.get(src.getLevel().getServer()).setRoyaleRadius(radius);
        src.sendSuccess(() -> Component.literal(PREFIX + "Royale radius set to " + radius + "."), false);
        return 1;
    }

    public static int commandRoyaleMechs(CommandSourceStack src, int count) {
        if (denyUnlessIdle(src)) {
            return 0;
        }
        if (count < 2 || count > 64) {
            src.sendFailure(Component.literal(PREFIX + "Mech count must be between 2 and 64."));
            return 0;
        }
        ArenaData.get(src.getLevel().getServer()).setMechCount(count);
        src.sendSuccess(() -> Component.literal(PREFIX + "Royale mech count set to " + count + "."), false);
        return 1;
    }

    public static int commandRoyaleMinPlayers(CommandSourceStack src, int min) {
        if (denyUnlessIdle(src)) {
            return 0;
        }
        ArenaData data = ArenaData.get(src.getLevel().getServer());
        data.setRoyaleMinPlayers(min);
        // Keep the startAt >= min invariant: raise startAt if it now sits below min.
        String extra = "";
        if (data.getRoyaleStartAtPlayers() < min) {
            data.setRoyaleStartAtPlayers(min);
            extra = " (start-at raised to " + min + " to stay >= min)";
        }
        String msg = extra;
        src.sendSuccess(() -> Component.literal(PREFIX + "Royale min players set to " + min + "." + msg), false);
        return 1;
    }

    public static int commandRoyaleStartAt(CommandSourceStack src, int startAt) {
        if (denyUnlessIdle(src)) {
            return 0;
        }
        ArenaData data = ArenaData.get(src.getLevel().getServer());
        if (startAt < data.getRoyaleMinPlayers()) {
            src.sendFailure(Component.literal(PREFIX + "Start-at (" + startAt
                    + ") must be >= min players (" + data.getRoyaleMinPlayers() + ")."));
            return 0;
        }
        data.setRoyaleStartAtPlayers(startAt);
        src.sendSuccess(() -> Component.literal(PREFIX + "Royale start-at players set to " + startAt + "."), false);
        return 1;
    }

    public static int commandRoyaleGrace(CommandSourceStack src, int ticks) {
        if (denyUnlessIdle(src)) {
            return 0;
        }
        if (ticks < 200 || ticks > 2400) {
            src.sendFailure(Component.literal(PREFIX + "Grace must be between 200 and 2400 ticks."));
            return 0;
        }
        ArenaData.get(src.getLevel().getServer()).setRoyaleGraceTicks(ticks);
        src.sendSuccess(() -> Component.literal(PREFIX + "Royale grace set to " + ticks
                + " ticks (" + (ticks / 20) + "s)."), false);
        return 1;
    }

    public static int commandRoyalePve(CommandSourceStack src, RoyalePve pve) {
        if (denyUnlessIdle(src)) {
            return 0;
        }
        ArenaData.get(src.getLevel().getServer()).setRoyalePve(pve);
        src.sendSuccess(() -> Component.literal(PREFIX + "Royale PvE set to "
                + pve.name().toLowerCase() + "."), false);
        return 1;
    }

    /** Re-sends the mech control card to the invoking player. No permission needed. */
    public static int commandMechHelp(CommandSourceStack src) {
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendFailure(Component.literal(PREFIX + "Only players can use /mechhelp."));
            return 0;
        }
        player.sendSystemMessage(mountHelpCard());
        return 1;
    }
}
