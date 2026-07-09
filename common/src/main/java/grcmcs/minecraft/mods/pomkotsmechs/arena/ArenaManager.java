package grcmcs.minecraft.mods.pomkotsmechs.arena;

import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.EntityEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.event.events.common.TickEvent;
import grcmcs.minecraft.mods.pomkotsmechs.PomkotsMechs;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.entity.EntityTypeTest;
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

    private static final String TAG_ARENA = "mecharena";
    private static final String TAG_OWNER_PREFIX = "mecharena_owner_";
    private static final String PREFIX = "[Arena] ";

    private static ArenaState state = ArenaState.IDLE;
    private static final LinkedHashMap<UUID, String> queue = new LinkedHashMap<>();
    private static final List<Fighter> fighters = new ArrayList<>();

    private static int idleTimer = 0;
    private static int countdownTicks = 0;
    private static int matchTicks = 0;
    private static int endingTicks = 0;

    // Geometry of the currently running match.
    private static double centerX;
    private static double centerY;
    private static double centerZ;
    private static net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> arenaDimension;

    /** Wired from the mod's common init. Registers all server-side event hooks. */
    public static void init() {
        TickEvent.SERVER_POST.register(ArenaManager::onServerTick);
        EntityEvent.LIVING_DEATH.register(ArenaManager::onLivingDeath);
        PlayerEvent.PLAYER_QUIT.register(ArenaManager::onPlayerQuit);
        LifecycleEvent.SERVER_STARTED.register(ArenaManager::onServerStarted);
    }

    // ---------------------------------------------------------------------
    // Lifecycle events
    // ---------------------------------------------------------------------

    private static void onServerStarted(MinecraftServer server) {
        // Fresh boot: drop any in-memory state and sweep leftover arena mechs
        // that a crash may have left behind.
        resetToIdle();
        queue.clear();
        killTaggedEntities(server);
    }

    private static void onPlayerQuit(ServerPlayer player) {
        UUID uuid = player.getUUID();
        queue.remove(uuid);

        if (state != ArenaState.ACTIVE) {
            return; // no live fighters to eliminate outside an active match
        }
        Fighter f = findFighterByUuid(uuid);
        if (f != null && !f.eliminated) {
            MinecraftServer server = player.getServer();
            // Disconnect counts as elimination. Don't move them to spectator
            // (they are leaving); instead restore their pre-match state so they
            // return normally on relog. The win check runs on the next tick.
            eliminate(server, f, false);
            if (server != null) {
                restorePlayer(server, player, f);
            }
        }
    }

    private static EventResult onLivingDeath(LivingEntity entity, DamageSource source) {
        if (state == ArenaState.ACTIVE) {
            MinecraftServer server = entity.getServer();
            if (server != null) {
                if (entity.getTags().contains(TAG_ARENA)) {
                    // An arena mech died -> eliminate its owner.
                    Fighter f = findFighterByMech(entity);
                    if (f != null) {
                        eliminate(server, f, true);
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
        ArenaData data = ArenaData.get(server);
        if (queue.size() >= MIN_FIGHTERS && data.getPads().size() >= MIN_PADS) {
            idleTimer++;
            if (idleTimer >= AUTO_START_DELAY) {
                beginCountdown(server);
            }
        } else {
            idleTimer = 0;
        }
    }

    private static void beginCountdown(MinecraftServer server) {
        state = ArenaState.COUNTDOWN;
        countdownTicks = COUNTDOWN_LENGTH;
        idleTimer = 0;
        broadcast(server, "Match starting in 10 seconds! (" + queue.size() + " queued)");
    }

    private static void tickCountdown(MinecraftServer server) {
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
        List<ArenaPoint> pads = data.getPads();
        if (pads.size() < MIN_PADS) {
            broadcast(server, "Not enough spawn pads. Match cancelled.");
            resetToIdle();
            return;
        }

        arenaDimension = pads.get(0).dimensionKey();
        ServerLevel arenaLevel = server.getLevel(arenaDimension);
        if (arenaLevel == null) {
            broadcast(server, "Arena dimension is not loaded. Match cancelled.");
            resetToIdle();
            return;
        }
        computeCenter(pads);

        fighters.clear();
        int cap = pads.size();
        int index = 0;
        List<UUID> consumed = new ArrayList<>();

        Iterator<Map.Entry<UUID, String>> it = queue.entrySet().iterator();
        while (it.hasNext() && index < cap) {
            Map.Entry<UUID, String> entry = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                // Queued player is offline; leave them queued and skip.
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
            if (!deployFighter(server, arenaLevel, f, pads.get(index))) {
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

        state = ArenaState.ACTIVE;
        matchTicks = 0;
        broadcast(server, "Match started! " + fighters.size() + " fighters enter the arena.");
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
        if (mech instanceof Mob mob) {
            mob.setPersistenceRequired();
        }
        if (!arenaLevel.addFreshEntity(mech)) {
            mech.discard();
            return false;
        }
        f.mech = mech;

        return player.startRiding(mech, true);
    }

    private static void tickActive(MinecraftServer server) {
        matchTicks++;

        ServerLevel arenaLevel = arenaDimension != null ? server.getLevel(arenaDimension) : null;
        if (arenaLevel != null) {
            checkOutOfBounds(server);
        }

        if (evaluateWinCondition(server)) {
            return;
        }

        if (matchTicks >= MATCH_TIMEOUT) {
            broadcast(server, "Time limit reached — the match is a draw.");
            beginEnding(server);
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
            double dx = player.getX() - centerX;
            double dz = player.getZ() - centerZ;
            double distSq = dx * dx + dz * dz;
            if (distSq > BOUNDS_RADIUS * BOUNDS_RADIUS) {
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
        for (Fighter f : new ArrayList<>(fighters)) {
            ServerPlayer player = server.getPlayerList().getPlayer(f.uuid);
            if (player != null) {
                restorePlayer(server, player, f);
            }
        }
        fighters.clear();
        resetToIdle();
    }

    private static void abortMatch(MinecraftServer server) {
        cleanup(server);
    }

    private static void restorePlayer(MinecraftServer server, ServerPlayer player, Fighter f) {
        player.setGameMode(f.originalGameMode);

        ArenaData data = ArenaData.get(server);
        ArenaPoint lobby = data.getLobby();
        if (lobby != null) {
            ServerLevel level = server.getLevel(lobby.dimensionKey());
            if (level != null) {
                player.teleportTo(level, lobby.x, lobby.y, lobby.z, lobby.yaw, 0f);
                return;
            }
        }
        // Fall back to the recorded pre-match position.
        ServerLevel level = server.getLevel(f.originalDimension);
        if (level == null) {
            level = server.overworld();
        }
        player.teleportTo(level, f.originalX, f.originalY, f.originalZ, f.originalYaw, 0f);
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
        arenaDimension = null;
    }

    private static void computeCenter(List<ArenaPoint> pads) {
        double sx = 0, sy = 0, sz = 0;
        for (ArenaPoint pad : pads) {
            sx += pad.x;
            sy += pad.y;
            sz += pad.z;
        }
        centerX = sx / pads.size();
        centerY = sy / pads.size();
        centerZ = sz / pads.size();
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
                return findFighterByUuid(UUID.fromString(ownerTag));
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
        StringBuilder sb = new StringBuilder(PREFIX + "State: " + state);
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
        data.setLobby(new ArenaPoint(src.getLevel().dimension().location(), pos.x, pos.y, pos.z, rot.y));
        src.sendSuccess(() -> Component.literal(PREFIX + "Lobby return point saved."), false);
        return 1;
    }

    public static int commandAddPad(CommandSourceStack src) {
        ArenaData data = ArenaData.get(src.getLevel().getServer());
        Vec3 pos = src.getPosition();
        Vec2 rot = src.getRotation();
        data.addPad(new ArenaPoint(src.getLevel().dimension().location(), pos.x, pos.y, pos.z, rot.y));
        int count = data.getPads().size();
        src.sendSuccess(() -> Component.literal(PREFIX + "Spawn pad #" + count + " added."), false);
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
        StringBuilder sb = new StringBuilder(PREFIX + "Lobby: "
                + (data.getLobby() != null ? data.getLobby().toString() : "not set"));
        sb.append("\n").append(PREFIX).append("Pads: ").append(data.getPads().size());
        for (int i = 0; i < data.getPads().size(); i++) {
            sb.append("\n").append(PREFIX).append("  #").append(i + 1).append(": ").append(data.getPads().get(i));
        }
        if (!data.getPads().isEmpty()) {
            computeCenter(data.getPads());
            sb.append("\n").append(PREFIX).append(String.format("Center: [%.1f, %.1f, %.1f]", centerX, centerY, centerZ));
        }
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
        if (queue.size() < MIN_FIGHTERS) {
            src.sendFailure(Component.literal(PREFIX + "Need at least " + MIN_FIGHTERS + " queued players."));
            return 0;
        }
        if (ArenaData.get(server).getPads().size() < MIN_PADS) {
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
        src.sendSuccess(() -> Component.literal(PREFIX + "Arena stopped and cleaned up."), true);
        return 1;
    }
}
