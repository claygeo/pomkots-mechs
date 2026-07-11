package grcmcs.minecraft.mods.pomkotsmechs.arena;

import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.EntityEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.event.events.common.TickEvent;
import grcmcs.minecraft.mods.pomkotsmechs.PomkotsMechs;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private static final int AUTO_START_DELAY = 200;   // ticks queue must stay ready before auto-start
    private static final int COUNTDOWN_LENGTH = 200;   // 10s
    private static final int ENDING_LENGTH = 100;      // 5s wind-down
    private static final int MATCH_TIMEOUT = 8 * 60 * 20; // 8 minutes of ACTIVE
    private static final double BOUNDS_RADIUS = 150.0; // horizontal blocks from center
    private static final int BOUNDS_GRACE = 200;       // ticks allowed outside before elimination
    private static final int SPECTATE_HEIGHT = 30;

    // Royale tuning.
    private static final int CAGE_LENGTH = 200;        // 10s the glass cage holds fighters
    private static final double RING_RADIUS = 3.0;     // fighters spawn on this ring inside the cage
    private static final int CAGE_HALF = 4;            // 9x9 footprint (center +/- 4)
    private static final int CAGE_WALL_HEIGHT = 5;     // wall blocks; roof sits one above
    private static final int SCATTER_MIN_DIST = 30;    // nearest a scattered mech may spawn
    private static final double ROYALE_BOUNDS_FACTOR = 1.25; // bounds = royale radius * this
    private static final int PREP_CHUNKS_PER_TICK = 2; // scatter chunk-gen budget during countdown
    private static final double ZONE_SHRINK_PER_TICK = 0.05; // ~1 block/s once the endgame zone closes
    private static final double ZONE_MIN_RADIUS = 15.0;      // the zone never shrinks below this
    private static final int ROYALE_HARD_CAP = MATCH_TIMEOUT + 6000; // absolute draw backstop (+5 min)

    private static final String TAG_ARENA = "mecharena";
    private static final String TAG_OWNER_PREFIX = "mecharena_owner_";
    private static final String TAG_MATCH_PREFIX = "mecharena_match_";
    private static final String PREFIX = "[Arena] ";

    private static ArenaState state = ArenaState.IDLE;
    private static final LinkedHashMap<UUID, String> queue = new LinkedHashMap<>();
    private static final List<Fighter> fighters = new ArrayList<>();

    private static int idleTimer = 0;
    private static int countdownTicks = 0;
    private static int matchTicks = 0;
    private static int endingTicks = 0;
    private static int sweepTimer = 0;
    private static int cageTicks = 0;   // royale only: ticks the cage still holds fighters

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
    // Scatter plan computed at countdown start: spawn points plus the deduped
    // set of chunks they need. Chunks are generated a few per tick DURING the
    // countdown so startMatch never forces dozens of Lost Cities chunks in one
    // tick (watchdog risk). Cleared by resetToIdle.
    private static final List<int[]> scatterPoints = new ArrayList<>();
    private static final List<long[]> scatterChunksToPrep = new ArrayList<>();
    // Live current-match loot mech count, tracked at spawn/death instead of
    // scanning loaded entities (unloaded outer chunks would undercount).
    private static int royaleMechsAlive = 0;

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
        queue.clear();
        fighters.clear();
        killTaggedEntities(server);
        // A crash mid-royale can leave a glass cage in the city; the recorded
        // positions outlive the process in ArenaData, so remove them now.
        removeCageBlocks(server);
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

        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        // A pending record means this player still owes a restore. Whatever the
        // state (ACTIVE/ENDING/even IDLE after a partial cleanup), heal them on
        // the way out so they never relog stranded in ADVENTURE/SPECTATOR.
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
        restorePlayer(server, player, rec);
    }

    private static void onPlayerJoin(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        // Heals crash-mid-match relogs (even days later): the record outlived the
        // match in ArenaData, so apply it the instant the player is back.
        RestoreRecord rec = ArenaData.get(server).getPendingRestore(player.getUUID());
        if (rec != null) {
            restorePlayer(server, player, rec);
        }
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
        if (!(level instanceof ServerLevel)) {
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

    private static EventResult onLivingDeath(LivingEntity entity, DamageSource source) {
        if (state == ArenaState.ACTIVE) {
            MinecraftServer server = entity.getServer();
            if (server != null) {
                if (entity.getTags().contains(TAG_ARENA)) {
                    if (matchMode == Mode.ROYALE) {
                        // Mechs are GEAR, not lives: a downed mech eliminates no
                        // one. The count is tracked at spawn/death rather than
                        // scanned, so mechs idling in unloaded chunks still count.
                        if (entity.getTags().contains(TAG_MATCH_PREFIX + matchId)) {
                            royaleMechsAlive = Math.max(0, royaleMechsAlive - 1);
                            broadcast(server, "A mech went down — " + royaleMechsAlive + " mechs remain.");
                        }
                    } else {
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

    // ---------------------------------------------------------------------
    // State machine (ticked at the end of every server tick)
    // ---------------------------------------------------------------------

    private static void onServerTick(MinecraftServer server) {
        switch (state) {
            case IDLE -> tickIdle(server);
            case COUNTDOWN -> tickCountdown(server);
            case ACTIVE -> tickActive(server);
            case ENDING -> tickEnding(server);
        }
    }

    private static void tickIdle(MinecraftServer server) {
        // Periodic stray-mech sweep. The EntityEvent.ADD guard covers Forge chunk
        // loads, but on Fabric that event only fires for fresh spawns — this
        // loader-agnostic sweep is what actually reaps crash leftovers there.
        if (++sweepTimer >= 600) {
            sweepTimer = 0;
            sweepStrayMechs(server);
        }
        ArenaData data = ArenaData.get(server);
        // ROYALE needs a center, not pads; DUEL needs pads. Fighter minimum is
        // shared. Anything else keeps the auto-start timer disarmed.
        boolean venueReady = data.getMode() == Mode.ROYALE
                ? data.getRoyaleCenter() != null
                : data.getPads().size() >= MIN_PADS;
        if (countViableQueued(server) >= MIN_FIGHTERS && venueReady) {
            idleTimer++;
            if (idleTimer >= AUTO_START_DELAY) {
                beginCountdown(server);
            }
        } else {
            idleTimer = 0;
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
        for (ServerLevel level : server.getAllLevels()) {
            List<? extends Entity> tagged = level.getEntities(
                    EntityTypeTest.forClass(Entity.class),
                    e -> e.getTags().contains(TAG_ARENA));
            for (Entity e : tagged) {
                boolean legit;
                if (royaleLive) {
                    // ROYALE: scattered mechs are unowned; the current match tag
                    // is what keeps them from being reaped by our own sweep.
                    legit = e.getTags().contains(TAG_MATCH_PREFIX + matchId);
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

    private static void beginCountdown(MinecraftServer server) {
        state = ArenaState.COUNTDOWN;
        countdownTicks = COUNTDOWN_LENGTH;
        idleTimer = 0;
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
        for (int i = 0; i < data.getMechCount(); i++) {
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
        if (countViableQueued(server) < MIN_FIGHTERS) {
            // Someone left/quit during the countdown. Cancel WITHOUT wiping the
            // queue; auto-start re-arms once enough players are queued again.
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
        // player deploys onto the spawn ring.
        int cap = mode == Mode.ROYALE ? countViableQueued(server) : pads.size();
        int index = 0;
        List<UUID> consumed = new ArrayList<>();

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
            // Add before deploying so a mid-deploy abort still restores this player.
            fighters.add(f);
            // Persist the pre-match snapshot BEFORE any mutation, so even a
            // partial deploy (or a crash) can always heal this player. Never
            // overwrite an unapplied record: it holds the player's TRUE original
            // state from an earlier match that hasn't been healed yet.
            if (data.getPendingRestore(f.uuid) == null) {
                data.putPendingRestore(f.uuid, new RestoreRecord(
                        f.originalGameMode, f.originalDimension,
                        f.originalX, f.originalY, f.originalZ, f.originalYaw));
            }
            // Deploy seam: DUEL mounts a mech on a pad; ROYALE drops the fighter
            // on foot onto the cage ring (loot mechs are scattered separately).
            boolean deployed = mode == Mode.ROYALE
                    ? deployFighterRoyale(server, arenaLevel, f, center, cap, index)
                    : deployFighter(server, arenaLevel, f, pads.get(index));
            if (!deployed) {
                broadcast(server, "Failed to deploy " + f.name + ". Match aborted.");
                abortMatch(server);
                return;
            }
            consumed.add(entry.getKey());
            index++;
        }

        for (UUID uuid : consumed) {
            queue.remove(uuid);
        }

        if (fighters.size() < MIN_FIGHTERS) {
            broadcast(server, "Not enough available fighters. Match cancelled.");
            abortMatch(server);
            return;
        }

        if (mode == Mode.ROYALE) {
            // Build the holding cage around the ring and scatter the loot mechs
            // while state is still COUNTDOWN, so the ADD guard admits the scatter
            // under the current match tag.
            buildCage(server, arenaLevel, center);
            scatterMechs(arenaLevel);
            cageTicks = CAGE_LENGTH;
        } else {
            cageTicks = 0;
        }

        state = ArenaState.ACTIVE;
        matchTicks = 0;
        if (mode == Mode.ROYALE) {
            broadcast(server, "Royale started! " + fighters.size()
                    + " fighters caged — grab a mech when the cage drops.");
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
     * ROYALE deploy: the pre-match snapshot is already recorded (same shared code
     * path as DUEL), so this only performs the physical setup — ADVENTURE + a
     * teleport onto the cage ring. NO mech is spawned or mounted: royale mechs
     * are scattered loot, not a fighter's life. Returns false if the teleport was
     * cancelled, in which case the caller aborts and everyone is restored.
     */
    private static boolean deployFighterRoyale(MinecraftServer server, ServerLevel arenaLevel, Fighter f,
                                               double[] center, int ringCount, int index) {
        ServerPlayer player = server.getPlayerList().getPlayer(f.uuid);
        if (player == null) {
            return false;
        }
        double angle = ringCount > 0 ? (2.0 * Math.PI * index / ringCount) : 0.0;
        double px = center[0] + RING_RADIUS * Math.cos(angle);
        double pz = center[2] + RING_RADIUS * Math.sin(angle);
        // Face the ring center so caged fighters look inward.
        float yaw = (float) (Math.toDegrees(-Math.atan2(center[0] - px, center[2] - pz)));

        player.setGameMode(GameType.ADVENTURE);
        player.teleportTo(arenaLevel, px, center[1], pz, yaw, 0f);
        return player.serverLevel() == arenaLevel;
    }

    /**
     * Builds the glass holding cage around the spawn ring: a hollow 9x9 box, five
     * walls high, with a roof, plus a floor only where the ground is missing.
     * Only positions that are AIR are overwritten (never real terrain), and every
     * placed position is recorded in ArenaData so it can be removed exactly — even
     * after a crash.
     */
    private static void buildCage(MinecraftServer server, ServerLevel level, double[] center) {
        ArenaData data = ArenaData.get(server);
        ResourceLocation dim = level.dimension().location();
        int bx = Mth.floor(center[0]);
        int by = Mth.floor(center[1]);
        int bz = Mth.floor(center[2]);
        // Pass 1: compute every position we would fill (currently AIR) into the
        // ledger — a WRITE-AHEAD log. Nothing is placed yet.
        List<CageBlock> plan = new ArrayList<>();
        for (int dx = -CAGE_HALF; dx <= CAGE_HALF; dx++) {
            for (int dz = -CAGE_HALF; dz <= CAGE_HALF; dz++) {
                // Floor one below the fighters' feet, roof above the walls.
                planCageBlock(level, dim, plan, bx + dx, by - 1, bz + dz);
                planCageBlock(level, dim, plan, bx + dx, by + CAGE_WALL_HEIGHT, bz + dz);
                // Walls: perimeter of the footprint only.
                boolean perimeter = dx == -CAGE_HALF || dx == CAGE_HALF
                        || dz == -CAGE_HALF || dz == CAGE_HALF;
                if (perimeter) {
                    for (int dy = 0; dy < CAGE_WALL_HEIGHT; dy++) {
                        planCageBlock(level, dim, plan, bx + dx, by + dy, bz + dz);
                    }
                }
            }
        }
        // Ledger to disk BEFORE any glass exists in the world: after a crash the
        // ledger can only ever list MORE positions than were placed (removal
        // tolerates never-placed entries — it only clears glass), never fewer.
        data.setCageBlocks(plan);
        server.overworld().getDataStorage().save();
        // Pass 2: place the glass.
        BlockState glass = Blocks.GLASS.defaultBlockState();
        for (CageBlock cb : plan) {
            level.setBlockAndUpdate(cb.pos, glass);
        }
    }

    /** Adds the position to the cage plan iff it is currently AIR. */
    private static void planCageBlock(ServerLevel level, ResourceLocation dim,
                                      List<CageBlock> plan, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        if (level.getBlockState(pos).isAir()) {
            plan.add(new CageBlock(dim, pos));
        }
    }

    /**
     * Scatters {@code mechCount} unowned loot mechs across the city: uniform angle,
     * distance {@code SCATTER_MIN_DIST}..radius from center, surface Y from the
     * motion-blocking heightmap. The chunk is force-generated first so getHeight is
     * meaningful on ungenerated terrain. Each mech is tagged with the current match
     * id (and no owner tag) so the stray sweeps treat it as legitimate loot.
     */
    private static void scatterMechs(ServerLevel level) {
        RandomSource rand = level.getRandom();
        royaleMechsAlive = 0;
        for (int i = 0; i < scatterPoints.size(); i++) {
            int[] pt = scatterPoints.get(i);
            String mechId = ROSTER.get(i % ROSTER.size());
            ResourceLocation id = new ResourceLocation(PomkotsMechs.MODID, mechId);
            if (!BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
                continue;
            }
            int x = pt[0];
            int z = pt[1];
            // Chunks were prepped across the countdown (planScatter/tickCountdown);
            // getChunk here is a cheap cache hit that also covers the rare case of
            // a countdown too short to exhaust the prep list.
            level.getChunk(x >> 4, z >> 4);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);

            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);
            Entity spawned = type.create(level);
            if (!(spawned instanceof LivingEntity mech)) {
                if (spawned != null) {
                    spawned.discard();
                }
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
        broadcast(level.getServer(), royaleMechsAlive + " mechs deployed across the city.");
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

        // ROYALE cage phase: fighters are held in the glass box. No mount policy,
        // no out-of-bounds, no win check while the cage still stands — just count
        // it down and break it. (DUEL keeps cageTicks == 0 and skips all of this.)
        if (matchMode == Mode.ROYALE && cageTicks > 0) {
            tickCage(server);
            return;
        }

        // Mount policy seam: DUEL evicts mech thieves. In ROYALE any player may
        // mount any mech — that scramble IS the game — so this never runs.
        if (matchMode == Mode.DUEL) {
            ejectMechThieves();
        }

        ServerLevel arenaLevel = arenaDimension != null ? server.getLevel(arenaDimension) : null;
        if (arenaLevel != null) {
            checkOutOfBounds(server);
        }

        if (evaluateWinCondition(server)) {
            return;
        }

        if (matchTicks >= MATCH_TIMEOUT) {
            if (matchMode == Mode.ROYALE) {
                // Royale endgame: no draw — the zone closes on the center at
                // ~1 block/s and the EXISTING bounds check eliminates whoever
                // stays outside, forcing a winner. Absolute backstop draw only
                // if the shrink somehow cannot resolve it.
                if (matchTicks == MATCH_TIMEOUT) {
                    broadcast(server, "THE ZONE IS CLOSING — get to the center!");
                }
                matchBoundsRadius = Math.max(ZONE_MIN_RADIUS, matchBoundsRadius - ZONE_SHRINK_PER_TICK);
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
     * ROYALE cage countdown. Announces the break at 10/5/4/3/2/1 seconds and, when
     * it lapses, removes ONLY the recorded cage blocks and turns the fighters
     * loose. No elimination can happen here — the ACTIVE tick skips bounds and win
     * checks entirely while {@code cageTicks > 0}.
     */
    private static void tickCage(MinecraftServer server) {
        if (cageTicks % 20 == 0) {
            int secs = cageTicks / 20;
            if (secs == 10 || secs <= 5) {
                broadcast(server, "Cage breaks in " + secs + "...");
            }
        }
        cageTicks--;
        if (cageTicks <= 0) {
            removeCageBlocks(server);
            broadcast(server, "GO! Find a mech!");
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

    private static void checkOutOfBounds(MinecraftServer server) {
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
        ArenaData data = ArenaData.get(server);
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
            }
        }
        fighters.clear();
        resetToIdle();
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
     */
    private static void restorePlayer(MinecraftServer server, ServerPlayer player, RestoreRecord record) {
        player.setGameMode(record.gameMode);

        ArenaData data = ArenaData.get(server);
        ArenaPoint lobby = data.getLobby();
        if (lobby != null) {
            ServerLevel level = server.getLevel(lobby.dimensionKey());
            if (level != null) {
                player.teleportTo(level, lobby.x, lobby.y, lobby.z, lobby.yaw, 0f);
                if (player.serverLevel() == level) {
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
        if (player.serverLevel() == level) {
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
        state = ArenaState.IDLE;
        idleTimer = 0;
        countdownTicks = 0;
        matchTicks = 0;
        endingTicks = 0;
        cageTicks = 0;
        arenaDimension = null;
        scatterPoints.clear();
        scatterChunksToPrep.clear();
        royaleMechsAlive = 0;
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

    private static void broadcast(MinecraftServer server, String message) {
        server.getPlayerList().broadcastSystemMessage(Component.literal(PREFIX + message), false);
    }

    private static void sendTo(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal(PREFIX + message));
    }

    // ---------------------------------------------------------------------
    // Command handlers (wired from ArenaCommands). Return Brigadier result ints.
    // ---------------------------------------------------------------------

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
        StringBuilder sb = new StringBuilder(PREFIX + "State: " + state
                + " | Mode: " + data.getMode());
        if (data.getMode() == Mode.ROYALE) {
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
                sb.append("\n").append(PREFIX).append("Time remaining: ")
                        .append(Math.max(0, (MATCH_TIMEOUT - matchTicks) / 20)).append("s");
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
        if (data.getMode() == Mode.ROYALE) {
            if (data.getRoyaleCenter() == null) {
                src.sendFailure(Component.literal(PREFIX + "Royale center not set. Use /arena royale setcenter first."));
                return 0;
            }
        } else if (data.getPads().size() < MIN_PADS) {
            src.sendFailure(Component.literal(PREFIX + "Need at least " + MIN_PADS + " spawn pads."));
            return 0;
        }
        beginCountdown(server);
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

    public static int commandRoyaleSetCenter(CommandSourceStack src) {
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
        if (radius < 50 || radius > 1000) {
            src.sendFailure(Component.literal(PREFIX + "Radius must be between 50 and 1000."));
            return 0;
        }
        ArenaData.get(src.getLevel().getServer()).setRoyaleRadius(radius);
        src.sendSuccess(() -> Component.literal(PREFIX + "Royale radius set to " + radius + "."), false);
        return 1;
    }

    public static int commandRoyaleMechs(CommandSourceStack src, int count) {
        if (count < 2 || count > 64) {
            src.sendFailure(Component.literal(PREFIX + "Mech count must be between 2 and 64."));
            return 0;
        }
        ArenaData.get(src.getLevel().getServer()).setMechCount(count);
        src.sendSuccess(() -> Component.literal(PREFIX + "Royale mech count set to " + count + "."), false);
        return 1;
    }
}
